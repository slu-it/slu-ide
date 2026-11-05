package dev.sluide.textediting

/** Splits plain text into sentences as ranges; no recursion, linear in the text, fine for 1 MiB. */
object SentenceRanges {
    /**
     * The sentence ranges of [text] in order, trimmed of surrounding whitespace, as `IntRange`s whose `last` is
     * inclusive (`text.substring(range)` is the sentence). A sentence ends after `.`, `!`, `?` or `…` (runs included)
     * and any closing quotes or brackets, when whitespace or the end follows, and at every line break (`"\n"`, which
     * belongs to no sentence). Abbreviations of [abbreviations], single initials (not `I`), decimals and an ellipsis
     * before a lowercase word end none. Known limit: German ordinals ("am 3. Oktober") end a sentence.
     */
    fun split(
        text: String,
        abbreviations: Set<String> = Abbreviations.ENGLISH,
    ): List<IntRange> {
        val sentences = mutableListOf<IntRange>()
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            sentencesIn(text, start, end, abbreviations, sentences)
            start = end + 1
        }
        return sentences
    }

    /**
     * [range] of [text] (as returned by [split]) split in two for a fast start when it is longer than [maxChars]: after
     * its first clause mark (`,` `;` `:` before a space, `—`, or ` – `) from 20 chars on and before [maxChars], else at
     * its last space before [maxChars]. Both pieces are trimmed ranges in [text]'s offsets. A range within [maxChars],
     * or one without a place to split, comes back alone.
     */
    fun splitAtClause(
        text: String,
        range: IntRange,
        maxChars: Int = 120,
    ): List<IntRange> {
        val base = range.first
        val piece = text.substring(range)
        if (piece.length <= maxChars) return listOf(range)
        val split =
            (CLAUSE_MIN_CHARS until maxChars).firstNotNullOfOrNull { clauseMarkEnd(piece, it) }
                ?: (maxChars - 1 downTo 1).firstOrNull { piece[it] == ' ' }
                ?: return listOf(range)
        val head = trimmed(piece, 0, split) ?: return listOf(range)
        val tail = trimmed(piece, split, piece.length) ?: return listOf(range)
        return listOf(head, tail).map { (base + it.first)..(base + it.last) }
    }

    /** Adds the trimmed, non-empty sentences of [text] from [start] to [end], which holds no line break. */
    private fun sentencesIn(
        text: String,
        start: Int,
        end: Int,
        abbreviations: Set<String>,
        into: MutableList<IntRange>,
    ) {
        var sentenceStart = start
        var index = start
        while (index < end) {
            if (text[index] !in TERMINALS) {
                index++
                continue
            }
            val runStart = index
            while (index < end && text[index] in TERMINALS) index++
            val runEnd = index
            while (index < end && text[index] in CLOSERS) index++
            if (index < end && !text[index].isWhitespace()) continue
            if (isNoEnd(text, sentenceStart, runStart, runEnd, index, end, abbreviations)) continue
            trimmed(text, sentenceStart, index)?.let { into += it }
            sentenceStart = index
        }
        trimmed(text, sentenceStart, end)?.let { into += it }
    }

    /** Whether the terminal run [runStart] to [runEnd], followed by whitespace at [after], ends no sentence. */
    private fun isNoEnd(
        text: String,
        sentenceStart: Int,
        runStart: Int,
        runEnd: Int,
        after: Int,
        end: Int,
        abbreviations: Set<String>,
    ): Boolean {
        if (after >= end) return false
        val run = text.substring(runStart, runEnd)
        var next = after
        while (next < end && text[next].isWhitespace()) next++
        val nextChar = if (next < end) text[next] else null
        if ('…' in run || run.startsWith("..")) return nextChar?.isLowerCase() == true
        if (run != "." || after != runEnd) return false
        var wordStart = runStart
        while (wordStart > sentenceStart && !text[wordStart - 1].isWhitespace()) wordStart--
        val word = text.substring(wordStart, runStart).trimStart(*OPENERS)
        return Abbreviations.isAbbreviation(
            word,
            nextChar,
            abbreviations,
            previous = previousWord(text, sentenceStart, wordStart),
            following = followingWord(text, next, end),
        )
    }

    /** The word before [wordStart] when it ends with a period, without it; null otherwise. */
    private fun previousWord(
        text: String,
        sentenceStart: Int,
        wordStart: Int,
    ): String? {
        var last = wordStart - 1
        while (last >= sentenceStart && text[last].isWhitespace()) last--
        if (last < sentenceStart || text[last] != '.') return null
        var first = last
        while (first > sentenceStart && !text[first - 1].isWhitespace()) first--
        return text.substring(first, last).trimStart(*OPENERS)
    }

    /** The word from [next] to the next whitespace, without a final period, closer or comma, or null at the end. */
    private fun followingWord(
        text: String,
        next: Int,
        end: Int,
    ): String? {
        if (next >= end) return null
        var last = next
        while (last < end && !text[last].isWhitespace()) last++
        return text.substring(next, last).trimEnd(*FOLLOWING_ENDINGS)
    }

    /** The end of a clause mark at [index] (`,` `;` `:` before a space, `—`, or ` – `), or null. */
    private fun clauseMarkEnd(
        text: String,
        index: Int,
    ): Int? =
        when (text[index]) {
            ',', ';', ':' -> (index + 1).takeIf { text.getOrNull(it) == ' ' }
            '—' -> index + 1
            '–' -> (index + 1).takeIf { text.getOrNull(index - 1) == ' ' && text.getOrNull(it) == ' ' }
            else -> null
        }

    private fun trimmed(
        text: String,
        start: Int,
        end: Int,
    ): IntRange? {
        var first = start
        var last = end - 1
        while (first <= last && text[first].isWhitespace()) first++
        while (last >= first && text[last].isWhitespace()) last--
        return if (first <= last) first..last else null
    }

    private const val CLAUSE_MIN_CHARS = 20
    private const val TERMINALS = ".!?…"
    private const val CLOSERS = "\"”’')]"
    private val OPENERS = charArrayOf('"', '“', '‘', '\'', '(', '[')
    private val FOLLOWING_ENDINGS = charArrayOf('.', ',', ';', ':', '"', '”', '’', '\'', ')', ']')
}
