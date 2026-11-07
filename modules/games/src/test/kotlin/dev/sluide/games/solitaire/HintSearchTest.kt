package dev.sluide.games.solitaire

import dev.sluide.games.solitaire.solver.SolveResult
import dev.sluide.games.solitaire.ui.emptyTable
import dev.sluide.games.solitaire.ui.pileOf
import dev.sluide.games.solitaire.ui.tableau
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class HintSearchTest {
    private val time = TestTimeSource()
    private val budgets = mutableListOf<Duration>()

    /** Tables told apart by the size of column 0. */
    private fun position(cards: Int) = emptyTable(Mode.FREECELL, mapOf(tableau(0) to pileOf(cards)))

    private fun search(
        current: Table,
        earlier: List<Table>,
        result: (Table) -> SolveResult,
    ) = HintSearch.search(
        current,
        earlier,
        2.seconds,
        { table, budget, _ ->
            budgets += budget
            time += budget
            result(table)
        },
        { false },
        time,
    )

    @Test
    fun `earlier positions are solved newest first, each within a quarter of the budget, until one is winnable`() {
        val earlier = (1..5).map(::position)

        val solved = mapOf(earlier[1] to SolveResult.Solved(emptyList()))

        val outcome = search(position(6), earlier) { solved[it] ?: SolveResult.Unsolvable }

        assertEquals(HintOutcome.UndoBack(4), outcome)
        assertEquals(listOf(2.seconds, 500.milliseconds, 500.milliseconds, 500.milliseconds, 500.milliseconds), budgets)
    }

    @Test
    fun `the earlier positions get twice the budget in all`() {
        val outcome = search(position(30), (1..29).map(::position)) { SolveResult.Unsolvable }

        assertEquals(HintOutcome.None, outcome)
        assertEquals(1 + 8, budgets.size)
    }

    @Test
    fun `a failing solver finds nothing`() {
        assertEquals(HintOutcome.None, search(position(1), emptyList()) { throw StackOverflowError() })
    }
}
