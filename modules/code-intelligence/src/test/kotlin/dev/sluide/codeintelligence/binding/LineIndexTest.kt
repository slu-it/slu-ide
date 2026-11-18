package dev.sluide.codeintelligence.binding

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The line index: offsets of positions clamped as the binding clamps them, and the line of an offset. */
class LineIndexTest {
    private val lines = LineIndex("ab\n\ncde")

    @Test
    fun `positions clamp to their line and the text`() {
        assertEquals(3, lines.lineCount)
        assertEquals(1, lines.offsetOf(0, 1))
        assertEquals(2, lines.offsetOf(0, 9), "past its line: the line's end")
        assertEquals(3, lines.offsetOf(1, 5))
        assertEquals(6, lines.offsetOf(2, 2))
        assertEquals(7, lines.offsetOf(5, 0), "past the last line: the end")
        assertEquals(0, lines.offsetOf(-1, 3))
    }

    @Test
    fun `an offset is on the line it starts or follows, a line break on its line`() {
        assertEquals(listOf(0, 0, 0, 1, 2, 2, 2, 2), (0..7).map(lines::lineOf))
        assertEquals(2, lines.lineOf(99))
    }

    @Test
    fun `the line one past the last is the end of the text`() {
        assertEquals(7, lines.offsetOf(3, 0))
        assertEquals(7, lines.offsetOf(3, 4))
    }

    @Test
    fun `an offset before the text is on the first line`() {
        assertEquals(0, lines.lineOf(-1))
        assertEquals(0, lines.lineOf(-100))
    }

    @Test
    fun `a text of many lines keeps every line start`() {
        val many = LineIndex((0 until 200).joinToString("\n") { "l$it" })

        assertEquals(200, many.lineCount)
        // "l0\n" to "l9\n" are 3 characters, "l10\n" to "l99\n" 4, "l100\n" and on 5.
        assertEquals(3 * 10 + 4 * 90, many.offsetOf(100, 0))
        assertEquals(3 * 10 + 4 * 90 + 5 * 99, many.offsetOf(199, 0))
        assertEquals(199, many.lineOf(3 * 10 + 4 * 90 + 5 * 99 + 1))
    }

    @Test
    fun `characters are UTF-16 code units, and a carriage return belongs to its line`() {
        // An emoji is a surrogate pair: 2 units, as LSP counts them. The CRLF line ends before the \n only.
        val text = LineIndex("a\uD83D\uDE00b\r\nxy")

        assertEquals(3, text.offsetOf(0, 3), "after the pair")
        assertEquals(2, text.offsetOf(0, 2), "between the pair's units: no clamping to code points")
        assertEquals(5, text.offsetOf(0, 99), "the line ends at the \\n, so after the \\r")
        assertEquals(6, text.offsetOf(1, 0))
        assertEquals(1, text.lineOf(6))
        assertEquals(0, text.lineOf(5))
    }
}
