package dev.sluide.games.sudoku

enum class Tier { T1, T2, T3, T4 }

data class Deduction(
    val cell: Cell,
    val digit: Int,
    val tier: Tier,
)

/**
 * Grades puzzles by the human techniques they need: T1 singles, T2 locked candidates and pairs, T3 triples and
 * X-Wing, T4 Swordfish and XY-Wing.
 */
object Grader {
    /** Highest tier needed to solve the grid fully; null when stuck, conflicting or contradictory. */
    fun grade(grid: Grid): Tier? = gradeTraced(grid, null)

    /**
     * [grade] reporting every step to [observer]: the technique name, the eliminated (cell, digit) pairs and the
     * placed (cell, digit), if any. For tests that check the techniques against a known solution.
     */
    internal fun gradeTraced(
        grid: Grid,
        observer: ((technique: String, eliminated: List<Pair<Cell, Int>>, placed: Pair<Cell, Int>?) -> Unit)?,
    ): Tier? {
        val state = Candidates.of(grid) ?: return null
        var top = Tier.T1
        while (true) {
            if (state.isContradiction()) return null
            if (state.isSolved) return top
            val log = if (observer != null) mutableListOf<Pair<Int, Int>>() else null
            state.removed = log
            val step = state.step() ?: return null
            top = maxOf(top, step.tier)
            if (observer != null) {
                val cells = grid.size.cells
                observer(
                    step.name,
                    log.orEmpty().map { cells[it.first] to it.second },
                    step.placement?.let { cells[it.index] to it.digit },
                )
            }
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
        val name: String,
        val placement: Placement?,
    )

    private class Technique(
        val name: String,
        val tier: Tier,
        val run: Candidates.() -> Boolean,
    )

    private val ladder =
        listOf(
            Technique("pointing", Tier.T2) { pointing() },
            Technique("claiming", Tier.T2) { claiming() },
            Technique("nakedPair", Tier.T2) { nakedSubset(2) },
            Technique("hiddenPair", Tier.T2) { hiddenSubset(2) },
            Technique("nakedTriple", Tier.T3) { nakedSubset(3) },
            Technique("hiddenTriple", Tier.T3) { hiddenSubset(3) },
            Technique("xWing", Tier.T3) { fish(2) },
            Technique("swordfish", Tier.T4) { fish(3) },
            Technique("xyWing", Tier.T4) { xyWing() },
        )

    /** Applies the easiest technique that makes progress (a placement or eliminations); null when stuck. */
    private fun Candidates.step(): Step? {
        nakedSingle()?.let {
            place(it.index, it.digit)
            return Step(Tier.T1, "nakedSingle", it)
        }
        hiddenSingle()?.let {
            place(it.index, it.digit)
            return Step(Tier.T1, "hiddenSingle", it)
        }
        for (technique in ladder) {
            if (technique.run(this)) return Step(technique.tier, technique.name, null)
        }
        return null
    }
}
