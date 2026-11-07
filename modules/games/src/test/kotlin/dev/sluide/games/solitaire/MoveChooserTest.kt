package dev.sluide.games.solitaire

import dev.sluide.games.solitaire.KlondikeRules.Companion.COLUMNS
import dev.sluide.games.solitaire.KlondikeRules.Companion.FOUNDATIONS
import dev.sluide.games.solitaire.KlondikeRules.Companion.WASTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val RULES = KlondikeRules(Mode.KLONDIKE_DRAW_1)

/** A Klondike table; every pile face up, missing ones empty. */
private fun klondike(
    waste: String = "",
    foundations: List<String> = emptyList(),
    columns: List<String> = emptyList(),
): Table {
    val piles =
        buildMap {
            put(KlondikeRules.STOCK, Pile.EMPTY)
            put(WASTE, Pile.allFaceUp(cards(waste)))
            FOUNDATIONS.forEachIndexed { i, id -> put(id, Pile.allFaceUp(cards(foundations.getOrElse(i) { "" }))) }
            COLUMNS.forEachIndexed { i, id -> put(id, Pile.allFaceUp(cards(columns.getOrElse(i) { "" }))) }
        }
    return Table(Mode.KLONDIKE_DRAW_1, piles, fan = if (waste.isEmpty()) 0 else 1)
}

class MoveChooserTest {
    @Test
    fun `a card that fits a foundation goes there before any column`() {
        val table = klondike(columns = listOf("2S", "", "", "AH"))
        assertEquals(Move.Transfer(COLUMNS[3], FOUNDATIONS[0]), MoveChooser.best(RULES, table, COLUMNS[3], 1))
    }

    @Test
    fun `non-empty columns are tried leftmost first and before empty ones`() {
        val table = klondike(waste = "5H", columns = listOf("", "6C", "", "6S"))
        assertEquals(Move.Transfer(WASTE, COLUMNS[1]), MoveChooser.best(RULES, table, WASTE, 1))
    }

    @Test
    fun `a King goes to the first empty column`() {
        val table = klondike(waste = "KH", columns = listOf("5C", "", ""))
        assertEquals(Move.Transfer(WASTE, COLUMNS[1]), MoveChooser.best(RULES, table, WASTE, 1))
    }

    @Test
    fun `a whole column headed by a King is not moved to an empty column`() {
        val table = klondike(columns = listOf("KH QS", "KD"))
        assertNull(MoveChooser.best(RULES, table, COLUMNS[0], 2))
        assertNull(MoveChooser.best(RULES, table, COLUMNS[1], 1))
    }

    @Test
    fun `a run of several cards never goes to a foundation`() {
        val table = klondike(foundations = listOf("AS 2S"), columns = listOf("3S 2H", "4D"))
        assertEquals(Move.Transfer(COLUMNS[0], COLUMNS[1], 2), MoveChooser.best(RULES, table, COLUMNS[0], 2))
    }

    @Test
    fun `toFoundation is null when no foundation takes the card`() {
        val table = klondike(columns = listOf("5H"))
        assertNull(MoveChooser.toFoundation(RULES, table, COLUMNS[0]))
    }

    @Test
    fun `without a target a single card falls back to the first free cell`() {
        val tableau = PileId(PileKind.TABLEAU, 0)
        val cells = List(4) { PileId(PileKind.CELL, it) }
        val table =
            Table(
                Mode.FREECELL,
                buildMap {
                    put(tableau, Pile.allFaceUp(cards("5H")))
                    put(PileId(PileKind.FOUNDATION, 0), Pile.EMPTY)
                    cells.forEachIndexed { i, id -> put(id, if (i == 0) Pile.allFaceUp(cards("2C")) else Pile.EMPTY) }
                },
            )
        val rules = CellOnlyRules()
        assertEquals(Move.Transfer(tableau, cells[1]), MoveChooser.best(rules, table, tableau, 1))
        assertNull(MoveChooser.best(rules, table, tableau, 2))
    }
}

/** Allows only moves of one card into an empty cell. */
private class CellOnlyRules : Rules {
    override val mode = Mode.FREECELL

    override fun canMove(
        table: Table,
        move: Move,
    ) = move is Move.Transfer &&
        move.count == 1 &&
        move.to.kind == PileKind.CELL &&
        table.pile(move.to).cards.isEmpty()

    override fun deal(deck: List<Card>): Table = TODO()

    override fun legalMoves(table: Table): List<Move> = TODO()

    override fun runLength(
        table: Table,
        from: PileId,
    ): Int = TODO()

    override fun apply(
        table: Table,
        move: Move,
    ): Outcome = TODO()

    override fun isWon(table: Table): Boolean = TODO()

    override fun isDecided(table: Table): Boolean = TODO()
}
