package dev.sluide.speech.text

import dev.sluide.speech.engine.SpeechChunk
import dev.sluide.textediting.SentenceRanges

/** Splits a [SpokenText] into sentences, the chunks a reading synthesizes one by one. */
object Sentences {
    /**
     * The chunks of [spoken], one per sentence, trimmed, in order, covering every spoken non-whitespace char once.
     * The splitting is [SentenceRanges.split]: a sentence ends after `.`, `!`, `?` or `…` (runs included) and any
     * closing quotes or brackets, when whitespace or the end follows, and at every hard break (`"\n"`, which belongs
     * to no chunk). Abbreviations, single initials (not `I`) and an ellipsis before a lowercase word end none.
     */
    fun chunks(spoken: SpokenText): List<SpeechChunk> {
        val text = spoken.text
        return SentenceRanges.split(text).map {
            SpeechChunk(it.first, it.last + 1, it.first, it.last + 1, text.substring(it))
        }
    }

    /**
     * [chunk] split in two for fast first audio when it is longer than [maxChars] ([SentenceRanges.splitAtClause]):
     * after its first clause mark from 20 chars on and before [maxChars], else at its last space before [maxChars].
     * Both pieces keep the chunk's sentence range. A chunk within [maxChars], or one without a place to split, comes
     * back alone. The reading applies it to the chunk it starts at.
     */
    fun splitForFastStart(
        chunk: SpeechChunk,
        maxChars: Int = 120,
    ): List<SpeechChunk> {
        val text = chunk.text
        val pieces = SentenceRanges.splitAtClause(text, text.indices, maxChars)
        if (pieces.size < 2) return listOf(chunk)
        return pieces.map {
            SpeechChunk(
                chunk.start + it.first,
                chunk.start + it.last + 1,
                chunk.sentenceStart,
                chunk.sentenceEnd,
                text.substring(it),
            )
        }
    }
}
