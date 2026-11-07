package dev.sluide.games.solitaire.solver

import dev.sluide.games.solitaire.FreeCellRules
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.Table

/**
 * FreeCell: a best-first search ([BestFirst], [FreeCellSpace]) over positions that see the whole table. It prunes
 * soundly only, so when the queue runs dry the position is [SolveResult.Unsolvable] (up to a 64-bit hash collision);
 * the cap of positions, the budget and a cancel give [SolveResult.Unknown]. A line is replayed through
 * [FreeCellRules] before it is returned.
 */
internal class FreeCellSearch : VariantSolver {
    private val rules = FreeCellRules()

    override fun solve(
        table: Table,
        budget: SearchBudget,
    ): SolveResult {
        if (table.mode != Mode.FREECELL) return SolveResult.Unknown
        val start = FreeCellState.of(table) ?: return SolveResult.Unknown
        val space = FreeCellSpace()
        val root = start.copy()
        val rootOps = ArrayList<Int>()
        space.settle(root, rootOps)
        return when (val result = BestFirst(space, budget, MAX_POSITIONS).run(root, rootOps.toIntArray())) {
            is BestFirstResult.Found -> replay(table, result.ops)?.let { SolveResult.Solved(it) } ?: SolveResult.Unknown
            BestFirstResult.Exhausted -> SolveResult.Unsolvable
            BestFirstResult.Aborted -> SolveResult.Unknown
        }
    }

    /** The real moves of [ops] from [table]; null when the rules refuse a step or the end is not won. */
    private fun replay(
        table: Table,
        ops: IntArray,
    ): List<Move>? {
        val moves = ArrayList<Move>(ops.size)
        var current = table

        fun play(move: Move): Boolean {
            if (!rules.canMove(current, move)) return false
            moves += move
            current = rules.apply(current, move).table
            return true
        }

        fun cellOf(card: Int): PileId? =
            FreeCellRules.CELLS.firstOrNull { current.pile(it).top?.let { c -> cardByte(c).toInt() } == card }

        fun foundationOf(suit: Int): PileId? =
            FreeCellRules.FOUNDATIONS.firstOrNull {
                current
                    .pile(it)
                    .top
                    ?.suit
                    ?.ordinal == suit
            }
                ?: FreeCellRules.FOUNDATIONS.firstOrNull { current.pile(it).cards.isEmpty() }

        for (op in ops) {
            val a = FcOp.a(op)
            val b = FcOp.b(op)
            val ok =
                when (FcOp.kind(op)) {
                    FcOp.COL_COL -> {
                        play(Move.Transfer(FreeCellRules.COLUMNS[a], FreeCellRules.COLUMNS[b], FcOp.count(op)))
                    }

                    FcOp.COL_CELL -> {
                        val cell = FreeCellRules.CELLS.firstOrNull { current.pile(it).cards.isEmpty() }
                        cell != null && play(Move.Transfer(FreeCellRules.COLUMNS[a], cell))
                    }

                    FcOp.CELL_COL -> {
                        val cell = cellOf(a)
                        cell != null && play(Move.Transfer(cell, FreeCellRules.COLUMNS[b]))
                    }

                    FcOp.COL_FOUND -> {
                        val suit =
                            current
                                .pile(FreeCellRules.COLUMNS[a])
                                .top
                                ?.suit
                                ?.ordinal
                        val target = suit?.let(::foundationOf)
                        target != null && play(Move.Transfer(FreeCellRules.COLUMNS[a], target))
                    }

                    FcOp.CELL_FOUND -> {
                        val cell = cellOf(a)
                        val suit =
                            cell?.let {
                                current
                                    .pile(it)
                                    .top
                                    ?.suit
                                    ?.ordinal
                            }
                        val target = suit?.let(::foundationOf)
                        cell != null && target != null && play(Move.Transfer(cell, target))
                    }

                    else -> {
                        false
                    }
                }
            if (!ok) return null
        }
        return if (rules.isWon(current)) moves else null
    }

    private companion object {
        const val MAX_POSITIONS = 250_000
    }
}
