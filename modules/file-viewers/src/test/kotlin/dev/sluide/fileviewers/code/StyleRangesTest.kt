package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.StyledRange
import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class StyleRangesTest {
    /** Spans from pairs of style and length. */
    private fun spans(vararg spans: Pair<String, Int>): StyleSpans<String> =
        StyleSpansBuilder<String>().apply { spans.forEach { (style, length) -> add(style, length) } }.create()

    @Test
    fun `equal styles have no changed range`() {
        assertNull(changedSpanRange(spans("a" to 3, "b" to 2), spans("a" to 3, "b" to 2)))
    }

    @Test
    fun `spans split differently with the same styles have no changed range`() {
        assertNull(changedSpanRange(spans("a" to 3, "b" to 2), spans("a" to 1, "a" to 2, "b" to 1, "b" to 1)))
        assertNull(changedSpanRange(spans("a" to 0, "a" to 3), spans("a" to 3, "a" to 0)))
    }

    @Test
    fun `finds the range from the first to the last changed character`() {
        assertEquals(2..4, changedSpanRange(spans("" to 2, "kw" to 3, "" to 5), spans("" to 10)))
        assertEquals(2..7, changedSpanRange(spans("" to 2, "kw" to 3, "" to 5), spans("" to 5, "str" to 3, "" to 2)))
    }

    @Test
    fun `finds changes at the very start and end`() {
        assertEquals(0..0, changedSpanRange(spans("kw" to 1, "" to 4), spans("" to 5)))
        assertEquals(4..4, changedSpanRange(spans("" to 5), spans("" to 4, "kw" to 1)))
        assertEquals(0..4, changedSpanRange(spans("kw" to 5), spans("str" to 5)))
    }

    /** The styles of [spans] character by character. */
    private fun styles(spans: StyleSpans<Collection<String>>): List<Collection<String>> =
        spans.flatMap { span -> List(span.length) { span.style } }

    private fun tokens(vararg spans: Pair<List<String>, Int>): StyleSpans<Collection<String>> =
        StyleSpansBuilder<Collection<String>>()
            .apply {
                spans.forEach { (style, length) ->
                    add(style, length)
                }
            }.create()

    @Test
    fun `no ranges leave the spans as they are`() {
        val spans = tokens(listOf("kw") to 3)
        assertSame(spans, overlayRanges(spans, emptyList()) { listOf("x") })
    }

    @Test
    fun `the style of each range is added to the token styles under it`() {
        val spans = tokens(listOf("kw") to 3, emptyList<String>() to 5, listOf("str") to 2)
        val styled = overlayRanges(spans, listOf(1..3, 8..9)) { index -> listOf("range$index") }
        assertEquals(10, styled.length())
        assertEquals(
            listOf(
                listOf("kw"),
                listOf("kw", "range0"),
                listOf("kw", "range0"),
                listOf("range0"),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                listOf("str", "range1"),
                listOf("str", "range1"),
            ),
            styles(styled).map { it.toList() },
        )
    }

    @Test
    fun `a range at the start and one touching the end are styled`() {
        val spans = tokens(emptyList<String>() to 4)
        val styled = overlayRanges(spans, listOf(0..0, 1..3)) { index -> listOf(if (index == 0) "a" else "b") }
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("b"), listOf("b")), styles(styled).map { it.toList() })
    }

    @Test
    fun `styled ranges go over highlighting and find matches, overlapping ones add both, clamped to the spans`() {
        // "def f" highlighted, with a find match on "f f".
        val spans = tokens(listOf("keyword") to 2, listOf("keyword", "search-match") to 1, listOf("search-match") to 2)
        val ranges =
            listOf(StyledRange(1, 4, "sentence"), StyledRange(3, 99, "word"), StyledRange(-3, 1, "start"))

        val styled = withStyledRanges(spans, ranges)

        assertEquals(
            listOf(
                listOf("keyword", "start"),
                listOf("keyword", "sentence"),
                listOf("keyword", "search-match", "sentence"),
                listOf("search-match", "sentence", "word"),
                listOf("search-match", "word"),
            ),
            styles(styled).map { it.toList() },
        )
    }

    @Test
    fun `styled ranges replace the stripped classes, none clear them, offsets place them in spans of part of a text`() {
        val spans = tokens(listOf("keyword", "old") to 2, listOf("old") to 2)

        assertEquals(
            listOf(listOf("keyword"), listOf("keyword"), emptyList(), emptyList()),
            styles(withStyledRanges(spans, emptyList(), strip = setOf("old"))).map { it.toList() },
        )
        // The spans start at offset 10 of the text: the range 11..13 is their 1..3.
        assertEquals(
            listOf(listOf("keyword"), listOf("keyword", "new"), listOf("new"), emptyList()),
            styles(withStyledRanges(spans, listOf(StyledRange(11, 13, "new")), 10, setOf("old"))).map { it.toList() },
        )
    }
}
