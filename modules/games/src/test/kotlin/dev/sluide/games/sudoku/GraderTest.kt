package dev.sluide.games.sudoku

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GraderTest {
    private val arto = "8..........36......7..9.2...5...7.......457.....1...3...1....68..85...1..9....4.."

    private val conflict = Grid.of(SudokuSize.FOUR, listOf("11..", "....", "....", "...."))

    @Test
    fun `an easy puzzle needs only singles`() {
        assertEquals(Tier.T1, Grader.grade(nine(WIKI_PUZZLE)))
    }

    @Test
    fun `known puzzles grade by the tier of the hardest technique they need`() {
        // generated once, fixed here so a change to the ladder shows up
        val medium = ".7..9..6...6.25..3..2...9.......263.5.......7.396.......1...4..7..86.1...4..1..5."
        val hard = "..236...5.1.5...2...9..8.......4..3.7..1.9..4.6..8.......2..8...8...3.7.9...763.."
        val expert = "..74...8....5...9658.....1...87...3.3...9...1.6...48...9.....6867...5....4...15.."
        assertEquals(Tier.T2, Grader.grade(nine(medium)))
        assertEquals(Tier.T3, Grader.grade(nine(hard)))
        assertEquals(Tier.T4, Grader.grade(nine(expert)))
    }

    @Test
    fun `a full grid grades T1`() {
        assertEquals(Tier.T1, Grader.grade(nine(WIKI_SOLUTION)))
    }

    @Test
    fun `the hardest known puzzle is beyond the techniques`() {
        assertEquals(1, Solver.countSolutions(nine(arto)))
        assertNull(Grader.grade(nine(arto)))
    }

    @Test
    fun `a conflicting grid grades null`() {
        assertNull(Grader.grade(conflict))
        assertNull(Grader.nextDeduction(conflict))
    }

    @Test
    fun `a contradictory grid grades null`() {
        assertNull(Grader.grade(Grid.of(SudokuSize.FOUR, listOf("123.", "....", "...4", "...."))))
    }

    @Test
    fun `the next deduction is a correct digit of the solution`() {
        val d = assertNotNull(Grader.nextDeduction(nine(WIKI_PUZZLE)))
        assertEquals(Tier.T1, d.tier)
        assertEquals(nine(WIKI_SOLUTION)[d.cell], d.digit)
        assertEquals(0, nine(WIKI_PUZZLE)[d.cell])
    }

    @Test
    fun `no deduction for a full grid`() {
        assertNull(Grader.nextDeduction(nine(WIKI_SOLUTION)))
    }

    @Test
    fun `a medium puzzle reaches a deduction that needs a T2 elimination`() {
        val puzzle = Generator(Random(101), 1000, countingClock()).generate(SudokuSize.NINE, Level.MEDIUM)
        assertEquals(Tier.T2, firstNonSingleDeduction(puzzle).tier)
    }

    @Test
    fun `the deductions of harder puzzles are correct and need the tier of the puzzle`() {
        for ((level, seed) in listOf(Level.HARD to 101, Level.EXPERT to 100)) {
            val puzzle = Generator(Random(seed), 1000, countingClock()).generate(SudokuSize.NINE, level)
            assertEquals(Tier.entries[level.ordinal], puzzle.tier)
            // walking the deductions until the highest tier shows up also checks every digit on the way
            assertEquals(puzzle.tier, walk(puzzle).maxOf { it.tier })
        }
    }

    /** Follows the grader's deductions from the givens, checking each digit, until the grid is full. */
    private fun walk(puzzle: Puzzle): List<Deduction> {
        var grid = puzzle.givens
        val steps = mutableListOf<Deduction>()
        while (!grid.isFull) {
            val d = assertNotNull(Grader.nextDeduction(grid))
            assertEquals(puzzle.solution[d.cell], d.digit)
            assertEquals(0, grid[d.cell])
            steps.add(d)
            grid = grid.with(d.cell, d.digit)
        }
        return steps
    }

    private fun firstNonSingleDeduction(puzzle: Puzzle): Deduction =
        assertNotNull(walk(puzzle).firstOrNull { it.tier != Tier.T1 })
}
