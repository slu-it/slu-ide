package dev.sluide.games.solitaire

import dev.sluide.games.solitaire.FreeCellRules.Companion.CELLS
import dev.sluide.games.solitaire.FreeCellRules.Companion.COLUMNS
import dev.sluide.games.solitaire.FreeCellRules.Companion.FOUNDATIONS
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val RULES = FreeCellRules()

/** A FreeCell table from codes, bottom to top; missing cells, foundations and columns are empty. */
private fun freeCell(
    cells: List<String> = emptyList(),
    foundations: List<String> = emptyList(),
    columns: List<String> = emptyList(),
): Table {
    fun piles(
        ids: List<PileId>,
        specs: List<String>,
    ) = ids.mapIndexed { i, id -> id to Pile.allFaceUp(cards(specs.getOrElse(i) { "" })) }
    return Table(
        Mode.FREECELL,
        (piles(CELLS, cells) + piles(FOUNDATIONS, foundations) + piles(COLUMNS, columns)).toMap(),
    )
}

private fun column(i: Int) = COLUMNS[i]

class FreeCellRulesTest {
    @Test
    fun `deals row by row, all face up, 7 cards on columns 0 to 3 and 6 on the others`() {
        val deck = Deals.orderedDeck(Mode.FREECELL)
        val table = RULES.deal(deck)

        assertEquals(Mode.FREECELL, table.mode)
        assertEquals(listOf(0, 8, 16, 24, 32, 40, 48).map { deck[it] }, table.pile(column(0)).cards)
        assertEquals(listOf(7, 15, 23, 31, 39, 47).map { deck[it] }, table.pile(column(7)).cards)
        COLUMNS.forEachIndexed { i, id ->
            assertEquals(if (i < 4) 7 else 6, table.pile(id).cards.size)
            assertEquals(table.pile(id).cards.size, table.pile(id).faceUp)
        }
        assertEquals(deck.toSet(), COLUMNS.flatMap { table.pile(it).cards }.toSet())
        (CELLS + FOUNDATIONS).forEach { assertEquals(Pile.EMPTY, table.pile(it)) }
        assertEquals(emptyList(), table.ids(PileKind.STOCK))
        assertEquals(CELLS, table.ids(PileKind.CELL))
        assertEquals(COLUMNS, table.ids(PileKind.TABLEAU))
    }

    @Test
    fun `refuses a deck that is not one full deck`() {
        val deck = Deals.orderedDeck(Mode.FREECELL)
        assertThrows<IllegalArgumentException> { RULES.deal(deck.drop(1)) }
        assertThrows<IllegalArgumentException> { RULES.deal(deck.drop(1) + deck[1]) }
    }

    @ParameterizedTest
    @CsvSource(
        "'', 0",
        "KS, 1",
        "KS QH, 2",
        "KS QH JC 10D, 4",
        "KS QS, 1",
        "KS QH JH, 1",
        "5S KS QH JC, 3",
    )
    fun `a column's run is its top cards descending in alternating colours`(
        spec: String,
        expected: Int,
    ) {
        assertEquals(expected, RULES.runLength(freeCell(columns = listOf(spec)), column(0)))
    }

    @Test
    fun `cells give 1 when filled, foundations and the absent stock nothing`() {
        val table = freeCell(cells = listOf("", "5C"), foundations = listOf("AH 2H"), columns = listOf("KS"))
        assertEquals(0, RULES.runLength(table, CELLS[0]))
        assertEquals(1, RULES.runLength(table, CELLS[1]))
        assertEquals(0, RULES.runLength(table, FOUNDATIONS[0]))
        assertEquals(0, RULES.runLength(table, PileId(PileKind.STOCK)))
    }

    @ParameterizedTest
    @CsvSource(
        "9H, 10C, true",
        "9H, 10S, true",
        "9H, 10D, false",
        "9H, JC, false",
        "9H, 9C, false",
        "9H, '', true",
    )
    fun `a card builds down in alternating colours onto a column, any card onto an empty one`(
        card: String,
        target: String,
        legal: Boolean,
    ) {
        val table = freeCell(columns = listOf(card, target))
        assertEquals(legal, RULES.canMove(table, Move.Transfer(column(0), column(1))))
    }

    @Test
    fun `a cell's card goes to a column by the same rule`() {
        val table = freeCell(cells = listOf("9H"), columns = listOf("10C", "10D", ""))
        assertTrue(RULES.canMove(table, Move.Transfer(CELLS[0], column(0))))
        assertFalse(RULES.canMove(table, Move.Transfer(CELLS[0], column(1))))
        assertTrue(RULES.canMove(table, Move.Transfer(CELLS[0], column(2))))
    }

    @Test
    fun `only a column's top card goes to an empty cell, a cell never to a cell`() {
        val table = freeCell(cells = listOf("", "AC", ""), columns = listOf("9H 8C"))
        assertTrue(RULES.canMove(table, Move.Transfer(column(0), CELLS[0])))
        assertTrue(RULES.canMove(table, Move.Transfer(column(0), CELLS[2])))
        assertFalse(RULES.canMove(table, Move.Transfer(column(0), CELLS[1])))
        assertFalse(RULES.canMove(table, Move.Transfer(column(0), CELLS[0], 2)))
        assertFalse(RULES.canMove(table, Move.Transfer(CELLS[1], CELLS[0])))
        assertFalse(RULES.canMove(table, Move.Transfer(CELLS[1], CELLS[1])))
    }

    @ParameterizedTest
    @CsvSource(
        "'', AS, true",
        "'', 2S, false",
        "AS, 2S, true",
        "AS, 2H, false",
        "AS, 3S, false",
        "AS 2S, AS, false",
    )
    fun `foundations take an Ace when empty and build up in suit`(
        foundation: String,
        card: String,
        legal: Boolean,
    ) {
        val table = freeCell(cells = listOf(card), foundations = listOf(foundation), columns = listOf(card))
        assertEquals(legal, RULES.canMove(table, Move.Transfer(CELLS[0], FOUNDATIONS[0])))
        assertEquals(legal, RULES.canMove(table, Move.Transfer(column(0), FOUNDATIONS[0])))
    }

    @Test
    fun `an Ace may go to any empty foundation, a run only one card at a time, nothing leaves a foundation`() {
        val table = freeCell(foundations = listOf("AH", "", "AC 2C"), columns = listOf("5C AD", "5H 4S"))
        assertTrue(RULES.canMove(table, Move.Transfer(column(0), FOUNDATIONS[1])))
        assertTrue(RULES.canMove(table, Move.Transfer(column(0), FOUNDATIONS[3])))
        assertFalse(RULES.canMove(table, Move.Transfer(column(1), FOUNDATIONS[1], 2)))
        assertFalse(RULES.canMove(table, Move.Transfer(FOUNDATIONS[0], column(1))))
        assertFalse(RULES.canMove(table, Move.Transfer(FOUNDATIONS[0], CELLS[0])))
        assertFalse(RULES.canMove(table, Move.Transfer(FOUNDATIONS[2], FOUNDATIONS[3])))
    }

    @ParameterizedTest
    @CsvSource(
        "0, 0, 5",
        "3, 0, 2",
        "4, 0, 1",
        "2, 1, 6",
        "4, 1, 2",
        "0, 1, 10",
        "1, 1, 8",
        "3, 2, 8",
        "4, 3, 8",
    )
    fun `a run moves up to (free cells + 1) times 2 to the empty columns, no more`(
        occupiedCells: Int,
        emptyColumns: Int,
        capacity: Int,
    ) {
        fun table(targetRank: Int) =
            freeCell(
                cells = List(occupiedCells) { "2C" },
                columns =
                    listOf(RUN, alternating(targetRank)) + List(emptyColumns) { "" } + List(6 - emptyColumns) { "2C" },
            )
        assertTrue(RULES.canMove(table(capacity + 1), Move.Transfer(column(0), column(1), capacity)))
        assertFalse(RULES.canMove(table(capacity + 2), Move.Transfer(column(0), column(1), capacity + 1)))
        assertFalse(RULES.legalMoves(table(capacity + 2)).contains(Move.Transfer(column(0), column(1), capacity + 1)))
    }

    @Test
    fun `an empty target column does not count as an empty column for the capacity`() {
        val table = freeCell(columns = listOf(RUN, "") + List(5) { "2C" } + "")
        assertTrue(RULES.canMove(table, Move.Transfer(column(0), column(1), 10)))
        assertFalse(RULES.canMove(table, Move.Transfer(column(0), column(1), 11)))
    }

    @Test
    fun `moves a run and scores minus one`() {
        val table = freeCell(columns = listOf("3C 9H 8C", "10S"))
        val outcome = RULES.apply(table, Move.Transfer(column(0), column(1), 2))

        assertEquals(cards("3C"), outcome.table.pile(column(0)).cards)
        assertEquals(cards("10S 9H 8C"), outcome.table.pile(column(1)).cards)
        assertEquals(3, outcome.table.pile(column(1)).faceUp)
        assertEquals(-1, outcome.points)
        assertEquals(emptyList(), outcome.flipped)
        assertEquals(emptyList(), outcome.completedRuns)
    }

    @Test
    fun `a card to a cell and from a cell scores minus one`() {
        val table = freeCell(columns = listOf("9H", "10C"), cells = listOf("", "8S"))
        val toCell = RULES.apply(table, Move.Transfer(column(0), CELLS[0]))
        assertEquals(cards("9H"), toCell.table.pile(CELLS[0]).cards)
        assertEquals(emptyList(), toCell.table.pile(column(0)).cards)
        assertEquals(-1, toCell.points)
        val fromCell = RULES.apply(table, Move.Transfer(CELLS[1], column(0)))
        assertEquals(cards("9H 8S"), fromCell.table.pile(column(0)).cards)
        assertEquals(emptyList(), fromCell.table.pile(CELLS[1]).cards)
        assertEquals(-1, fromCell.points)
    }

    @Test
    fun `a card to a foundation scores 10 minus the move`() {
        val table = freeCell(cells = listOf("AH"), foundations = listOf("", "AC"), columns = listOf("5D 2C"))
        val fromCell = RULES.apply(table, Move.Transfer(CELLS[0], FOUNDATIONS[0]))
        assertEquals(9, fromCell.points)
        assertEquals(cards("AH"), fromCell.table.pile(FOUNDATIONS[0]).cards)
        val fromColumn = RULES.apply(table, Move.Transfer(column(0), FOUNDATIONS[1]))
        assertEquals(9, fromColumn.points)
        assertEquals(cards("AC 2C"), fromColumn.table.pile(FOUNDATIONS[1]).cards)
        assertEquals(emptyList(), fromColumn.flipped)
        assertEquals(emptyList(), fromColumn.completedRuns)
    }

    @Test
    fun `lists legal moves in a stable order`() {
        val table =
            freeCell(
                cells = listOf("AH"),
                foundations = listOf("", "", "AD", "AC 2C"),
                columns = listOf("AS", "9H 8C", "10S", "3C", "10D"),
            )

        fun t(
            from: PileId,
            to: PileId,
            count: Int = 1,
        ) = Move.Transfer(from, to, count)
        val empties = listOf(5, 6, 7).map { column(it) }
        val expected =
            listOf(
                t(CELLS[0], FOUNDATIONS[0]),
                t(CELLS[0], FOUNDATIONS[1]),
                t(column(0), FOUNDATIONS[0]),
                t(column(0), FOUNDATIONS[1]),
                t(column(3), FOUNDATIONS[3]),
            ) +
                empties.map { t(CELLS[0], it) } +
                empties.map { t(column(0), it) } +
                empties.map { t(column(1), it) } +
                listOf(t(column(1), column(2), 2)) +
                empties.map { t(column(1), it, 2) } +
                empties.map { t(column(2), it) } +
                empties.map { t(column(3), it) } +
                empties.map { t(column(4), it) } +
                listOf(0, 1, 2, 3, 4).map { t(column(it), CELLS[1]) }
        assertEquals(expected, RULES.legalMoves(table))
    }

    @Test
    fun `only the first empty cell is listed for a column's top card, canMove allows each`() {
        val table = freeCell(cells = listOf("2C", "", "2D", ""), columns = listOf("KS"))
        val toCells = RULES.legalMoves(table).filter { it is Move.Transfer && it.to.kind == PileKind.CELL }
        assertEquals(listOf(Move.Transfer(column(0), CELLS[1])), toCells)
        assertTrue(RULES.canMove(table, Move.Transfer(column(0), CELLS[3])))
    }

    @Test
    fun `no empty cell, no move to a cell`() {
        val table = freeCell(cells = listOf("2C", "3C", "4C", "5C"), columns = listOf("KS"))
        assertFalse(RULES.canMove(table, Move.Transfer(column(0), CELLS[0])))
        assertEquals(emptyList(), RULES.legalMoves(table).filter { (it as Move.Transfer).to.kind == PileKind.CELL })
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5])
    fun `legal moves are the moves canMove allows but the repeats into other empty cells, without duplicates`(
        seed: Int,
    ) {
        val random = Random(seed)
        val ids = CELLS + FOUNDATIONS + COLUMNS
        val candidates =
            ids.flatMap { from -> ids.flatMap { to -> (0..14).map { Move.Transfer(from, to, it) } } } +
                listOf(Move.Draw, Move.Recycle, Move.DealRow)
        var table = RULES.deal(Deals.deck(Mode.FREECELL, seed))
        repeat(300) {
            val moves = RULES.legalMoves(table)
            assertEquals(moves.distinct(), moves)
            val firstEmptyCell = CELLS.firstOrNull { table.pile(it).cards.isEmpty() }
            val allowed =
                candidates.filter {
                    RULES.canMove(table, it) &&
                        !(it is Move.Transfer && it.to.kind == PileKind.CELL && it.to != firstEmptyCell)
                }
            assertEquals(allowed.toSet(), moves.toSet())
            if (moves.isEmpty()) return
            table = RULES.apply(table, moves[random.nextInt(moves.size)]).table
            assertEquals(52, ids.sumOf { table.pile(it).cards.size })
        }
    }

    @Test
    fun `other moves are never legal`() {
        val table = freeCell(columns = listOf("9H"))
        listOf(Move.Draw, Move.Recycle, Move.DealRow).forEach { assertFalse(RULES.canMove(table, it)) }
        assertFalse(RULES.canMove(table, Move.Transfer(PileId(PileKind.STOCK), column(0))))
    }

    @Test
    fun `an illegal move is refused, as is a table of another mode`() {
        val table = freeCell(columns = listOf("9H", "10D"))
        assertThrows<IllegalArgumentException> { RULES.apply(table, Move.Transfer(column(0), column(1))) }
        val other = freeCell(columns = listOf("9H")).copy(mode = Mode.KLONDIKE_DRAW_1)
        assertFalse(RULES.canMove(other, Move.Transfer(column(0), CELLS[0])))
        assertEquals(emptyList(), RULES.legalMoves(other))
        assertThrows<IllegalArgumentException> { RULES.apply(other, Move.Transfer(column(0), CELLS[0])) }
    }

    @Test
    fun `won with all 52 cards on the foundations`() {
        val full = Suit.entries.map { suit -> (1..13).joinToString(" ") { Card(it, suit).code } }
        assertTrue(RULES.isWon(freeCell(foundations = full)))
        val missingKing = full.dropLast(1) + full.last().substringBeforeLast(' ')
        assertFalse(RULES.isWon(freeCell(foundations = missingKing, columns = listOf("KS"))))
        assertFalse(RULES.isWon(RULES.deal(Deals.orderedDeck(Mode.FREECELL))))
    }

    @ParameterizedTest
    @CsvSource(
        "'KS QH 3C', true",
        "'KS QH QC', false",
        "'5H 9S', false",
        "'5H 5S', false",
        "'', true",
    )
    fun `decided when every column descends from bottom to top`(
        column: String,
        decided: Boolean,
    ) {
        assertEquals(decided, RULES.isDecided(freeCell(columns = listOf("KD", column, "2S"), cells = listOf("KC"))))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8])
    fun `a decided table is won by foundation moves alone`(seed: Int) {
        val random = Random(seed)
        val deck = Deals.orderedDeck(Mode.FREECELL).shuffled(random)
        val inCells = deck.take(random.nextInt(5))
        val columns = List(8) { mutableListOf<Card>() }
        deck.drop(inCells.size).forEach { card ->
            // a rank only once per column: a column descends strictly
            columns.filter { column -> column.none { it.rank == card.rank } }.random(random) += card
        }
        val descending = compareByDescending<Card> { it.rank }.thenBy { it.suit }
        val piles =
            CELLS.mapIndexed { i, id -> id to Pile.allFaceUp(listOfNotNull(inCells.getOrNull(i))) } +
                FOUNDATIONS.map { it to Pile.EMPTY } +
                COLUMNS.mapIndexed { i, id -> id to Pile.allFaceUp(columns[i].sortedWith(descending)) }
        var table = Table(Mode.FREECELL, piles.toMap())

        repeat(52) {
            assertTrue(RULES.isDecided(table))
            val move = RULES.legalMoves(table).firstOrNull { it is Move.Transfer && it.to.kind == PileKind.FOUNDATION }
            assertNotNull(move, "no foundation move on ${table.piles}")
            table = RULES.apply(table, move).table
        }
        assertTrue(RULES.isWon(table))
    }

    private companion object {
        /** A king-to-ace run of alternating colours: spades on odd ranks, hearts on even ones. */
        val RUN = (13 downTo 1).joinToString(" ") { alternating(it) }

        fun alternating(rank: Int): String = Card(rank, if (rank % 2 == 1) Suit.SPADES else Suit.HEARTS).code
    }
}
