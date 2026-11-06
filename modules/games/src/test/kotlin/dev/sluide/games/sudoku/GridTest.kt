package dev.sluide.games.sudoku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GridTest {
    private val four = listOf("12..", "..34", "....", "...1")

    @Test
    fun `of and toString round trip`() {
        val grid = Grid.of(SudokuSize.FOUR, four)
        assertEquals(four.joinToString("\n"), grid.toString())
        assertEquals(grid, Grid.of(SudokuSize.FOUR, four.map { it.replace('.', '0') }))
        assertEquals(grid.hashCode(), Grid.of(SudokuSize.FOUR, four).hashCode())
        assertEquals(5, grid.filledCount)
    }

    @Test
    fun `with sets and clears a digit without changing the original`() {
        val grid = Grid.empty(SudokuSize.FOUR)
        val set = grid.with(Cell(2, 1), 3)
        assertEquals(3, set[Cell(2, 1)])
        assertEquals(0, grid[Cell(2, 1)])
        assertEquals(grid, set.with(Cell(2, 1), 0))
        assertNotEquals(grid, set)
        assertFalse(grid.isFull)
    }

    @Test
    fun `candidates exclude digits of peers`() {
        val grid = Grid.of(SudokuSize.FOUR, four)
        assertEquals(setOf(2, 3, 4), grid.candidates(Cell(2, 0)))
        assertEquals(setOf(2, 4), grid.candidates(Cell(2, 2)))
        assertEquals(emptySet(), grid.candidates(Cell(0, 2)))
        assertEquals(emptySet(), grid.candidates(Cell(0, 0)))
    }

    @Test
    fun `candidates of an empty cell`() {
        val grid = Grid.of(SudokuSize.FOUR, listOf("1...", "....", "....", "...."))
        assertEquals(setOf(2, 3, 4), grid.candidates(Cell(0, 3)))
        assertEquals(setOf(2, 3, 4), grid.candidates(Cell(1, 1)))
        assertEquals(setOf(1, 2, 3, 4), grid.candidates(Cell(3, 3)))
    }

    @Test
    fun `conflict detection`() {
        assertFalse(Grid.of(SudokuSize.FOUR, four).hasConflict())
        assertTrue(Grid.of(SudokuSize.FOUR, listOf("1.1.", "....", "....", "....")).hasConflict())
        assertTrue(Grid.of(SudokuSize.FOUR, listOf("1...", "1...", "....", "....")).hasConflict())
        assertTrue(Grid.of(SudokuSize.FOUR, listOf("1...", ".1..", "....", "....")).hasConflict())
    }

    @Test
    fun `cells outside the grid are refused`() {
        val grid = Grid.empty(SudokuSize.NINE)
        assertFailsWith<IllegalArgumentException> { grid[Cell(0, 9)] }
        assertFailsWith<IllegalArgumentException> { grid[Cell(-1, 0)] }
        assertFailsWith<IllegalArgumentException> { grid.with(Cell(9, 0), 1) }
        assertFailsWith<IllegalArgumentException> { grid.candidates(Cell(0, 9)) }
    }

    @Test
    fun `of rejects bad rows`() {
        assertFailsWith<IllegalArgumentException> { Grid.of(SudokuSize.FOUR, listOf("....", "....", "....")) }
        assertFailsWith<IllegalArgumentException> { Grid.of(SudokuSize.FOUR, listOf("...", "....", "....", "....")) }
        assertFailsWith<IllegalArgumentException> { Grid.of(SudokuSize.FOUR, listOf("5...", "....", "....", "....")) }
        assertFailsWith<IllegalArgumentException> { Grid.of(SudokuSize.FOUR, listOf("x...", "....", "....", "....")) }
    }
}
