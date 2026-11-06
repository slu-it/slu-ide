package dev.sluide.games.sudoku

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val WIKI_PUZZLE = "53..7....6..195....98....6.8...6...34..8.3..17...2...6.6....28....419..5....8..79"
internal const val WIKI_SOLUTION = "534678912672195348198342567859761423426853791713924856961537284287419635345286179"

internal fun nine(line: String): Grid = Grid.of(SudokuSize.NINE, line.chunked(9))

class SolverTest {
    @Test
    fun `solves a known puzzle`() {
        val expected = nine(WIKI_SOLUTION)
        assertTrue(expected.isFull)
        assertFalse(expected.hasConflict())
        assertEquals(expected, Solver.solve(nine(WIKI_PUZZLE)))
        assertEquals(1, Solver.countSolutions(nine(WIKI_PUZZLE)))
    }

    @Test
    fun `an empty grid has many solutions and counting stops at the limit`() {
        val empty = Grid.empty(SudokuSize.NINE)
        assertEquals(2, Solver.countSolutions(empty))
        assertEquals(5, Solver.countSolutions(empty, 5))
        assertEquals(0, Solver.countSolutions(empty, 0))
    }

    @Test
    fun `a conflicting or unsolvable grid has no solution`() {
        val conflict = Grid.of(SudokuSize.FOUR, listOf("11..", "....", "....", "...."))
        assertEquals(0, Solver.countSolutions(conflict))
        assertNull(Solver.solve(conflict))
        // (0,3) has no candidate left: row 1,2,3 and column... 4 is in its column
        val stuck = Grid.of(SudokuSize.FOUR, listOf("123.", "....", "...4", "...."))
        assertEquals(0, Solver.countSolutions(stuck))
    }

    @Test
    fun `a full grid has exactly one solution, itself`() {
        val full = nine(WIKI_SOLUTION)
        assertEquals(full, Solver.solve(full))
        assertEquals(1, Solver.countSolutions(full))
    }

    @Test
    fun `random full grids are complete and conflict free for every size`() {
        val random = Random(42)
        SudokuSize.entries.forEach { size ->
            repeat(3) {
                val grid = Solver.randomFull(size, random)
                assertEquals(size, grid.size)
                assertTrue(grid.isFull, "$size full")
                assertFalse(grid.hasConflict(), "$size conflict")
            }
        }
    }

    @Test
    fun `random full grids depend on the seed`() {
        val a = Solver.randomFull(SudokuSize.NINE, Random(1))
        assertEquals(a, Solver.randomFull(SudokuSize.NINE, Random(1)))
        assertNotNull(Solver.randomFull(SudokuSize.NINE, Random(2)).takeIf { it != a })
    }
}
