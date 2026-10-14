package dev.sluide.textediting

/**
 * Replacing the characters `[start, end)` of a text with [replacement]. [between] finds the smallest one that turns
 * one text into another, so an editor can apply a new version of its text without replacing all of it (and losing
 * caret, scroll position and undo history).
 */
data class TextReplacement(
    val start: Int,
    val end: Int,
    val replacement: String,
) {
    /** Whether this changes nothing. */
    val isEmpty: Boolean get() = start == end && replacement.isEmpty()

    /** [text] with this replacement applied. */
    fun applyTo(text: String): String = text.substring(0, start) + replacement + text.substring(end)

    companion object {
        /**
         * The replacement that turns [old] into [new]: everything between their common prefix and common suffix. It
         * never splits a surrogate pair. Empty if the texts are equal.
         */
        fun between(
            old: String,
            new: String,
        ): TextReplacement {
            val shorter = minOf(old.length, new.length)
            var prefix = 0
            while (prefix < shorter && old[prefix] == new[prefix]) prefix++
            if (prefix in 1..<shorter && old[prefix - 1].isHighSurrogate()) prefix--
            var suffix = 0
            while (suffix < shorter - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            if (suffix in 1..<shorter - prefix && old[old.length - suffix].isLowSurrogate()) suffix--
            return TextReplacement(prefix, old.length - suffix, new.substring(prefix, new.length - suffix))
        }
    }
}
