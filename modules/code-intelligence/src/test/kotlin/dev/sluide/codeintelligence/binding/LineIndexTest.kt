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
}
