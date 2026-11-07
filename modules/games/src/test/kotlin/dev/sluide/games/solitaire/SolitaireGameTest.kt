package dev.sluide.games.solitaire

import dev.sluide.games.solitaire.KlondikeRules.Companion.COLUMNS
import dev.sluide.games.solitaire.KlondikeRules.Companion.FOUNDATIONS
import dev.sluide.games.solitaire.KlondikeRules.Companion.STOCK
import dev.sluide.games.solitaire.KlondikeRules.Companion.WASTE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val RANK_CODES = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

/** The cards of [suit] from Ace up to [top] (rank code), as codes. */
private fun suitUpTo(
    suit: Char,
    top: String,
): String = RANK_CODES.take(RANK_CODES.indexOf(top) + 1).joinToString(" ") { "$it$suit" }

private fun klondike(
    mode: Mode = Mode.KLONDIKE_DRAW_1,
    stock: String = "",
    waste: String = "",
    foundations: List<String> = emptyList(),
    columns: List<String> = emptyList(),
): Table {
    val piles =
        mapOf(STOCK to Pile(cards(stock)), WASTE to Pile.allFaceUp(cards(waste))) +
            FOUNDATIONS.mapIndexed { i, id -> id to Pile.allFaceUp(cards(foundations.getOrElse(i) { "" })) } +
            COLUMNS.mapIndexed { i, id -> id to Pile.allFaceUp(cards(columns.getOrElse(i) { "" })) }
    return Table(mode, piles, if (waste.isEmpty()) 0 else 1)
}

private fun game(table: Table) = SolitaireGame(table.mode, 1, table)

/** Three suits complete, spades up to the Jack, the Queen on column 1 and the King on column 0: decided, not won. */
private fun nearlyWon(): Table =
    klondike(
        foundations = listOf(suitUpTo('C', "K"), suitUpTo('D', "K"), suitUpTo('H', "K"), suitUpTo('S', "J")),
        columns = listOf("KS", "QS"),
    )

private val TO_FOUNDATION_3 = Move.Transfer(COLUMNS[0], FOUNDATIONS[3])

/** Spider-like rules: every transfer completes a run for 100 points; never won. */
private class FakeSpiderRules : Rules {
    override val mode = Mode.SPIDER_1

    override fun deal(deck: List<Card>) = error("unused")

    override fun legalMoves(table: Table) = emptyList<Move>()

    override fun canMove(
        table: Table,
        move: Move,
    ) = move is Move.Transfer

    override fun runLength(
        table: Table,
        from: PileId,
    ) = 0

    override fun apply(
        table: Table,
        move: Move,
    ) = Outcome(table.copy(fan = table.fan + 1), emptyList(), listOf(PileId(PileKind.TABLEAU, 0)), 100)

    override fun isWon(table: Table) = false

    override fun isDecided(table: Table) = false
}

/** Rules with a fixed list of legal moves; every move adds a card to a foundation, won after [winAfterMoves]. */
private class FakeRules(
    private val decided: Boolean,
    private val legal: List<Move>,
    private val winAfterMoves: Int,
) : Rules {
    override val mode = Mode.FREECELL

    override fun deal(deck: List<Card>) = error("unused")

    override fun legalMoves(table: Table) = legal

    override fun canMove(
        table: Table,
        move: Move,
    ) = true

    override fun runLength(
        table: Table,
        from: PileId,
    ) = 0

    override fun apply(
        table: Table,
        move: Move,
    ) = Outcome(table.copy(fan = table.fan + 1), emptyList(), emptyList(), 0)

    override fun isWon(table: Table) = table.fan >= winAfterMoves

    override fun isDecided(table: Table) = decided
}

private fun fakeGame(
    decided: Boolean,
    legal: List<Move>,
    winAfterMoves: Int = Int.MAX_VALUE,
): SolitaireGame {
    val table = Table(Mode.FREECELL, mapOf(PileId(PileKind.CELL, 0) to Pile.allFaceUp(cards("AS"))))
    return SolitaireGame(Mode.FREECELL, 1, table, FakeRules(decided, legal, winAfterMoves))
}

class SolitaireGameTest {
    private val spider =
        SolitaireGame(Mode.SPIDER_1, 1, Table(Mode.SPIDER_1, emptyMap()), FakeSpiderRules())
    private val spiderMove = Move.Transfer(PileId(PileKind.TABLEAU, 0), PileId(PileKind.TABLEAU, 1))

    @Test
    fun `a legal move changes the table and counts, an illegal one does nothing`() {
        val g = game(klondike(waste = "5H", columns = listOf("6S")))
        val illegal = Move.Transfer(WASTE, COLUMNS[1])
        assertNull(g.move(Move.Draw))
        assertNull(g.move(Move.Transfer(WASTE, FOUNDATIONS[0])))
        assertEquals(0, g.moves)
        val step = assertNotNull(g.move(Move.Transfer(WASTE, COLUMNS[0])))
        assertEquals(g.start, step.before)
        assertEquals(g.table, step.outcome.table)
        assertEquals("6S 5H", codes(g.table.pile(COLUMNS[0]).cards))
        assertEquals(1, g.moves)
        assertFalse(g.canMove(illegal))
    }

    @Test
    fun `positions list the tables from the start to now`() {
        val g = game(klondike(stock = "AS 2S 3S"))
        g.move(Move.Draw)
        g.move(Move.Draw)
        assertEquals(3, g.positions.size)
        assertEquals(g.start, g.positions.first())
        assertEquals(g.table, g.positions.last())
        g.undo()
        assertEquals(2, g.positions.size)
        assertEquals(g.table, g.positions.last())
    }

    @Test
    fun `undo and redo move through the history, each counting a move`() {
        val g = game(klondike(stock = "AS 2S"))
        val first = assertNotNull(g.move(Move.Draw))
        val second = assertNotNull(g.move(Move.Draw))
        assertEquals(second, g.undo())
        assertEquals(second.before, g.table)
        assertEquals(first, g.undo())
        assertEquals(g.start, g.table)
        assertFalse(g.canUndo)
        assertNull(g.undo())
        assertEquals(first, g.redo())
        assertEquals(second, g.redo())
        assertNull(g.redo())
        assertEquals(6, g.moves)
    }

    @Test
    fun `a new move clears redo`() {
        val g = game(klondike(stock = "AS 2S"))
        g.move(Move.Draw)
        g.undo()
        assertTrue(g.canRedo)
        g.move(Move.Draw)
        assertFalse(g.canRedo)
        assertNull(g.redo())
    }

    @Test
    fun `undo takes a step's points away and costs one point, redo gives them back`() {
        val g = game(klondike(waste = "5H", columns = listOf("6S")))
        g.move(Move.Transfer(WASTE, COLUMNS[0]))
        assertEquals(5, g.score(0))
        g.undo()
        assertEquals(0, g.score(0))
        g.redo()
        assertEquals(4, g.score(0))
    }

    @Test
    fun `the Klondike score never drops below 0`() {
        val g = game(klondike(waste = "AH"))
        g.move(Move.Recycle)
        assertEquals(0, g.score(0))
        g.undo()
        assertEquals(0, g.score(0))
        g.redo()
        assertEquals(0, g.score(0))
    }

    @Test
    fun `points above the floor lose the undo penalty`() {
        val g = game(klondike(columns = listOf("AS"), waste = "AH"))
        g.move(Move.Transfer(COLUMNS[0], FOUNDATIONS[0]))
        g.move(Move.Transfer(WASTE, FOUNDATIONS[1]))
        assertEquals(20, g.score(0))
        g.undo()
        assertEquals(9, g.score(0))
    }

    @Test
    fun `hints cost 20 each and are counted`() {
        spider.hintUsed()
        spider.hintUsed()
        assertEquals(2, spider.hints)
        assertEquals(500 - 40, spider.score(0))
    }

    @Test
    fun `a hint after the win does nothing`() {
        val g = game(nearlyWon())
        g.autoFinishMoves().forEach { g.move(it) }
        g.hintUsed()
        assertEquals(0, g.hints)
    }

    @Test
    fun `restart clears history, moves and move points but keeps undo and hint penalties in Spider`() {
        spider.move(spiderMove)
        spider.move(spiderMove)
        spider.undo()
        spider.hintUsed()
        assertEquals(500 + 100 - 1 - 20, spider.score(0))
        spider.restart()
        assertEquals(spider.start, spider.table)
        assertEquals(0, spider.moves)
        assertEquals(1, spider.hints)
        assertFalse(spider.canUndo)
        assertFalse(spider.canRedo)
        assertEquals(listOf(spider.start), spider.positions)
        assertEquals(500 - 1 - 20, spider.score(0))
    }

    @Test
    fun `Spider starts at 500 and a completed run gives 100`() {
        assertEquals(500, spider.score(1_000_000))
        val step = assertNotNull(spider.move(spiderMove))
        assertEquals(1, step.outcome.completedRuns.size)
        assertEquals(600, spider.score(1_000_000))
        spider.undo()
        assertEquals(499, spider.score(0))
    }

    @Test
    fun `Klondike loses 2 points per 10 seconds`() {
        val g = game(klondike(waste = "AC", columns = listOf("AS", "AH")))
        g.move(Move.Transfer(COLUMNS[0], FOUNDATIONS[0]))
        g.move(Move.Transfer(COLUMNS[1], FOUNDATIONS[1]))
        g.move(Move.Transfer(WASTE, FOUNDATIONS[2]))
        assertEquals(30 - 12, g.score(60_000))
    }

    @Test
    fun `winning ends undo, redo and moves and adds the time bonus`() {
        val g = game(nearlyWon())
        assertFalse(g.won)
        g.move(Move.Transfer(COLUMNS[1], FOUNDATIONS[3]))
        assertNotNull(g.move(TO_FOUNDATION_3))
        assertTrue(g.won)
        assertFalse(g.decided)
        assertFalse(g.canUndo)
        assertFalse(g.canRedo)
        assertNull(g.undo())
        val kingToEmpty = Move.Transfer(FOUNDATIONS[3], COLUMNS[0])
        assertFalse(g.canMove(kingToEmpty))
        assertNull(g.move(kingToEmpty))
        assertEquals(20 - 6 + 23_333, g.score(30_000))
    }

    @Test
    fun `auto-finish moves a decided game to its win in order`() {
        val g = game(nearlyWon())
        assertTrue(g.decided)
        val finish = g.autoFinishMoves()
        assertEquals(2, finish.size)
        finish.forEach { assertNotNull(g.move(it)) }
        assertTrue(g.won)
        assertEquals(2, g.moves)
    }

    @Test
    fun `auto-finish is empty when a decided game has no foundation move`() {
        assertTrue(fakeGame(decided = true, legal = emptyList()).autoFinishMoves().isEmpty())
    }

    @Test
    fun `auto-finish uses a move from a cell to a foundation`() {
        val move = Move.Transfer(PileId(PileKind.CELL, 0), PileId(PileKind.FOUNDATION, 0))
        assertEquals(listOf(move), fakeGame(decided = true, legal = listOf(move), winAfterMoves = 1).autoFinishMoves())
    }

    @Test
    fun `auto-finish ignores moves out of a foundation and ends`() {
        val loop = Move.Transfer(PileId(PileKind.FOUNDATION, 0), PileId(PileKind.FOUNDATION, 1))
        assertTrue(fakeGame(decided = true, legal = listOf(loop), winAfterMoves = 1).autoFinishMoves().isEmpty())
    }

    @Test
    fun `auto-finish stops when the moves never reach the win`() {
        val move = Move.Transfer(PileId(PileKind.CELL, 0), PileId(PileKind.FOUNDATION, 0))
        assertTrue(fakeGame(decided = true, legal = listOf(move)).autoFinishMoves().isEmpty())
    }

    @Test
    fun `a start table of another mode is refused`() {
        assertFailsWith<IllegalArgumentException> { SolitaireGame(Mode.SPIDER_1, 1, nearlyWon(), FakeSpiderRules()) }
    }

    @Test
    fun `auto-finish is empty when the game is not decided or won`() {
        assertTrue(game(klondike(stock = "AS")).autoFinishMoves().isEmpty())
        val g = game(nearlyWon())
        g.autoFinishMoves().forEach { g.move(it) }
        assertTrue(g.autoFinishMoves().isEmpty())
    }
}
