package dev.sluide.games.solitaire.solver

import dev.sluide.games.solitaire.Deals
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Rules
import dev.sluide.games.solitaire.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DealGeneratorTest {
    /** Answers from [answers] in turn (the last one for ever) and keeps the tables it was asked about. */
    private class FakeSolver(
        private val answers: List<SolveResult>,
    ) : Solver() {
        val asked = mutableListOf<Table>()

        override fun solve(
            table: Table,
            budget: Duration,
            cancelled: () -> Boolean,
        ): SolveResult {
            asked += table
            return answers[minOf(asked.size - 1, answers.lastIndex)]
        }
    }

    private val solved = SolveResult.Solved(emptyList())
    private val mode = Mode.KLONDIKE_DRAW_1

    private fun tableOf(number: Int) = Rules.of(mode).deal(Deals.deck(mode, number))

    @Test
    fun `the first number the solver proves is the deal and carries its line`() {
        val fake = FakeSolver(listOf(solved))

        val deal = DealGenerator(fake).firstWinnable(mode, 40)

        assertEquals(40, deal?.number)
        assertEquals(tableOf(40), deal?.table)
        assertEquals(emptyList(), deal?.solution)
    }

    @Test
    fun `numbers that are unsolvable or undecided are skipped in order`() {
        val fake = FakeSolver(listOf(SolveResult.Unknown, SolveResult.Unsolvable, solved))

        val deal = DealGenerator(fake).firstWinnable(mode, 40)

        assertEquals(42, deal?.number)
        assertEquals(listOf(tableOf(40), tableOf(41), tableOf(42)), fake.asked)
    }

    @Test
    fun `a start outside the numbers is refused`() {
        assertFailsWith<IllegalArgumentException> { DealGenerator(FakeSolver(listOf(solved))).firstWinnable(mode, 0) }
    }

    @Test
    fun `the scan wraps from the last number to 1`() {
        val fake = FakeSolver(listOf(SolveResult.Unknown, solved))

        val deal = DealGenerator(fake).firstWinnable(mode, Deals.MAX_NUMBER)

        assertEquals(1, deal?.number)
    }

    @Test
    fun `no deal after maxTries numbers`() {
        val fake = FakeSolver(listOf(SolveResult.Unknown))

        assertNull(DealGenerator(fake).firstWinnable(mode, 1, maxTries = 5))
        assertEquals(5, fake.asked.size)
    }

    @Test
    fun `a cancel stops the scan before the next number`() {
        val fake = FakeSolver(listOf(SolveResult.Unknown))
        var polls = 0

        val deal = DealGenerator(fake).firstWinnable(mode, 1, cancelled = { ++polls > 2 })

        assertNull(deal)
        assertEquals(2, fake.asked.size)
    }

    @Test
    fun `a typed number is a deal only when proven`() {
        val generator = DealGenerator(FakeSolver(listOf(SolveResult.Unsolvable, SolveResult.Unknown, solved)))

        assertNull(generator.dealIfWinnable(mode, 5))
        assertNull(generator.dealIfWinnable(mode, 5))
        assertEquals(5, generator.dealIfWinnable(mode, 5)?.number)
    }

    @Test
    fun `the real solver gives the same winnable deal for the same start`() {
        val generator = DealGenerator(perDeal = 10.seconds)

        val first = generator.firstWinnable(mode, 1)
        val again = generator.firstWinnable(mode, 1)

        assertNotNull(first)
        assertEquals(first, again)
        assertTrue(first.solution.isNotEmpty())
    }
}
