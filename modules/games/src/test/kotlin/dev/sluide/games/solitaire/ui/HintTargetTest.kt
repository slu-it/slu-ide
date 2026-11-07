package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import kotlin.test.Test
import kotlin.test.assertEquals

class HintTargetTest {
    private val stock = PileId(PileKind.STOCK)
    private val waste = PileId(PileKind.WASTE)

    @Test
    fun `a transfer hint outlines its cards and target`() {
        val move = Move.Transfer(PileId(PileKind.TABLEAU, 2), PileId(PileKind.TABLEAU, 5), 3)

        assertEquals(HintTarget(move.from, 3, move.to), hintTarget(move, Mode.KLONDIKE_DRAW_1))
    }

    @Test
    fun `a Klondike stock move points from the stock to the waste`() {
        assertEquals(HintTarget(stock, 1, waste), hintTarget(Move.Draw, Mode.KLONDIKE_DRAW_3))
        assertEquals(HintTarget(stock, 1, waste), hintTarget(Move.Recycle, Mode.KLONDIKE_DRAW_1))
    }

    @Test
    fun `dealing a Spider row outlines the stock only`() {
        assertEquals(HintTarget(stock, 1, stock), hintTarget(Move.DealRow, Mode.SPIDER_2))
    }
}
