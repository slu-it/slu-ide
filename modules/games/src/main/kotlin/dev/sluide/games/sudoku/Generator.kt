package dev.sluide.games.sudoku

import kotlin.random.Random

data class Puzzle(val size: SudokuSize, val level: Level, val givens: Grid, val solution: Grid, val tier: Tier)

/**
 * Generates puzzles with exactly one solution that never need guessing. 9×9 puzzles aim at the tier of the level with
 * 180° symmetric givens; 4×4 and 6×6 puzzles aim at a number of givens. The first attempt always completes, further
 * attempts start while less than [budgetMillis] have passed; then the best attempt is returned.
 */
class Generator(
    private val random: Random = Random.Default,
    private val budgetMillis: Long = 3000,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Blocking; never call it on the FX thread. */
    fun generate(
        size: SudokuSize,
        level: Level,
    ): Puzzle {
        val start = nowMillis()
        var best: Puzzle? = null
        while (true) {
            val puzzle = if (size == SudokuSize.NINE) attemptNine(level) else attemptSmall(size, level)
            if (isTarget(puzzle)) return puzzle
            if (best == null || isBetter(puzzle, best)) best = puzzle
            if (nowMillis() - start >= budgetMillis) return best
        }
    }

    private fun isTarget(puzzle: Puzzle): Boolean =
        if (puzzle.size == SudokuSize.NINE) {
            puzzle.tier == targetTier(puzzle.level)
        } else {
            puzzle.givens.filledCount <= targetGivens(puzzle.size, puzzle.level)
        }

    private fun isBetter(
        a: Puzzle,
        b: Puzzle,
    ): Boolean =
        if (a.size == SudokuSize.NINE) {
            a.tier > b.tier || (a.tier == b.tier && a.givens.filledCount < b.givens.filledCount)
        } else {
            a.givens.filledCount < b.givens.filledCount
        }

    private fun attemptNine(level: Level): Puzzle {
        val size = SudokuSize.NINE
        val target = targetTier(level)
        val solution = Solver.randomFull(size, random)
        var grid = solution
        val total = size.side * size.side
        val order = (0..(total - 1) / 2).shuffled(random)
        for (i in order) {
            if (level == Level.EASY && grid.filledCount <= EASY_MAX_GIVENS) break
            val j = total - 1 - i
            var candidate = grid.with(size.cells[i], 0)
            if (j != i) candidate = candidate.with(size.cells[j], 0)
            if (Solver.countSolutions(candidate) != 1) continue
            val tier = Grader.grade(candidate)
            if (tier != null && tier <= target) grid = candidate
        }
        val tier = checkNotNull(Grader.grade(grid))
        return Puzzle(size, level, grid, solution, tier)
    }

    private fun attemptSmall(
        size: SudokuSize,
        level: Level,
    ): Puzzle {
        val target = targetGivens(size, level)
        val solution = Solver.randomFull(size, random)
        var grid = solution
        for (cell in size.cells.shuffled(random)) {
            if (grid.filledCount <= target) break
            val candidate = grid.with(cell, 0)
            if (Solver.countSolutions(candidate) == 1 && Grader.grade(candidate) != null) grid = candidate
        }
        val tier = checkNotNull(Grader.grade(grid))
        return Puzzle(size, level, grid, solution, tier)
    }

    private fun targetTier(level: Level): Tier =
        when (level) {
            Level.EASY -> Tier.T1
            Level.MEDIUM -> Tier.T2
            Level.HARD -> Tier.T3
            Level.EXPERT -> Tier.T4
        }

    private fun targetGivens(
        size: SudokuSize,
        level: Level,
    ): Int =
        when (size) {
            SudokuSize.FOUR -> listOf(8, 6, 5, 4)[level.ordinal]
            SudokuSize.SIX -> listOf(18, 15, 12, 10)[level.ordinal]
            SudokuSize.NINE -> error("9×9 is graded by tier")
        }

    private companion object {
        const val EASY_MAX_GIVENS = 38
    }
}
