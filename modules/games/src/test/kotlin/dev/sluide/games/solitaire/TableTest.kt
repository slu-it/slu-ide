package dev.sluide.games.solitaire

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TableTest {
    @Test
    fun `a pile knows its top card and which cards are face up`() {
        val pile = Pile(cards("KS QH JC 10D"), 2)
        assertEquals(card("10D"), pile.top)
        assertEquals(2, pile.faceDown)
        assertEquals(cards("JC 10D"), pile.faceUpCards)
        assertNull(Pile.EMPTY.top)
        assertEquals(emptyList(), Pile.EMPTY.faceUpCards)
        assertEquals(Pile(cards("AC 2C"), 2), Pile.allFaceUp(cards("AC 2C")))
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 3])
    fun `a pile refuses more face-up cards than it has, or fewer than none`(faceUp: Int) {
        assertThrows<IllegalArgumentException> { Pile(cards("AC 2C"), faceUp) }
    }

    @Test
    fun `a table lists its ids by index and gives an empty pile for an id it has not`() {
        val second = PileId(PileKind.TABLEAU, 1)
        val first = PileId(PileKind.TABLEAU, 0)
        val table = Table(Mode.FREECELL, mapOf(second to Pile.allFaceUp(cards("AC")), first to Pile.EMPTY))
        assertEquals(listOf(first, second), table.ids(PileKind.TABLEAU))
        assertEquals(emptyList(), table.ids(PileKind.CELL))
        assertEquals(Pile.EMPTY, table.pile(PileId(PileKind.CELL, 3)))
    }

    @Test
    fun `with replaces the given piles and keeps the others`() {
        val a = PileId(PileKind.TABLEAU, 0)
        val b = PileId(PileKind.TABLEAU, 1)
        val table = Table(Mode.KLONDIKE_DRAW_3, mapOf(a to Pile.allFaceUp(cards("AC")), b to Pile.EMPTY), fan = 2)
        val changed = table.with(a to Pile.EMPTY, b to Pile.allFaceUp(cards("AC")))
        assertEquals(Table(Mode.KLONDIKE_DRAW_3, mapOf(a to Pile.EMPTY, b to Pile.allFaceUp(cards("AC"))), 2), changed)
        assertEquals(Pile.allFaceUp(cards("AC")), table.pile(a), "the table itself is unchanged")
    }
}
