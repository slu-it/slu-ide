package dev.sluide.games.solitaire

import dev.sluide.games.solitaire.solver.SolveResult
import kotlin.time.Duration
import kotlin.time.TimeSource

/** What a hint search found. */
internal sealed interface HintOutcome {
    /** The next move of a winning line from the current position. */
    data class Next(
        val move: Move,
    ) : HintOutcome

    /** No winning line from here, but there is one [steps] moves back. */
    data class UndoBack(
        val steps: Int,
    ) : HintOutcome

    /** Neither within the budget. */
    data object None : HintOutcome
}

/** The hint search of [SolitaireModel]. */
internal object HintSearch {
    /**
     * Solves [table] within [budget]. When it is proven unsolvable, solves the [earlier] positions (oldest first, as
     * [SolitaireGame.positions] without the current one) newest first, each within a quarter of [budget] and all of
     * them within twice [budget] as [time] measures it. Blocking; meant for a background thread. A failing [solve]
     * counts as nothing found.
     */
    fun search(
        table: Table,
        earlier: List<Table>,
        budget: Duration,
        solve: (Table, Duration, () -> Boolean) -> SolveResult,
        cancelled: () -> Boolean,
        time: TimeSource = TimeSource.Monotonic,
    ): HintOutcome =
        try {
            when (val result = solve(table, budget, cancelled)) {
                is SolveResult.Solved -> result.moves.firstOrNull()?.let { HintOutcome.Next(it) } ?: HintOutcome.None
                SolveResult.Unsolvable -> searchBack(earlier, budget, solve, cancelled, time)
                SolveResult.Unknown -> HintOutcome.None
            }
        } catch (e: Throwable) {
            // Errors too (a deep recursion, say): the model must hear back to end "Thinking…".
            System
                .getLogger(HintSearch::class.java.name)
                .log(System.Logger.Level.WARNING, "Solitaire hint search failed", e)
            HintOutcome.None
        }

    private fun searchBack(
        earlier: List<Table>,
        budget: Duration,
        solve: (Table, Duration, () -> Boolean) -> SolveResult,
        cancelled: () -> Boolean,
        time: TimeSource,
    ): HintOutcome {
        val start = time.markNow()
        val total = budget * 2
        for (steps in 1..earlier.size) {
            val left = total - start.elapsedNow()
            if (cancelled() || !left.isPositive()) break
            if (solve(earlier[earlier.size - steps], minOf(budget / 4, left), cancelled) is SolveResult.Solved) {
                return HintOutcome.UndoBack(steps)
            }
        }
        return HintOutcome.None
    }
}
