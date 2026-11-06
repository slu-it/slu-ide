package dev.sluide.games.sudoku

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A clock that advances one "millisecond" per call: the generator reads it once at the start and once after every
 * attempt, so a budget of N allows N attempts (budget 1 = exactly one).
 */
internal fun countingClock(): () -> Long {
    var calls = 0L
    return { calls++ }
}

class GeneratorTest {
    private fun targetTier(level: Level) = Tier.entries[level.ordinal]

    private fun givensTarget(
        size: SudokuSize,
        level: Level,
    ) = when (size) {
        SudokuSize.FOUR -> listOf(8, 6, 5, 4)[level.ordinal]
        SudokuSize.SIX -> listOf(18, 15, 12, 10)[level.ordinal]
        SudokuSize.NINE -> error("graded by tier")
    }

    private fun reachedTarget(puzzle: Puzzle) =
        if (puzzle.size == SudokuSize.NINE) {
            puzzle.tier == targetTier(puzzle.level)
        } else {
            puzzle.givens.filledCount <= givensTarget(puzzle.size, puzzle.level)
        }

    private fun check(
        puzzle: Puzzle,
        size: SudokuSize,
        level: Level,
    ) {
        val label = "$size $level"
        assertEquals(size, puzzle.size)
        assertEquals(level, puzzle.level)
        assertEquals(1, Solver.countSolutions(puzzle.givens), label)
        assertTrue(puzzle.solution.isFull, label)
        assertFalse(puzzle.solution.hasConflict(), label)
        size.cells.forEach { c ->
            val g = puzzle.givens[c]
            assertTrue(g == 0 || g == puzzle.solution[c], "$label givens subset of solution at $c")
        }
        assertEquals(puzzle.solution, Solver.solve(puzzle.givens), label)
        val grade = assertNotNull(Grader.grade(puzzle.givens), label)
        assertEquals(grade, puzzle.tier, label)
        if (size == SudokuSize.NINE) {
            assertTrue(grade <= targetTier(level), label)
            val last = size.side * size.side - 1
            size.cells.forEachIndexed { i, c ->
                assertEquals(puzzle.givens[c] == 0, puzzle.givens[size.cells[last - i]] == 0, "$label symmetry at $c")
            }
        }
    }

    @Test
    fun `every size and level reaches its target with enough attempts`() {
        // the counting clock allows up to 1000 attempts (seed 101 needs at most 24), so the result is deterministic
        for (size in SudokuSize.entries) {
            for (level in Level.entries) {
                val puzzle = Generator(Random(101), 1000, countingClock()).generate(size, level)
                check(puzzle, size, level)
                assertTrue(
                    reachedTarget(puzzle),
                    "$size $level: tier ${puzzle.tier}, givens ${puzzle.givens.filledCount}",
                )
            }
        }
    }

    @Test
    fun `easy 9x9 puzzles have 37 or 38 givens and need only singles`() {
        repeat(3) { seed ->
            val puzzle = Generator(Random(seed), 10, countingClock()).generate(SudokuSize.NINE, Level.EASY)
            assertEquals(Tier.T1, puzzle.tier)
            assertTrue(puzzle.givens.filledCount in 37..38, "givens ${puzzle.givens.filledCount}")
        }
    }

    @Test
    fun `the same seed gives the same puzzle`() {
        val a = Generator(Random(5), 20, countingClock()).generate(SudokuSize.SIX, Level.MEDIUM)
        val b = Generator(Random(5), 20, countingClock()).generate(SudokuSize.SIX, Level.MEDIUM)
        assertEquals(a, b)
    }

    @Test
    fun `a spent budget returns the first attempt without starting another`() {
        // seed 3 needs more than one attempt for Expert: its first attempt is not T4
        var calls = 0L
        val puzzle = Generator(Random(3), 1, { calls++ }).generate(SudokuSize.NINE, Level.EXPERT)
        check(puzzle, SudokuSize.NINE, Level.EXPERT)
        assertTrue(puzzle.tier < Tier.T4, "tier ${puzzle.tier}")
        assertEquals(2L, calls, "start and one check after the first attempt")
    }

    /** What the generator should return after at most [attempts] attempts, rebuilt from single attempts. */
    private fun expectedBest(
        size: SudokuSize,
        level: Level,
        seed: Int,
        attempts: Int,
    ): Puzzle {
        val random = Random(seed)
        var best: Puzzle? = null
        repeat(attempts) {
            val puzzle = Generator(random, 1, countingClock()).generate(size, level)
            if (reachedTarget(puzzle)) return puzzle
            val current = best
            val better =
                current == null ||
                    if (size == SudokuSize.NINE) {
                        puzzle.tier > current.tier ||
                            (puzzle.tier == current.tier && puzzle.givens.filledCount < current.givens.filledCount)
                    } else {
                        puzzle.givens.filledCount < current.givens.filledCount
                    }
            if (better) best = puzzle
        }
        return checkNotNull(best)
    }

    @Test
    fun `small grids retry until the givens target within the attempts`() {
        for (seed in listOf(20, 54, 71, 1, 2, 3)) {
            val s = seed * 17 + Level.EXPERT.ordinal
            val actual = Generator(Random(s), 3, countingClock()).generate(SudokuSize.FOUR, Level.EXPERT)
            assertEquals(expectedBest(SudokuSize.FOUR, Level.EXPERT, s, 3), actual, "4×4 seed $seed")
        }
    }

    @Test
    fun `an unspent target keeps the best attempt, highest tier then fewest givens on 9x9`() {
        for (seed in 0 until 6) {
            val actual = Generator(Random(seed), 3, countingClock()).generate(SudokuSize.NINE, Level.HARD)
            assertEquals(expectedBest(SudokuSize.NINE, Level.HARD, seed, 3), actual, "9×9 seed $seed")
        }
    }

    @Test
    fun `expert 9x9 finishes within the default budget plus two seconds`() {
        val start = System.nanoTime()
        val puzzle = Generator(Random(11)).generate(SudokuSize.NINE, Level.EXPERT)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        check(puzzle, SudokuSize.NINE, Level.EXPERT)
        assertTrue(elapsedMillis < 5000, "took $elapsedMillis ms")
    }
}
