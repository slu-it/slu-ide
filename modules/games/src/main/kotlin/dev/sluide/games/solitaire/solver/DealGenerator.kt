package dev.sluide.games.solitaire.solver

import dev.sluide.games.solitaire.Deals
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.Rules
import dev.sluide.games.solitaire.Table
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A deal of [number] in [mode] with a line [solution] that wins it. */
internal data class Deal(
    val mode: Mode,
    val number: Int,
    val table: Table,
    val solution: List<Move>,
)

/** Finds numbered deals the [solver] proves winnable within [perDeal] each. */
internal class DealGenerator(
    private val solver: Solver = Solver(),
    private val perDeal: Duration = DEFAULT_PER_DEAL,
) {
    /** The deal of [number] if the solver proves it winnable within [perDeal], else null. */
    fun dealIfWinnable(
        mode: Mode,
        number: Int,
        cancelled: () -> Boolean = { false },
    ): Deal? {
        val table = Rules.of(mode).deal(Deals.deck(mode, number))
        val result = solver.solve(table, perDeal, cancelled)
        return (result as? SolveResult.Solved)?.let { Deal(mode, number, table, it.moves) }
    }

    /**
     * The first proven-winnable deal scanning upward from [from] (wrapping after [Deals.MAX_NUMBER] to 1); null only
     * when cancelled or after [maxTries] numbers.
     */
    fun firstWinnable(
        mode: Mode,
        from: Int,
        maxTries: Int = 200,
        cancelled: () -> Boolean = { false },
    ): Deal? {
        require(from in 1..Deals.MAX_NUMBER) { "deal number must be 1..${Deals.MAX_NUMBER}: $from" }
        var number = from
        repeat(maxTries) {
            if (cancelled()) return null
            dealIfWinnable(mode, number, cancelled)?.let { return it }
            number = if (number >= Deals.MAX_NUMBER) 1 else number + 1
        }
        return null
    }

    companion object {
        val DEFAULT_PER_DEAL: Duration = 250.milliseconds
    }
}
