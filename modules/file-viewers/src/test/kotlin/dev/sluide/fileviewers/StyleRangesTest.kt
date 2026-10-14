package dev.sluide.fileviewers

import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
