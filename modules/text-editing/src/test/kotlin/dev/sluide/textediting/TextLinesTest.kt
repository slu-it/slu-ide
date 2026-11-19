package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextLinesTest {
    @Test
    fun `a splice edit spans from the first to the end of the last change only`() {
        val edit = spliceEdit("abc\ndef\nghi", listOf(Splice(4, 0, "  "), Splice(8, 1, "")), Selection(0))

        assertEquals(TextEdit(4, 9, "  def\n", Selection(0)), edit)
    }

    @Test
    fun `a splice edit without a change is null`() {
        assertNull(spliceEdit("abc", listOf(Splice(1, 0, "")), Selection(0)))
        assertNull(spliceEdit("abc", emptyList(), Selection(0)))
    }

    @Test
    fun `an offset inside removed text moves to the end of the inserted text, shifted by earlier splices`() {
        // "  a\n  b": the first splice removes 2 characters, the second replaces the 2 at 4 by "XY".
        val edit = spliceEdit("  a\n  b", listOf(Splice(0, 2, ""), Splice(4, 2, "XY")), Selection(5))

        assertEquals(TextEdit(0, 6, "a\nXY", Selection(4)), edit)
    }

    @Test
    fun `offsets before, at the start of and after a splice move as documented`() {
        val splices = listOf(Splice(2, 2, "X"), Splice(6, 0, "YYY"))

        assertEquals(Selection(1, 2), spliceEdit("abcdefghij", splices, Selection(1, 2))?.selection)
        assertEquals(Selection(3, 5), spliceEdit("abcdefghij", splices, Selection(4, 6))?.selection)
        assertEquals(Selection(5, 10), spliceEdit("abcdefghij", splices, Selection(6, 8))?.selection)
    }

    @Test
    fun `a selection ending at the start of a line does not touch that line`() {
        assertEquals(LineBlock(0, 3), touchedLines("abc\ndef", Selection(0, 4)))
        assertEquals(LineBlock(0, 7), touchedLines("abc\ndef", Selection(0, 5)))
        assertEquals(LineBlock(4, 7), touchedLines("abc\ndef", Selection(4)))
        assertEquals(LineBlock(0, 3), touchedLines("abc\ndef", Selection(0)))
    }
}
