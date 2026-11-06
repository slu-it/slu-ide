package dev.sluide.games.sudoku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SudokuSizeTest {
    @Test
    fun `units are rows, columns and boxes of side cells`() {
        SudokuSize.entries.forEach { size ->
            assertEquals(size.side * 3, size.units.size)
            assertEquals(true, size.units.all { it.size == size.side })
            assertEquals(size.cells.take(size.side), size.units[0])
            assertEquals(size.cells.filter { it.col == 0 }, size.units[size.side])
        }
    }

    @Test
    fun `peers of a cell`() {
        assertEquals(20, SudokuSize.NINE.peers(Cell(4, 4)).size)
        // 6×6: 5 in the row, 5 in the column, box 2×3 adds 2 more not in row or column
        assertEquals(12, SudokuSize.SIX.peers(Cell(0, 0)).size)
        assertEquals(7, SudokuSize.FOUR.peers(Cell(1, 1)).size)
        assertFalse(Cell(0, 0) in SudokuSize.NINE.peers(Cell(0, 0)))
    }

    @Test
    fun `box index with rectangular boxes`() {
        val six = SudokuSize.SIX
        assertEquals(0, six.box(Cell(0, 0)))
        assertEquals(1, six.box(Cell(1, 5)))
        assertEquals(4, six.box(Cell(4, 2)))
        assertEquals(5, six.box(Cell(5, 5)))
        assertEquals(
            listOf(Cell(2, 3), Cell(2, 4), Cell(2, 5), Cell(3, 3), Cell(3, 4), Cell(3, 5)),
            six.units[2 * 6 + 3],
        )
    }

    @Test
    fun `key lookup`() {
        SudokuSize.entries.forEach { assertEquals(it, SudokuSize.ofKey(it.key)) }
        assertEquals(listOf("4", "6", "9"), SudokuSize.entries.map { it.key })
        assertNull(SudokuSize.ofKey("5"))
        assertNull(SudokuSize.ofKey(null))
        assertEquals(SudokuSize.NINE, SudokuSize.DEFAULT)
    }

    @Test
    fun `level lookup`() {
        Level.entries.forEach { assertEquals(it, Level.ofKey(it.key)) }
        assertNull(Level.ofKey("insane"))
        assertEquals(Level.MEDIUM, Level.DEFAULT)
    }
}
