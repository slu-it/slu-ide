package dev.sluide.games.sudoku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardLayoutTest {
    @Test
    fun `a wide area puts the pad beside the board`() {
        val layout = boardLayout(900.0, 500.0, SudokuSize.NINE)

        assertTrue(layout.padBeside)
        assertEquals(55.0, layout.cell)
    }

    @Test
    fun `a tall area puts the pad below the board`() {
        val layout = boardLayout(400.0, 900.0, SudokuSize.NINE)

        assertFalse(layout.padBeside)
        assertEquals(44.0, layout.cell)
    }

    @Test
    fun `a square area picks the arrangement with the larger cell`() {
        val layout = boardLayout(600.0, 600.0, SudokuSize.NINE)

        assertTrue(layout.padBeside)
        assertEquals(48.0, layout.cell)
    }

    @Test
    fun `the cell is clamped to the limits`() {
        assertEquals(MAX_CELL, boardLayout(3000.0, 3000.0, SudokuSize.FOUR).cell)
        assertEquals(MIN_CELL, boardLayout(100.0, 100.0, SudokuSize.NINE).cell)
    }

    @Test
    fun `a small grid gets bigger cells than a large one in the same area`() {
        assertTrue(boardLayout(600.0, 500.0, SudokuSize.FOUR).cell > boardLayout(600.0, 500.0, SudokuSize.NINE).cell)
    }

    @Test
    fun `the pad has the digit rows plus one row`() {
        assertEquals(3, padRows(SudokuSize.FOUR))
        assertEquals(3, padRows(SudokuSize.SIX))
        assertEquals(4, padRows(SudokuSize.NINE))
    }
}
