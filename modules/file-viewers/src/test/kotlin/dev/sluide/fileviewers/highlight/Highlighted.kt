package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TokenStyle
import kotlin.test.assertEquals
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

    private fun Char.isWordPart() = isLetterOrDigit() || this == '_'

    /**
     * Where the [occurrence]th (0-based) [snippet] starts; with [wholeWord] only those that are no part of a longer
     * name (`with` is not in `startswith`).
     */
    fun find(
        snippet: String,
        occurrence: Int = 0,
        wholeWord: Boolean = false,
    ): Int {
        var start = -1
        var found = -1
        while (found < occurrence) {
            start = text.indexOf(snippet, start + 1)
            check(start >= 0) { "not in text: $snippet (#$occurrence)" }
            val end = start + snippet.length
            val inName =
                wholeWord &&
                    (
                        (snippet.first().isWordPart() && start > 0 && text[start - 1].isWordPart()) ||
                            (snippet.last().isWordPart() && end < text.length && text[end].isWordPart())
                    )
            if (!inName) found++
        }
        return start
    }

    /** The span [start] until `start + length` is exactly one span of [style]; [message] tells which one. */
    fun assertExactlyAt(
        style: TokenStyle,
        start: Int,
        length: Int,
        message: String,
    ) {
        val overlapping = spans.filter { it.start < start + length && it.end > start }
        assertEquals(listOf(HighlightSpan(start, start + length, style)), overlapping, message)
    }

    /** No span overlaps [start] until `start + length`; [message] tells which one. */
    fun assertPlainAt(
        start: Int,
        length: Int,
        message: String,
    ) {
        val overlapping = spans.filter { it.start < start + length && it.end > start }
        assertEquals(emptyList(), overlapping, message)
    }

    /** The [occurrence]th [snippet] is exactly one span of [style], from its first to its last character. */
    fun assertExactly(
        style: TokenStyle,
        snippet: String,
        occurrence: Int = 0,
        wholeWord: Boolean = false,
    ) = assertExactlyAt(style, find(snippet, occurrence, wholeWord), snippet.length, "$snippet (#$occurrence)")

    /** The [occurrence]th [snippet] has no style. */
    fun assertPlain(
        snippet: String,
        occurrence: Int = 0,
        wholeWord: Boolean = false,
    ) = assertPlainAt(find(snippet, occurrence, wholeWord), snippet.length, "$snippet (#$occurrence)")
}
