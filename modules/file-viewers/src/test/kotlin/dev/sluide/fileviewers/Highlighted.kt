package dev.sluide.fileviewers

import kotlin.test.assertTrue

/** A [text] together with its highlighting, for asserting which parts get which style. */
internal class Highlighted(
    val text: String,
    highlighter: SyntaxHighlighter,
) {
    val spans = highlighter.highlight(text)

    /** Styles of all spans overlapping the [occurrence]th occurrence (0-based) of [snippet]. */
    fun stylesOf(
        snippet: String,
        occurrence: Int = 0,
    ): Set<TokenStyle> {
        var start = -1
        repeat(occurrence + 1) { start = text.indexOf(snippet, start + 1) }
        check(start >= 0) { "not in text: $snippet (#$occurrence)" }
        val end = start + snippet.length
        return spans.filter { it.start < end && it.end > start }.map { it.style }.toSet()
    }

    /** Spans are sorted, do not overlap and stay within the text. */
    fun assertWellFormed() {
        assertTrue(spans.isNotEmpty())
        assertTrue(spans.all { it.start in 0 until it.end && it.end <= text.length }, "spans within text")
        assertTrue(spans.zipWithNext().all { (a, b) -> a.end <= b.start }, "spans sorted and disjoint")
    }
}
