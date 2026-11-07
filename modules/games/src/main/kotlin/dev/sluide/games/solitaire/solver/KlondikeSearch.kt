package dev.sluide.games.solitaire.solver

import dev.sluide.games.solitaire.KlondikeRules
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.Table

/**
 * Depth-first search of Klondike (see [KlondikeMoves], [KlondikeSettle]) with a table of the 64-bit hashes of the
 * positions seen. Two passes: the first with heuristic pruning finds most solutions fast; only when it is exhausted
 * without one, a second pass without the heuristics runs, and only its exhaustion yields
 * [SolveResult.Unsolvable] (up to the odds of a 64-bit hash collision, about n^2/2^65 for n positions).
 *
 * A found line is replayed through [KlondikeRules] before it is returned, so the hash can only lose solutions, never
 * produce a wrong [SolveResult.Solved].
 */
internal class KlondikeSearch(
    private val mode: Mode,
) : VariantSolver {
    private val rules = KlondikeRules(mode)
    private val draw3 = mode == Mode.KLONDIKE_DRAW_3

    override fun solve(
        table: Table,
        budget: SearchBudget,
    ): SolveResult {
        if (table.mode != mode) return SolveResult.Unknown
        if (rules.isWon(table)) return SolveResult.Solved(emptyList())
        val root = KlondikeState.of(table) ?: return SolveResult.Unknown
        val first = run(root, budget, sound = false)
        if (first is Outcome.Aborted) return SolveResult.Unknown
        if (first is Outcome.Found) replay(table, first.path)?.let { return SolveResult.Solved(it) }
        val second = run(root, budget, sound = true)
        return when (second) {
            is Outcome.Found -> replay(table, second.path)?.let { SolveResult.Solved(it) } ?: SolveResult.Unknown
            Outcome.Exhausted -> SolveResult.Unsolvable
            Outcome.Aborted -> SolveResult.Unknown
        }
    }

    private sealed interface Outcome {
        class Found(
            val path: Path?,
        ) : Outcome

        data object Exhausted : Outcome

        data object Aborted : Outcome
    }

    private fun run(
        start: KlondikeState,
        budget: SearchBudget,
        sound: Boolean,
    ): Outcome {
        val settler = KlondikeSettle(draw3, autoWaste = !draw3 || !sound)
        val visited = LongSet()
        val moves = KlondikeMoves(draw3, sound, settler, visited)
        val root = start.copy()
        settler.settle(root)
        visited.add(settler.hash(root))
        return dfs(root, budget, moves, visited)
    }

    private fun dfs(
        s: KlondikeState,
        budget: SearchBudget,
        moves: KlondikeMoves,
        visited: LongSet,
    ): Outcome {
        if (s.isWon()) return Outcome.Found(s.path)
        if (budget.tick()) return Outcome.Aborted
        if (visited.full) {
            budget.stop()
            return Outcome.Aborted
        }
        for (child in moves.expand(s)) {
            val result = dfs(child.state, budget, moves, visited)
            if (result !is Outcome.Exhausted) return result
        }
        return Outcome.Exhausted
    }

    /** The real moves of [path] from [table]; null when the rules refuse a step or the end is not won. */
    private fun replay(
        table: Table,
        path: Path?,
    ): List<Move>? {
        val ops = generateSequence(path) { it.parent }.map { it.op }.toList().asReversed()
        val moves = ArrayList<Move>()
        var current = table

        fun play(move: Move): Boolean {
            if (!rules.canMove(current, move)) return false
            moves += move
            current = rules.apply(current, move).table
            return true
        }

        fun foundationOf(suit: Int): PileId? =
            KlondikeRules.FOUNDATIONS.firstOrNull {
                current
                    .pile(it)
                    .top
                    ?.suit
                    ?.ordinal == suit
            }
                ?: KlondikeRules.FOUNDATIONS.firstOrNull { current.pile(it).cards.isEmpty() }

        for (op in ops) {
            val ok =
                when (op.kind) {
                    Op.COL_COL -> {
                        play(Move.Transfer(KlondikeRules.COLUMNS[op.a], KlondikeRules.COLUMNS[op.b], op.count))
                    }

                    Op.COL_FOUND -> {
                        val suit =
                            current
                                .pile(KlondikeRules.COLUMNS[op.a])
                                .top
                                ?.suit
                                ?.ordinal
                        val target = suit?.let(::foundationOf)
                        target != null && play(Move.Transfer(KlondikeRules.COLUMNS[op.a], target))
                    }

                    Op.WASTE_COL -> {
                        stockSteps(op, ::play) && play(Move.Transfer(KlondikeRules.WASTE, KlondikeRules.COLUMNS[op.b]))
                    }

                    Op.WASTE_FOUND -> {
                        val ready = stockSteps(op, ::play)
                        val suit =
                            current
                                .pile(KlondikeRules.WASTE)
                                .top
                                ?.suit
                                ?.ordinal
                        val target = suit?.let(::foundationOf)
                        ready && target != null && play(Move.Transfer(KlondikeRules.WASTE, target))
                    }

                    Op.FOUND_COL -> {
                        val from = foundationOf(op.a)
                        from != null && play(Move.Transfer(from, KlondikeRules.COLUMNS[op.b]))
                    }

                    else -> {
                        false
                    }
                }
            if (!ok) return null
        }
        return if (rules.isWon(current)) moves else null
    }

    private fun stockSteps(
        op: Op,
        play: (Move) -> Boolean,
    ): Boolean = op.stock.all { play(if (it == Op.DRAW) Move.Draw else Move.Recycle) }
}
