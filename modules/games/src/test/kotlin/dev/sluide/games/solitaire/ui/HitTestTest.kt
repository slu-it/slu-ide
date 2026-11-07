package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HitTestTest {
    private val table =
        emptyTable(
            Mode.KLONDIKE_DRAW_1,
            mapOf(tableau(0) to pileOf(5, up = 2), tableau(1) to pileOf(1)),
        )
    private val result = layout(table, 1000.0, 700.0)

    private fun center(placed: Placed) = placed.rect.x + 5 to placed.rect.y + 5

    @Test
    fun `the topmost of overlapping cards is hit`() {
        val cards = result.cards.filter { it.pile == tableau(0) }
        val (x, y) = center(cards[3])
        // the point is also inside the card below, which is covered by the next one
        assertEquals(3, cardAt(result, x, y)?.index)
        assertEquals(4, cardAt(result, x, cards[4].rect.y + 1)?.index)
        assertNull(cardAt(result, 1.0, 1.0))
    }

    @Test
    fun `a point below a short column drops on that column`() {
        val slot = result.slots.getValue(tableau(1))
        assertEquals(tableau(1), pileAt(result, slot.x + 5, 690.0))
        assertEquals(tableau(0), pileAt(result, result.slots.getValue(tableau(0)).x + 5, 690.0))
    }

    @Test
    fun `a point between columns drops on the nearest one`() {
        val a = result.slots.getValue(tableau(2))
        val b = result.slots.getValue(tableau(3))
        val gapX = (a.x + a.w + b.x) / 2
        assertEquals(tableau(2), pileAt(result, gapX - 1, a.y + 20))
        assertEquals(tableau(3), pileAt(result, gapX + 1, a.y + 20))
    }

    @Test
    fun `the top row piles and empty space above the tableau`() {
        val stock = result.slots.getValue(PileId(PileKind.STOCK))
        assertEquals(PileId(PileKind.STOCK), pileAt(result, stock.x + 3, stock.y + 3))
        val gap = result.slots.getValue(PileId(PileKind.WASTE)).let { it.x + it.w + 1 }
        assertNull(pileAt(result, gap + 10, stock.y + 3))
    }

    @Test
    fun `a point outside the layout is no drop target`() {
        val slot = result.slots.getValue(tableau(1))
        assertNull(pileAt(result, -5.0, 400.0))
        assertNull(pileAt(result, slot.x + 5, result.prefHeight + 5))
        assertNull(pileAt(result, result.prefWidth + 5, 400.0))
    }
}
