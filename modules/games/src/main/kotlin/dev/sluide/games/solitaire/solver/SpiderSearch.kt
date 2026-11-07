package dev.sluide.games.solitaire.solver

import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.SpiderRules
import dev.sluide.games.solitaire.Table

/**
 * Spider with 1, 2 or 4 suits: a best-first search ([BestFirst], [SpiderSpace]) that sees the face-down cards and
 * the stock. Pruning is sound only, so an exhausted queue means [SolveResult.Unsolvable]; for a real deal the search
 * practically always ends on the budget or the cap of positions as [SolveResult.Unknown]. A line is replayed through
 * [SpiderRules] before it is returned.
 */
internal class SpiderSearch(
    private val rules: SpiderRules,
) : VariantSolver {
    override fun solve(
        table: Table,
        budget: SearchBudget,
    ): SolveResult {
        if (table.mode != rules.mode) return SolveResult.Unknown
        val root = SpiderState.of(table) ?: return SolveResult.Unknown
        for ((index, step) in STEPS.withIndex()) {
            val line =
                if (step.nodes == 0) {
                    null
                } else {
                    SpiderBeam(SpiderSpace(root.stock), budget, step.nodes, step.width, BEAM_POSITIONS).run(root)
                }
            if (line != null) replay(table, line)?.let { return SolveResult.Solved(it) }
            if (budget.stopped) return SolveResult.Unknown
            if (index == 0 || index == STEPS.lastIndex) {
                val cap = if (index == 0) QUICK_POSITIONS else EXHAUSTIVE_POSITIONS
                when (val result = BestFirst(SpiderSpace(root.stock), budget, cap).run(root, IntArray(0))) {
                    is BestFirstResult.Found -> replay(table, result.ops)?.let { return SolveResult.Solved(it) }
                    BestFirstResult.Exhausted -> return SolveResult.Unsolvable
                    BestFirstResult.Aborted -> if (budget.stopped) return SolveResult.Unknown
                }
            }
        }
        return SolveResult.Unknown
    }

    private class Step(
        val nodes: Int,
        val width: Int,
    )

    private fun replay(
        table: Table,
        ops: IntArray,
    ): List<Move>? {
        val moves = ArrayList<Move>(ops.size)
        var current = table
        for (op in ops) {
            val move =
                if (SpOp.kind(op) == SpOp.DEAL) {
                    Move.DealRow
                } else {
                    Move.Transfer(SpiderRules.COLUMNS[SpOp.a(op)], SpiderRules.COLUMNS[SpOp.b(op)], SpOp.count(op))
                }
            if (!rules.canMove(current, move)) return null
            moves += move
            current = rules.apply(current, move).table
        }
        return if (rules.isWon(current)) moves else null
    }

    private companion object {
        const val BEAM_POSITIONS = 250_000
        const val QUICK_POSITIONS = 20_000
        const val EXHAUSTIVE_POSITIONS = 250_000

        /** Beams of growing size; a sound search follows the first and ends the list. */
        val STEPS = listOf(Step(2_000, 3), Step(8_000, 10), Step(30_000, 30), Step(100_000, 100), Step(0, 0))
    }
}
