package dev.sluide.speech.text

import dev.sluide.speech.engine.SpeechChunk

/** Splits a [SpokenText] into sentences, the chunks a reading synthesizes one by one. */
object Sentences {
    /**
     * The chunks of [spoken], one per sentence, trimmed, in order, covering every spoken non-whitespace char once.
     * A sentence ends after `.`, `!`, `?` or `…` (runs included) and any closing quotes or brackets, when whitespace
     * or the end follows, and at every hard break (`"\n"`, which belongs to no chunk). Abbreviations, single
     * initials (not `I`) and an ellipsis before a lowercase word end none. If the first sentence is longer than
     * [firstChunkMaxChars], it is split in two for fast first audio, at its first clause mark from 20 chars on, else
     * at its last space before [firstChunkMaxChars]; both chunks keep the sentence's range.
     */
    fun chunks(
        spoken: SpokenText,
        firstChunkMaxChars: Int = 120,
    ): List<SpeechChunk> {
        val text = spoken.text
        val sentences = mutableListOf<IntRange>()
        var start = 0
        for (end in text.indices.filter { text[it] == '\n' } + text.length) {
            sentences += sentencesIn(text, start, end)
            start = end + 1
        }
        val chunks = sentences.map { SpeechChunk(it.first, it.last + 1, it.first, it.last + 1, text.substring(it)) }
        val first = chunks.firstOrNull() ?: return chunks
        return splitFirst(text, first, firstChunkMaxChars) + chunks.drop(1)
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
        val nextWord = text.substring(after, end).trimStart()
        if ('…' in run || run.startsWith("..")) return nextWord.firstOrNull()?.isLowerCase() == true
        if (run != "." || after != runEnd) return false
        var wordStart = runStart
        while (wordStart > sentenceStart && !text[wordStart - 1].isWhitespace()) wordStart--
        val word = text.substring(wordStart, runStart).trimStart(*OPENERS)
        return when {
            word.length == 1 && word[0].isUpperCase() -> word != "I"
            word in NUMBERED -> nextWord.firstOrNull()?.isDigit() == true
            else -> word.lowercase() in ABBREVIATIONS
        }
    }

    /** [first] split in two when longer than [maxChars], both keeping its sentence range; else just [first]. */
    private fun splitFirst(
        text: String,
        first: SpeechChunk,
        maxChars: Int,
    ): List<SpeechChunk> {
        if (first.end - first.start <= maxChars) return listOf(first)
        val split =
            (first.start + CLAUSE_MIN_CHARS until first.start + maxChars).firstNotNullOfOrNull {
                clauseMarkEnd(
                    text,
                    it,
                )
            }
                ?: (first.start + maxChars - 1 downTo first.start + 1).firstOrNull { text[it] == ' ' }
                ?: return listOf(first)
        val head = trimmed(text, first.start, split) ?: return listOf(first)
        val tail = trimmed(text, split, first.end) ?: return listOf(first)
        return listOf(head, tail).map {
            SpeechChunk(it.first, it.last + 1, first.sentenceStart, first.sentenceEnd, text.substring(it))
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
