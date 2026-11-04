package dev.sluide.speech.text

/** Words whose final period does not end a sentence; shared by [Sentences] and the Kokoro tokenizer. */
internal object Abbreviations {
    private val NUMBERED = setOf("No", "no", "Fig", "fig")
    private val WORDS =
        setOf("mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "e.g", "i.e", "approx", "cf")
    private val INITIALS = Regex("(\\p{L}\\.)+\\p{L}")

    /**
     * Whether [word] (without its period) followed by `.` is an abbreviation: a known one, `No`/`Fig` before a number
     * ([nextChar] is the next character after the whitespace, or null), or a single capital initial other than `I`.
     */
    fun isAbbreviation(
        word: String,
        nextChar: Char?,
    ): Boolean =
        when {
            word.length == 1 && word[0].isUpperCase() -> word != "I"
            word in NUMBERED -> nextChar?.isDigit() == true
            else -> word.lowercase() in WORDS
        }

    /** Whether [word] is several initials joined by periods (`U.S`, `U.K`), without the final period. */
    fun isInitials(word: String): Boolean = INITIALS.matches(word)
}
