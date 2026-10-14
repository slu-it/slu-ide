package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextPositionTest {
    @Test
    fun `parses a line and an optional column`() {
        assertEquals(TextPosition(4, 0), parseGoTo("5", 10))
        assertEquals(TextPosition(4, 2), parseGoTo("5:3", 10))
        assertEquals(TextPosition(4, 2), parseGoTo(" 5 : 3 ", 10))
    }

    @Test
    fun `clamps the line to the text`() {
        assertEquals(TextPosition(0, 0), parseGoTo("0", 10))
        assertEquals(TextPosition(9, 0), parseGoTo("12", 10))
        assertEquals(TextPosition(9, 0), parseGoTo("99999999999999", 10))
        assertEquals(TextPosition(0, 0), parseGoTo("3", 0))
        assertEquals(TextPosition(1, 0), parseGoTo("2:0", 3))
    }

    @Test
    fun `rejects blank and other input`() {
        assertNull(parseGoTo("", 10))
        assertNull(parseGoTo("  ", 10))
        assertNull(parseGoTo("abc", 10))
        assertNull(parseGoTo("1:", 10))
        assertNull(parseGoTo("-1", 10))
        assertNull(parseGoTo("1:2:3", 10))
    }

    @Test
    fun `turns a position into an offset clamped to its line`() {
        val text = "ab\ncde\n\nf"
        assertEquals(0, TextPosition(0, 0).offsetIn(text))
        assertEquals(5, TextPosition(1, 2).offsetIn(text))
        assertEquals(6, TextPosition(1, 9).offsetIn(text))
        assertEquals(7, TextPosition(2, 3).offsetIn(text))
        assertEquals(9, TextPosition(3, 5).offsetIn(text))
        assertEquals(9, TextPosition(7, 5).offsetIn(text))
        assertEquals(0, TextPosition(2, 2).offsetIn(""))
    }
}
