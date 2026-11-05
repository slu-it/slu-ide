package dev.sluide.speech.text

import dev.sluide.textediting.Abbreviations as SharedAbbreviations

/** English abbreviations and initials; used by the Kokoro tokenizer. Sentence splitting lives in `text-editing`. */
internal object Abbreviations {
    private val INITIALS = Regex("(\\p{L}\\.)+\\p{L}")

    /** Whether [word] followed by `.` is an English abbreviation, see [SharedAbbreviations.isAbbreviation]. */
    fun isAbbreviation(
        word: String,
        nextChar: Char?,
    ): Boolean = SharedAbbreviations.isAbbreviation(word, nextChar)

    /**
     * Whether [word] is several initials joined by periods (`U.S`, `U.K`), without the final period. Only the Kokoro
     * tokenizer uses it; sentence splitting still ends a sentence after "U.S.".
     */
    fun isInitials(word: String): Boolean = INITIALS.matches(word)
}
