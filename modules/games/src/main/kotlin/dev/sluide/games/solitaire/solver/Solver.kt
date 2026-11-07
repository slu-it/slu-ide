package dev.sluide.games.solitaire.solver

import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.Table
import dev.sluide.games.solitaire.Variant
import kotlin.time.Duration

/** The outcome of [Solver.solve]. */
internal sealed interface SolveResult {
    /** A line of real moves that [dev.sluide.games.solitaire.Rules.apply] replays from the given table to a won one. */
    data class Solved(
        val moves: List<Move>,
    ) : SolveResult

    /** Only when the search was exhaustive and every pruning rule used was sound (see [KlondikeSearch]). */
    data object Unsolvable : SolveResult

    /** The budget or the cancel hit, or the position or the pruning does not allow a proof. */
    data object Unknown : SolveResult
}

/**
 * The per-variant part of the solver: searches one table within a [SearchBudget]. Klondike is [KlondikeSearch];
 * FreeCell and Spider plug in here later.
 */
internal fun interface VariantSolver {
    fun solve(
        table: Table,
        budget: SearchBudget,
    ): SolveResult
}

/**
 * Proves deals winnable: a "thoughtful" search that sees the face-down cards and the stock. Holds no state between
 * calls, so it is safe to call from several threads at once.
 */
internal open class Solver(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** Polls [cancelled] and the clock every few hundred nodes. */
    open fun solve(
        table: Table,
        budget: Duration,
        cancelled: () -> Boolean = { false },
    ): SolveResult {
        val variantSolver = variantSolver(table.mode) ?: return SolveResult.Unknown
        val searchBudget = SearchBudget(nanoTime, nanoTime() + budget.inWholeNanoseconds, cancelled)
        return try {
            variantSolver.solve(table, searchBudget)
        } catch (_: StackOverflowError) {
            SolveResult.Unknown
        }
    }

    private fun variantSolver(mode: Mode): VariantSolver? =
        when (mode.variant) {
            Variant.KLONDIKE -> KlondikeSearch(mode)
            Variant.FREECELL, Variant.SPIDER -> null
        }
}

/** The time limit and the cancel flag of one search, polled by [tick]. */
internal class SearchBudget(
    private val nanoTime: () -> Long,
    private val deadline: Long,
    private val cancelled: () -> Boolean,
) {
    private var nodes = 0

    var stopped = false
        private set

    /** Counts a node; every [POLL_EVERY] nodes (and at the first) checks the clock and the cancel flag. */
    fun tick(): Boolean {
        if (!stopped && nodes++ % POLL_EVERY == 0 && (cancelled() || nanoTime() - deadline >= 0)) stopped = true
        return stopped
    }

    /** Marks the search as given up for another reason (memory). */
    fun stop() {
        stopped = true
    }

    private companion object {
        const val POLL_EVERY = 256
    }
}
