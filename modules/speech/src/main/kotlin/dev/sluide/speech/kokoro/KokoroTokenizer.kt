package dev.sluide.speech.kokoro

import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.speech.text.Abbreviations

/**
 * Where a word is in [TokenizedChunk.ids]: [start] to [end] are `SpokenText` offsets (ends exclusive), the tokens
 * [firstToken] to [endToken) index into the ids (the model's pad tokens are not counted).
 */
internal data class WordSpan(
    val start: Int,
    val end: Int,
    val firstToken: Int,
    val endToken: Int,
)

/** The model input of one chunk (without the pads) and where each word of the chunk is in it. */
internal class TokenizedChunk(
    val ids: LongArray,
    val words: List<WordSpan>,
)

/**
 * Turns a [SpeechChunk] into Kokoro token ids the way misaki does: the words get their phonemes from [phonemes]
 * (espeak plus [EspeakToKokoro]), the punctuation of Kokoro's vocabulary becomes tokens, a space token goes between
 * pieces that had whitespace between them. Characters the [vocab] lacks are dropped.
 *
 * Known limit: a leading elision apostrophe (`'90s`, `'n'`) is read as an opening quote.
 */
internal class KokoroTokenizer(
    private val phonemes: (word: String, language: String) -> String,
    private val vocab: KokoroVocab,
) {
    private sealed interface Piece {
        val start: Int
        val end: Int

        class Word(
            override val start: Int,
            override val end: Int,
        ) : Piece

        class Punct(
            override val start: Int,
            override val end: Int,
            val char: Char,
        ) : Piece
    }

    private val spaceId = vocab.id(' ')
    private val sentenceIds = SENTENCE.mapNotNull { vocab.id(it) }.toSet()
    private val clauseIds = CLAUSE.mapNotNull { vocab.id(it) }.toSet()

    /** [language] is `en-US` or `en-GB`. Calls [phonemes], which blocks: not on the FX thread. */
    fun tokenize(
        chunk: SpeechChunk,
        language: String,
    ): TokenizedChunk {
        val ids = ArrayList<Long>()
        val words = ArrayList<WordSpan>()
        var previousEnd = -1
        var pendingSpace = false
        val pieces = pieces(chunk.text)
        for ((index, piece) in pieces.withIndex()) {
            if (previousEnd >= 0 && piece.start > previousEnd) pendingSpace = true
            previousEnd = piece.end
            val tokens: List<Long> =
                when (piece) {
                    is Piece.Punct -> {
                        listOfNotNull(vocab.id(piece.char))
                    }

                    is Piece.Word -> {
                        val word = chunk.text.substring(piece.start, piece.end)
                        val previousIsWord = pieces.getOrNull(index - 1) is Piece.Word
                        val nextIsWord = pieces.getOrNull(index + 1) is Piece.Word
                        wordPhonemes(word, previousIsWord, nextIsWord, language).mapNotNull { vocab.id(it) }
                    }
                }
            if (tokens.isNotEmpty() && pendingSpace) {
                if (ids.isNotEmpty()) spaceId?.let { ids.add(it) }
                pendingSpace = false
            }
            val first = ids.size
            ids.addAll(tokens)
            if (piece is Piece.Word) {
                words.add(WordSpan(chunk.start + piece.start, chunk.start + piece.end, first, ids.size))
            }
        }
        return TokenizedChunk(ids.toLongArray(), words)
    }

    /** espeak spells the article "a" as the letter; function words are spoken without stress. */
    private fun wordPhonemes(
        word: String,
        previousIsWord: Boolean,
        nextIsWord: Boolean,
        language: String,
    ): String =
        when {
            FunctionWords.isArticleA(word, previousIsWord, nextIsWord) -> "ɐ"
            FunctionWords.isUnstressed(word) -> phonemes(word, language).filter { it != 'ˈ' && it != 'ˌ' }
            else -> phonemes(word, language)
        }

    /**
     * Cuts [tokenized] into pieces of at most [max] tokens, at the last sentence end, else clause end, else a space
     * between words (espeak puts spaces inside the phonemes of one word, e.g. a number), else outside a word after at
     * most [max] tokens (inside only for one very long word). Space tokens at the ends of a piece are dropped.
     */
    fun split(
        tokenized: TokenizedChunk,
        max: Int = MAX_TOKENS,
    ): List<TokenizedChunk> {
        val ids = tokenized.ids
        if (ids.size <= max) return listOf(tokenized)
        val insideWord = BooleanArray(ids.size)
        for (w in tokenized.words) for (t in w.firstToken until minOf(w.endToken, ids.size)) insideWord[t] = true
        val continuing = BooleanArray(ids.size)
        for (w in tokenized.words) for (t in w.firstToken + 1 until minOf(w.endToken, ids.size)) continuing[t] = true
        val cuts = mutableListOf(0)
        while (ids.size - cuts.last() > max) {
            val start = cuts.last()
            val limit = start + max
            cuts.add(
                cutAfter(ids, insideWord, start, limit, sentenceIds)
                    ?: cutAfter(ids, insideWord, start, limit, clauseIds)
                    ?: lastSpace(ids, insideWord, start, limit)
                    ?: wordBoundary(continuing, start, limit),
            )
        }
        cuts.add(ids.size)
        return (0 until cuts.size - 1).mapNotNull { piece(tokenized, cuts[it], cuts[it + 1], it == cuts.size - 2) }
    }

    private fun cutAfter(
        ids: LongArray,
        insideWord: BooleanArray,
        start: Int,
        limit: Int,
        marks: Set<Long>,
    ): Int? {
        for (p in limit downTo start + 1) if (ids[p - 1] in marks && !insideWord[p - 1]) return p
        return null
    }

    private fun lastSpace(
        ids: LongArray,
        insideWord: BooleanArray,
        start: Int,
        limit: Int,
    ): Int? {
        val space = spaceId ?: return null
        for (p in limit downTo start + 1) if (ids[p] == space && !insideWord[p]) return p
        return null
    }

    private fun wordBoundary(
        continuing: BooleanArray,
        start: Int,
        limit: Int,
    ): Int {
        for (p in limit downTo start + 1) if (!continuing[p]) return p
        return limit
    }

    /** The tokens [from] to [to) with their words; null when nothing is left of it. */
    private fun piece(
        tokenized: TokenizedChunk,
        from: Int,
        to: Int,
        last: Boolean,
    ): TokenizedChunk? {
        val ids = tokenized.ids
        var a = from
        var b = to
        while (a < b && ids[a] == spaceId) a++
        while (b > a && ids[b - 1] == spaceId) b--
        val words =
            tokenized.words
                .filter { it.firstToken >= from && (it.firstToken < to || (last && it.firstToken == to)) }
                .map {
                    val first = it.firstToken.coerceIn(a, b)
                    WordSpan(it.start, it.end, first - a, it.endToken.coerceIn(first, b) - a)
                }
        if (a == b && words.isEmpty()) return null
        return TokenizedChunk(ids.copyOfRange(a, b), words)
    }

    private fun pieces(text: String): List<Piece> {
        val result = ArrayList<Piece>()
        val state = ScanState()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            var runEnd = i
            while (runEnd < text.length && !text[runEnd].isWhitespace()) runEnd++
            scanRun(text, i, runEnd, result, state)
            i = runEnd
        }
        return result
    }

    private fun scanRun(
        text: String,
        runStart: Int,
        runEnd: Int,
        out: MutableList<Piece>,
        state: ScanState,
    ) {
        var j = runStart
        while (j < runEnd) {
            val c = text[j]
            when (kind(c)) {
                Kind.WORD -> {
                    var end = wordEnd(text, j, runStart, runEnd, state)
                    if (keepsFinalDot(text, j, end, runEnd)) end++
                    out.add(Piece.Word(j, end))
                    j = end
                }

                Kind.PUNCT -> {
                    out.add(Piece.Punct(j, j + 1, if (c == '–') '—' else c))
                    j++
                }

                Kind.AMBIGUOUS -> {
                    if (c == '.') {
                        var dots = 1
                        while (j + dots < runEnd && text[j + dots] == '.') dots++
                        if (dots >= 3) {
                            out.add(Piece.Punct(j, j + dots, '…'))
                        } else {
                            for (k in 0 until dots) out.add(Piece.Punct(j + k, j + k + 1, '.'))
                        }
                        j += dots
                    } else {
                        // a quote with no word before it opens, any other closes
                        val opens = j == runStart || kind(text[j - 1]) != Kind.WORD
                        state.openSingleQuote = opens
                        out.add(Piece.Punct(j, j + 1, '"'))
                        j++
                    }
                }
            }
        }
    }

    /** The end of the word starting at [from]: word characters, and `'’‘.` between two word characters. */
    private fun wordEnd(
        text: String,
        from: Int,
        runStart: Int,
        runEnd: Int,
        state: ScanState,
    ): Int {
        var k = from
        while (k < runEnd) {
            when (kind(text[k])) {
                Kind.WORD -> {
                    k++
                }

                Kind.AMBIGUOUS -> {
                    if (k > runStart && kind(text[k - 1]) == Kind.WORD && k + 1 < runEnd &&
                        kind(text[k + 1]) == Kind.WORD
                    ) {
                        k++
                    } else if (isPossessiveApostrophe(text, k, runEnd, state)) {
                        return k + 1
                    } else {
                        return k
                    }
                }

                Kind.PUNCT -> {
                    // thousands separators and times: 1,000,000 and 3:30 (exactly two digits after the colon, not 16:9)
                    if (digitSeparator(text, k, from, runEnd)) {
                        k++
                    } else {
                        return k
                    }
                }
            }
        }
        return k
    }

    private fun digitSeparator(
        text: String,
        k: Int,
        from: Int,
        runEnd: Int,
    ): Boolean {
        if (k <= from || !text[k - 1].isDigit() || k + 1 >= runEnd || !text[k + 1].isDigit()) return false
        return when (text[k]) {
            ',' -> true
            ':' -> k + 2 < runEnd && text[k + 2].isDigit() && (k + 3 >= runEnd || !text[k + 3].isDigit())
            else -> false
        }
    }

    /** `Smiths'`: an apostrophe after an s that ends the word, when no single quote is open. */
    private fun isPossessiveApostrophe(
        text: String,
        k: Int,
        runEnd: Int,
        state: ScanState,
    ): Boolean =
        text[k] in "'’" && !state.openSingleQuote && k > 0 && text[k - 1] in "sS" &&
            (k + 1 == runEnd || kind(text[k + 1]) != Kind.WORD)

    /**
     * Whether the period after the word [from] to [end] stays in the word: a known abbreviation or initials, with more
     * text after it in the chunk (at the end of the chunk it is the sentence end).
     */
    private fun keepsFinalDot(
        text: String,
        from: Int,
        end: Int,
        runEnd: Int,
    ): Boolean {
        if (end + 1 != runEnd || text[end] != '.') return false
        var next = runEnd
        while (next < text.length && text[next].isWhitespace()) next++
        if (next >= text.length) return false
        val word = text.substring(from, end)
        return Abbreviations.isAbbreviation(word, text[next]) || Abbreviations.isInitials(word)
    }

    private class ScanState {
        var openSingleQuote = false
    }

    private enum class Kind { WORD, PUNCT, AMBIGUOUS }

    private fun kind(c: Char): Kind =
        when {
            c in PUNCTUATION -> Kind.PUNCT
            c in AMBIGUOUS -> Kind.AMBIGUOUS
            else -> Kind.WORD
        }

    companion object {
        /** The most tokens one model call takes (its 512 positions minus the two pads). */
        const val MAX_TOKENS = 510
        private const val SENTENCE = ".!?…"
        private const val CLAUSE = ",;:—"
        private const val PUNCTUATION = ";:,!?—…\"()“”–"
        private const val AMBIGUOUS = ".'’‘"
    }
}
