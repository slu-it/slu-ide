package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Card
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.Pile
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Rules
import dev.sluide.games.solitaire.Suit
import dev.sluide.games.solitaire.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnimationPlannerTest {
    private val stock = PileId(PileKind.STOCK)
    private val waste = PileId(PileKind.WASTE)
    private val klondike = Mode.KLONDIKE_DRAW_1

    private fun plan(
        before: Table,
        move: Move,
        reverse: Boolean = false,
    ): AnimationPlan {
        val outcome = Rules.of(before.mode).apply(before, move)
        val (old, new) = if (reverse) outcome.table to before else before to outcome.table
        return planStep(
            move,
            outcome.flipped,
            outcome.completedRuns,
            old,
            layout(old, 900.0, 700.0),
            new,
            layout(new, 900.0, 700.0),
            reverse,
        )
    }

    private fun placedAt(
        table: Table,
        pile: PileId,
        index: Int,
    ) = layout(table, 900.0, 700.0).cards.first { it.pile == pile && it.index == index }

    private val fiveOnSix =
        emptyTable(
            klondike,
            mapOf(
                tableau(0) to Pile(listOf(Card(13, Suit.CLUBS), Card(5, Suit.SPADES)), 1),
                tableau(1) to Pile(listOf(Card(6, Suit.HEARTS)), 1),
            ),
        )

    @Test
    fun `a transfer slides the moved card from its old place and flips the card it uncovers`() {
        val plan = plan(fiveOnSix, Move.Transfer(tableau(0), tableau(1)))

        val slide = plan.slides.single()
        assertEquals(tableau(1), slide.pile)
        assertEquals(1, slide.index)
        assertEquals(placedAt(fiveOnSix, tableau(0), 1).rect, slide.from)
        assertEquals(listOf(FlipPlan(tableau(0), 0, true)), plan.flips)
    }

    @Test
    fun `undo slides the card back from the outcome and turns the card face down again`() {
        val plan = plan(fiveOnSix, Move.Transfer(tableau(0), tableau(1)), reverse = true)

        val slide = plan.slides.single()
        assertEquals(tableau(0), slide.pile)
        assertEquals(1, slide.index)
        assertEquals(
            placedAt(
                Rules.of(klondike).apply(fiveOnSix, Move.Transfer(tableau(0), tableau(1))).table,
                tableau(1),
                1,
            ).rect,
            slide.from,
        )
        assertEquals(listOf(FlipPlan(tableau(0), 0, false)), plan.flips)
    }

    @Test
    fun `a transfer of several cards slides each of them`() {
        val table =
            emptyTable(
                klondike,
                mapOf(
                    tableau(0) to Pile(listOf(Card(5, Suit.SPADES), Card(4, Suit.HEARTS)), 2),
                    tableau(1) to Pile(listOf(Card(6, Suit.HEARTS)), 1),
                ),
            )

        val plan = plan(table, Move.Transfer(tableau(0), tableau(1), 2))

        assertEquals(listOf(1, 2), plan.slides.map { it.index })
        assertEquals(listOf(tableau(1)), plan.slides.map { it.pile }.distinct())
        assertTrue(plan.flips.isEmpty())
    }

    @Test
    fun `a draw brings the new waste card from the stock`() {
        val table = emptyTable(klondike, mapOf(stock to Pile(listOf(Card(2, Suit.CLUBS), Card(3, Suit.CLUBS)))))

        val plan = plan(table, Move.Draw)

        val slide = plan.slides.single()
        assertEquals(waste, slide.pile)
        assertEquals(0, slide.index)
        assertEquals(layout(table, 900.0, 700.0).slots.getValue(stock), slide.from)
    }

    @Test
    fun `a recycle slides nothing`() {
        val table = emptyTable(klondike, mapOf(waste to Pile.allFaceUp(listOf(Card(2, Suit.CLUBS)))), fan = 1)

        assertTrue(plan(table, Move.Recycle).slides.isEmpty())
    }

    @Test
    fun `a Spider row deal brings each column's new card from the stock, staggered`() {
        val columns = (0 until 10).associate { tableau(it) to Pile(listOf(Card(13, Suit.SPADES)), 1) }
        val table =
            emptyTable(
                Mode.SPIDER_1,
                columns + (stock to Pile(List(10) { Card(it + 1, Suit.SPADES) })),
            )

        val plan = plan(table, Move.DealRow)

        assertEquals(10, plan.slides.size)
        assertEquals((0 until 10).map { it * STAGGER_MS }, plan.slides.map { it.delayMs })
        assertEquals(List(10) { tableau(it) }, plan.slides.map { it.pile })
        assertEquals(setOf(layout(table, 900.0, 700.0).slots.getValue(stock)), plan.slides.map { it.from }.toSet())
    }

    @Test
    fun `the King of a completed Spider run slides to the foundation from where the run was`() {
        val run = (13 downTo 2).map { Card(it, Suit.SPADES) }
        val table =
            emptyTable(
                Mode.SPIDER_1,
                mapOf(
                    tableau(0) to Pile(run, run.size),
                    tableau(1) to Pile(listOf(Card(1, Suit.SPADES)), 1),
                ),
            )

        val plan = plan(table, Move.Transfer(tableau(1), tableau(0)))

        val slide = plan.slides.single { it.pile.kind == PileKind.FOUNDATION }
        assertEquals(0, slide.index)
        assertEquals(placedAt(table, tableau(0), 0).rect, slide.from)
        assertEquals(1, plan.slides.size)
    }

    private val underRun =
        emptyTable(
            Mode.SPIDER_1,
            mapOf(
                tableau(0) to Pile(listOf(Card(9, Suit.HEARTS)) + (13 downTo 2).map { Card(it, Suit.SPADES) }, 12),
                tableau(1) to Pile(listOf(Card(1, Suit.SPADES)), 1),
            ),
        )
    private val completing = Move.Transfer(tableau(1), tableau(0))

    @Test
    fun `a completed run with a card under it slides the King only and turns the uncovered card`() {
        val plan = plan(underRun, completing)

        val slide = plan.slides.single()
        assertEquals(PileKind.FOUNDATION, slide.pile.kind)
        assertEquals(placedAt(underRun, tableau(0), 1).rect, slide.from)
        assertEquals(listOf(FlipPlan(tableau(0), 0, true)), plan.flips)
    }

    @Test
    fun `undoing a completed run brings the run back from the foundation and turns the card down`() {
        val plan = plan(underRun, completing, reverse = true)

        val foundation =
            Rules
                .of(Mode.SPIDER_1)
                .apply(underRun, completing)
                .table
                .ids(PileKind.FOUNDATION)
                .first()
        val from =
            layout(
                Rules.of(Mode.SPIDER_1).apply(underRun, completing).table,
                900.0,
                700.0,
            ).slots.getValue(foundation)
        assertEquals(listOf(tableau(1)) + List(12) { tableau(0) }, plan.slides.map { it.pile })
        assertEquals(setOf(from), plan.slides.map { it.from }.toSet())
        assertEquals(listOf(FlipPlan(tableau(0), 0, false)), plan.flips)
    }

    @Test
    fun `a card whose place changes without being moved slides too`() {
        val table =
            emptyTable(
                Mode.KLONDIKE_DRAW_3,
                mapOf(waste to Pile.allFaceUp(listOf(Card(2, Suit.CLUBS), Card(3, Suit.CLUBS), Card(4, Suit.CLUBS)))),
                fan = 3,
            )
        val after = table.with(waste to Pile.allFaceUp(table.pile(waste).cards.dropLast(1))).copy(fan = 1)
        val move = Move.Transfer(waste, PileId(PileKind.TABLEAU, 0), 1)

        val plan =
            planStep(
                move,
                emptyList(),
                emptyList(),
                table,
                layout(table, 900.0, 700.0),
                after,
                layout(after, 900.0, 700.0),
                false,
            )

        assertNotNull(plan.slides.firstOrNull { it.pile == waste && it.index == 1 })
    }

    @Test
    fun `the deal cascade starts every tableau card at the stock, round by round, ending after a second`() {
        val table =
            emptyTable(
                klondike,
                (0 until 7).associate { tableau(it) to Pile(List(it + 1) { k -> Card(k + 1, Suit.HEARTS) }, 1) } +
                    (stock to Pile(listOf(Card(9, Suit.CLUBS)))),
            )
        val layout = layout(table, 900.0, 700.0)

        val plan = planDeal(layout)

        assertEquals(28, plan.slides.size)
        assertEquals(setOf(layout.slots.getValue(stock)), plan.slides.map { it.from }.toSet())
        assertEquals(tableau(0), plan.slides.first().pile)
        assertEquals(listOf(0, 1, 1), plan.slides.subList(6, 9).map { it.index })
        assertEquals(DEAL_TOTAL_MS - DEAL_SLIDE_MS, plan.slides.last().delayMs, 1e-9)
        assertEquals(plan.slides.map { it.delayMs }.sorted(), plan.slides.map { it.delayMs })
        // only the top card of each column is face up
        assertEquals(7, plan.slides.count { it.flipAfter })
    }

    @Test
    fun `a deal without a stock starts at the top-left corner`() {
        val table = emptyTable(Mode.FREECELL, mapOf(tableau(0) to pileOf(2, 2)))
        val layout = layout(table, 900.0, 700.0)

        val plan = planDeal(layout)

        assertEquals(Rect(0.0, 0.0, layout.cardWidth, layout.cardHeight), plan.slides.first().from)
    }
}
