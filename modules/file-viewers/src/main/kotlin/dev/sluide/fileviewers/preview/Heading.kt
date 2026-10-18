package dev.sluide.fileviewers.preview

/** A heading of a document: its [level] (1 to 6), plain [text] and 0-based source [line]. */
internal data class Heading(
    val level: Int,
    val text: String,
    val line: Int,
)

/** Derived from the [Heading]s of a document, for its table of contents. */
internal object HeadingOutline {
    /** The indentation of each of [headings]: the rank of its level among the levels in use, so `##`, `####` is 0, 1. */
    fun depths(headings: List<Heading>): List<Int> {
        val levels = headings.map { it.level }.distinct().sorted()
        return headings.map { levels.indexOf(it.level) }
    }

    /** The index of the heading whose section contains [line]: the last one at or above it, `null` before the first. */
    fun sectionAt(
        headings: List<Heading>,
        line: Int,
    ): Int? = headings.indexOfLast { it.line <= line }.takeIf { it >= 0 }
}
