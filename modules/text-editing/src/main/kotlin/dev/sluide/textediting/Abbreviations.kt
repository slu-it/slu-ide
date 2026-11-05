package dev.sluide.textediting

/**
 * Words whose final period does not end a sentence, per language. Entries are lower case without the final period; a
 * multi-part one keeps the inner periods and single spaces (`"z. b"` for "z. B."), see [isAbbreviation].
 */
object Abbreviations {
    val ENGLISH: Set<String> =
        setOf("mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "vs", "etc", "e.g", "i.e", "approx", "cf")

    val GERMAN: Set<String> =
        setOf(
            "z. b",
            "z.b",
            "d. h",
            "d.h",
            "u. a",
            "u.a",
            "bzw",
            "ca",
            "usw",
            "vgl",
            "nr",
            "dr",
            "prof",
            "hr",
            "fr",
            "s",
            "abs",
            "ggf",
            "inkl",
            "evtl",
            "bspw",
            "etc",
        )

    private val NUMBERED = setOf("No", "no", "Fig", "fig")

    /**
     * Whether [word] (without its period) followed by `.` is an abbreviation: one of [abbreviations], `No`/`Fig` before
     * a number ([nextChar] is the next character after the whitespace, or null), or a single capital initial other
     * than `I`. A multi-part entry matches when [previous] (the word before [word], without its period) and [word], or
     * [word] and [following] (the word after the period, without its own), joined by a space, are in
     * [abbreviations]; that is how "z." and "B." of "z. B." both end no sentence.
     */
    fun isAbbreviation(
        word: String,
        nextChar: Char?,
        abbreviations: Set<String> = ENGLISH,
        previous: String? = null,
        following: String? = null,
    ): Boolean {
        val lower = word.lowercase()
        return when {
            word.length == 1 && word[0].isUpperCase() -> word != "I"
            word in NUMBERED -> nextChar?.isDigit() == true
            lower in abbreviations -> true
            previous != null && "${previous.lowercase()}. $lower" in abbreviations -> true
            following != null && "$lower. ${following.lowercase()}" in abbreviations -> true
            else -> false
        }
    }
}
