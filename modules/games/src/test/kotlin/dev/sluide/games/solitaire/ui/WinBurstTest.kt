package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Card
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Pile
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Suit
import kotlin.test.Test
import kotlin.test.assertEquals

class WinBurstTest {
    private val klondike = Mode.KLONDIKE_DRAW_1

    @Test
    fun `the burst takes the top card of each foundation in turn until all have flown`() {
        val foundation = { i: Int -> PileId(PileKind.FOUNDATION, i) }
        val table =
            emptyTable(
                klondike,
                mapOf(
                    foundation(0) to Pile.allFaceUp(listOf(Card(1, Suit.CLUBS), Card(2, Suit.CLUBS))),
                    foundation(2) to Pile.allFaceUp(listOf(Card(1, Suit.HEARTS))),
                ),
            )
        val layout = layout(table, 900.0, 700.0)

        val cards = burstCards(table, layout)

        assertEquals(
            listOf(Card(2, Suit.CLUBS), Card(1, Suit.HEARTS), Card(1, Suit.CLUBS)),
            cards.map { it.card },
        )
        assertEquals(layout.slots.getValue(foundation(2)), cards[1].origin)
    }
}
