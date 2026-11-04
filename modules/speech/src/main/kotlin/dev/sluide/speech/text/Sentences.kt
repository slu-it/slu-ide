package dev.sluide.speech.text

import dev.sluide.speech.engine.SpeechChunk

/** Splits a [SpokenText] into sentences, the chunks a reading synthesizes one by one. */
object Sentences {
    /**
     * The chunks of [spoken], one per sentence, trimmed, in order, covering every spoken non-whitespace char once.
     * A sentence ends after `.`, `!`, `?` or `…` (runs included) and any closing quotes or brackets, when whitespace
     * or the end follows, and at every hard break (`"\n"`, which belongs to no chunk). Abbreviations, single
     * initials (not `I`) and an ellipsis before a lowercase word end none.
     */
    fun chunks(spoken: SpokenText): List<SpeechChunk> {
        val text = spoken.text
        val chunks = mutableListOf<SpeechChunk>()
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            for (sentence in sentencesIn(text, start, end)) {
                val sentenceEnd = sentence.last + 1
                chunks +=
                    SpeechChunk(sentence.first, sentenceEnd, sentence.first, sentenceEnd, text.substring(sentence))
            }
            start = end + 1
        }
        return chunks
    }

    /**
     * [chunk] split in two for fast first audio when it is longer than [maxChars]: after its first clause mark
     * (`,` `;` `:` before a space, `—`, or ` – `) from 20 chars on and before [maxChars], else at its last space before
     * [maxChars]. Both pieces keep the chunk's sentence range. A chunk within [maxChars], or one without a place to
     * split, comes back alone. The reading applies it to the chunk it starts at.
     */
    fun splitForFastStart(
        chunk: SpeechChunk,
        maxChars: Int = 120,
    ): List<SpeechChunk> {
        val text = chunk.text
        if (text.length <= maxChars) return listOf(chunk)
        val split =
            (CLAUSE_MIN_CHARS until maxChars).firstNotNullOfOrNull { clauseMarkEnd(text, it) }
                ?: (maxChars - 1 downTo 1).firstOrNull { text[it] == ' ' }
                ?: return listOf(chunk)
        val head = trimmed(text, 0, split) ?: return listOf(chunk)
        val tail = trimmed(text, split, text.length) ?: return listOf(chunk)
        return listOf(head, tail).map {
            SpeechChunk(
                chunk.start + it.first,
                chunk.start + it.last + 1,
                chunk.sentenceStart,
                chunk.sentenceEnd,
                text.substring(it),
            )
        }
    }

    /** The trimmed, non-empty sentences of [text] from [start] to [end], which holds no hard break. */
    private fun sentencesIn(
        text: String,
        start: Int,
        end: Int,
    ): List<IntRange> {
        val sentences = mutableListOf<IntRange>()
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
            if (isNoEnd(text, sentenceStart, runStart, runEnd, index, end)) continue
            trimmed(text, sentenceStart, index)?.let { sentences += it }
            sentenceStart = index
        }
        trimmed(text, sentenceStart, end)?.let { sentences += it }
        return sentences
    }

    /** Whether the terminal run [runStart] to [runEnd], followed by whitespace at [after], ends no sentence. */
    private fun isNoEnd(
        text: String,
        sentenceStart: Int,
        runStart: Int,
        runEnd: Int,
        after: Int,
        end: Int,
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
        return when {
            word.length == 1 && word[0].isUpperCase() -> word != "I"
            word in NUMBERED -> nextChar?.isDigit() == true
            else -> word.lowercase() in ABBREVIATIONS
        }
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
    private val NUMBERED = setOf("No", "no", "Fig", "fig")
    private val ABBREVIATIONS =
        setOf("mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "e.g", "i.e", "approx", "cf")
}
