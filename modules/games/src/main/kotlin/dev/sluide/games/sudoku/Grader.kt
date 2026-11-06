package dev.sluide.games.sudoku

enum class Tier { T1, T2, T3, T4 }

data class Deduction(
    val cell: Cell,
    val digit: Int,
    val tier: Tier,
)

/** Grades puzzles by the human techniques they need: T1 singles, T2 locked candidates and pairs, T3 triples and X-Wing, T4 Swordfish and XY-Wing. */
object Grader {
    /** Highest tier needed to solve the grid fully; null when stuck, conflicting or contradictory. */
    fun grade(grid: Grid): Tier? {
        val state = Candidates.of(grid) ?: return null
        var top = Tier.T1
        while (true) {
            if (state.isContradiction()) return null
            if (state.isSolved) return top
            val step = state.step() ?: return null
            top = maxOf(top, step.tier)
        }
    }

    /** The first digit the grader places from this grid, with the highest tier used on the way; null when stuck. */
    fun nextDeduction(grid: Grid): Deduction? {
        val state = Candidates.of(grid) ?: return null
        var top = Tier.T1
        while (true) {
            if (state.isSolved || state.isContradiction()) return null
            val step = state.step() ?: return null
            top = maxOf(top, step.tier)
            step.placement?.let { return Deduction(state.size.cells[it.index], it.digit, top) }
        }
    }

    private class Step(
        val tier: Tier,
        val placement: Placement?,
    )

    /** Applies the easiest technique that makes progress (a placement or eliminations); null when stuck. */
    private fun Candidates.step(): Step? {
        (nakedSingle() ?: hiddenSingle())?.let {
            place(it.index, it.digit)
            return Step(Tier.T1, it)
        }
        if (pointing() || claiming() || nakedSubset(2) || hiddenSubset(2)) return Step(Tier.T2, null)
        if (nakedSubset(3) || hiddenSubset(3) || fish(2)) return Step(Tier.T3, null)
        if (fish(3) || xyWing()) return Step(Tier.T4, null)
        return null
    }
}
