package dev.sluide.games.solitaire

import dev.sluide.games.solitaire.ui.emptyTable
import dev.sluide.games.solitaire.ui.tableau
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CursorTest {
    private fun up(codes: String) = Pile.allFaceUp(cards(codes))

    /** Klondike: column 0 holds a face-down King under the run 8S 7H 6C. */
    private val klondike =
        emptyTable(
            Mode.KLONDIKE_DRAW_1,
            mapOf(
                tableau(0) to Pile(cards("KD 8S 7H 6C"), 3),
                tableau(1) to up("4D"),
                PileId(PileKind.STOCK) to Pile(cards("2C 3C")),
                PileId(PileKind.WASTE) to up("AH"),
            ),
        )
    private val klondikeRules = Rules.of(Mode.KLONDIKE_DRAW_1)

    private fun Cursor.go(vararg directions: Direction): Cursor =
        directions.fold(this) { cursor, direction -> cursor.moved(direction, klondikeRules, klondike) }

    @ParameterizedTest
    @EnumSource(Direction::class)
    internal fun `the first key puts the cursor on the first column`(direction: Direction) {
        assertEquals(Cursor(tableau(0), 1), Cursor().go(direction))
    }

    @ParameterizedTest(name = "{0}: {1} -> column {2}")
    @MethodSource("columnsUnder")
    internal fun `down from the top row goes to the column under the pile`(
        mode: Mode,
        pile: PileId,
        column: Int,
    ) {
        val table = emptyTable(mode)

        assertEquals(Cursor(tableau(column), 0), Cursor(pile, 0).moved(Direction.DOWN, Rules.of(mode), table))
    }

    @ParameterizedTest(name = "{0}: column {1} -> {2}")
    @MethodSource("pilesAbove")
    internal fun `up from an empty column goes to the pile above or the nearest one to its left`(
        mode: Mode,
        column: Int,
        pile: PileId,
    ) {
        val table = emptyTable(mode)

        assertEquals(Cursor(pile, 0), Cursor(tableau(column), 0).moved(Direction.UP, Rules.of(mode), table))
    }

    @Test
    fun `up in a column selects the run card by card, then goes to the top row`() {
        val start = Cursor(tableau(0), 1)

        assertEquals(Cursor(tableau(0), 2), start.go(Direction.UP))
        assertEquals(Cursor(tableau(0), 3), start.go(Direction.UP, Direction.UP))
        assertEquals(Cursor(PileId(PileKind.STOCK), 1), start.go(Direction.UP, Direction.UP, Direction.UP))
    }

    @Test
    fun `down in a column selects one card less but at least one`() {
        assertEquals(Cursor(tableau(0), 2), Cursor(tableau(0), 3).go(Direction.DOWN))
        assertEquals(Cursor(tableau(0), 1), Cursor(tableau(0), 1).go(Direction.DOWN))
    }

    @Test
    fun `left and right move within the row with depth 1 and stop at the ends`() {
        assertEquals(Cursor(tableau(1), 1), Cursor(tableau(0), 3).go(Direction.RIGHT))
        assertEquals(Cursor(tableau(0), 2), Cursor(tableau(0), 2).go(Direction.LEFT))
        assertEquals(Cursor(tableau(6), 0), Cursor(tableau(6), 0).go(Direction.RIGHT))
        assertEquals(Cursor(PileId(PileKind.WASTE), 1), Cursor(PileId(PileKind.STOCK), 1).go(Direction.RIGHT))
        assertEquals(Cursor(PileId(PileKind.STOCK), 1), Cursor(PileId(PileKind.STOCK), 1).go(Direction.LEFT))
        val lastFoundation = Cursor(PileId(PileKind.FOUNDATION, 3), 0)
        assertEquals(lastFoundation, lastFoundation.go(Direction.RIGHT))
        assertEquals(lastFoundation, lastFoundation.go(Direction.UP))
    }

    @Test
    fun `the depth is clamped to the run, at least 1, 0 on an empty pile`() {
        assertEquals(Cursor(tableau(0), 3), Cursor(tableau(0), 9).clamped(klondikeRules, klondike))
        assertEquals(Cursor(tableau(1), 1), Cursor(tableau(1), 0).clamped(klondikeRules, klondike))
        assertEquals(Cursor(tableau(2), 0), Cursor(tableau(2), 1).clamped(klondikeRules, klondike))
        // The stock has no run but its top card is shown as selected.
        val stock = PileId(PileKind.STOCK)
        assertEquals(Cursor(stock, 1), Cursor(stock, 4).clamped(klondikeRules, klondike))
    }

    @Test
    fun `cards are picked up only when they can move together and never from the stock`() {
        assertEquals(Pick(tableau(0), 2), Cursor(tableau(0), 2).pickedUp(klondikeRules, klondike)?.picked)
        assertEquals(
            Pick(PileId(PileKind.WASTE), 1),
            Cursor(PileId(PileKind.WASTE), 1).pickedUp(klondikeRules, klondike)?.picked,
        )
        assertNull(Cursor(tableau(0), 4).pickedUp(klondikeRules, klondike))
        assertNull(Cursor(tableau(2), 0).pickedUp(klondikeRules, klondike))
        assertNull(Cursor(PileId(PileKind.STOCK), 1).pickedUp(klondikeRules, klondike))
        assertNull(Cursor().pickedUp(klondikeRules, klondike))
        val freeCell = emptyTable(Mode.FREECELL, mapOf(PileId(PileKind.FOUNDATION, 0) to up("AS")))
        assertNull(Cursor(PileId(PileKind.FOUNDATION, 0), 1).pickedUp(Rules.of(Mode.FREECELL), freeCell))
    }

    companion object {
        private val stock = PileId(PileKind.STOCK)
        private val waste = PileId(PileKind.WASTE)

        private fun foundation(index: Int) = PileId(PileKind.FOUNDATION, index)

        private fun cell(index: Int) = PileId(PileKind.CELL, index)

        @JvmStatic
        fun columnsUnder(): List<Arguments> =
            listOf(
                Arguments.of(Mode.KLONDIKE_DRAW_1, stock, 0),
                Arguments.of(Mode.KLONDIKE_DRAW_3, waste, 1),
                Arguments.of(Mode.KLONDIKE_DRAW_1, foundation(0), 3),
                Arguments.of(Mode.KLONDIKE_DRAW_1, foundation(3), 6),
                Arguments.of(Mode.FREECELL, cell(0), 0),
                Arguments.of(Mode.FREECELL, cell(3), 3),
                Arguments.of(Mode.FREECELL, foundation(0), 4),
                Arguments.of(Mode.FREECELL, foundation(3), 7),
                Arguments.of(Mode.SPIDER_1, stock, 0),
                Arguments.of(Mode.SPIDER_2, foundation(0), 2),
                Arguments.of(Mode.SPIDER_4, foundation(7), 9),
            )

        @JvmStatic
        fun pilesAbove(): List<Arguments> =
            listOf(
                Arguments.of(Mode.KLONDIKE_DRAW_1, 0, stock),
                Arguments.of(Mode.KLONDIKE_DRAW_1, 1, waste),
                Arguments.of(Mode.KLONDIKE_DRAW_1, 2, waste),
                Arguments.of(Mode.KLONDIKE_DRAW_1, 3, foundation(0)),
                Arguments.of(Mode.KLONDIKE_DRAW_1, 6, foundation(3)),
                Arguments.of(Mode.FREECELL, 3, cell(3)),
                Arguments.of(Mode.FREECELL, 4, foundation(0)),
                Arguments.of(Mode.SPIDER_1, 1, stock),
                Arguments.of(Mode.SPIDER_1, 2, foundation(0)),
                Arguments.of(Mode.SPIDER_4, 9, foundation(7)),
            )
    }
}
