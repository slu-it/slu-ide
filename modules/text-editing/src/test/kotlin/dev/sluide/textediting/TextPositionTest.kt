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

    @Test
    fun `turns an offset into a position, the inverse of offsetIn`() {
        val text = "ab\ncde\n\nf"
        val positions = (0..text.length).map { TextPosition.of(text, it) }
        assertEquals(
            listOf(0 to 0, 0 to 1, 0 to 2, 1 to 0, 1 to 1, 1 to 2, 1 to 3, 2 to 0, 3 to 0, 3 to 1),
            positions.map { it.line to it.column },
        )
        positions.forEachIndexed { offset, position -> assertEquals(offset, position.offsetIn(text), "$position") }
    }

    @Test
    fun `an offset beyond the text is its end, a surrogate pair counts as two columns`() {
        assertEquals(TextPosition(1, 1), TextPosition.of("ab\nc", 99))
        assertEquals(TextPosition(0, 0), TextPosition.of("ab\nc", -3))
        assertEquals(TextPosition(0, 0), TextPosition.of("", 0))
        assertEquals(TextPosition(1, 0), TextPosition.of("ab\n", 3), "after a final line break: the empty last line")
        // "😀" is one code point but two UTF-16 units, as LSP's default position encoding counts.
        assertEquals(TextPosition(1, 3), TextPosition.of("x\n😀y", 5))
        assertEquals(TextPosition(1, 4), TextPosition.of(StringBuilder("x\n😀yz"), 6), "any CharSequence")
    }
}
