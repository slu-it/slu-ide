package dev.sluide.games.solitaire

import dev.sluide.games.solitaire.SpiderRules.Companion.COLUMNS
import dev.sluide.games.solitaire.SpiderRules.Companion.FOUNDATIONS
import dev.sluide.games.solitaire.SpiderRules.Companion.STOCK
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val SPIDER_MODES = listOf(Mode.SPIDER_1, Mode.SPIDER_2, Mode.SPIDER_4)
private val RULES = SpiderRules(Mode.SPIDER_4)

private const val KING_TO_TWO = "KH QH JH 10H 9H 8H 7H 6H 5H 4H 3H 2H"

/** A column from codes: the cards before `|` face down, after it face up; without `|` all face up. */
private fun col(spec: String): Pile {
    val parts = spec.split('|')
    if (parts.size == 1) return Pile.allFaceUp(cards(spec))
    val up = cards(parts[1])
    return Pile(cards(parts[0]) + up, up.size)
}

/** A Spider table from codes, bottom to top; missing foundations and columns are empty, the stock is face down. */
private fun spider(
    stock: String = "",
    foundations: List<String> = emptyList(),
    columns: List<String> = emptyList(),
    mode: Mode = Mode.SPIDER_4,
): Table {
    val piles =
        mapOf(STOCK to Pile(cards(stock))) +
            FOUNDATIONS.mapIndexed { i, id -> id to Pile.allFaceUp(cards(foundations.getOrElse(i) { "" })) } +
            COLUMNS.mapIndexed { i, id -> id to col(columns.getOrElse(i) { "" }) }
    return Table(mode, piles)
}

/** Ten columns holding one face-up King of clubs each, except those given. */
private fun kings(vararg columns: String): List<String> = columns.toList() + List(10 - columns.size) { "| KC" }

private fun t(
    from: Int,
    to: Int,
    count: Int = 1,
) = Move.Transfer(COLUMNS[from], COLUMNS[to], count)

class SpiderRulesTest {
    @ParameterizedTest
    @EnumSource(Mode::class, names = ["SPIDER_1", "SPIDER_2", "SPIDER_4"])
    fun `deals 54 cards row by row with the top card face up and the other 50 to the stock`(mode: Mode) {
        val deck = Deals.deck(mode, 7)
        val table = SpiderRules(mode).deal(deck)

        assertEquals(mode, table.mode)
        COLUMNS.forEachIndexed { i, id ->
            assertEquals(if (i < 4) 6 else 5, table.pile(id).cards.size)
            assertEquals(1, table.pile(id).faceUp)
        }
        assertEquals(listOf(0, 10, 20, 30, 40, 50).map { deck[it] }, table.pile(COLUMNS[0]).cards)
        assertEquals(listOf(9, 19, 29, 39, 49).map { deck[it] }, table.pile(COLUMNS[9]).cards)
        assertEquals(Pile(deck.subList(54, 104)), table.pile(STOCK))
        assertEquals(0, table.pile(STOCK).faceUp)
        assertEquals(deck.last(), table.pile(STOCK).top)
        FOUNDATIONS.forEach { assertEquals(Pile.EMPTY, table.pile(it)) }
        assertEquals(COLUMNS, table.ids(PileKind.TABLEAU))
        assertEquals(FOUNDATIONS, table.ids(PileKind.FOUNDATION))
        assertEquals(emptyList(), table.ids(PileKind.CELL))
    }

    @Test
    fun `refuses a deck that is not the 104 cards of its mode, and a mode that is not Spider`() {
        val deck = Deals.orderedDeck(Mode.SPIDER_2)
        assertThrows<IllegalArgumentException> { SpiderRules(Mode.SPIDER_2).deal(deck.drop(1)) }
        assertThrows<IllegalArgumentException> { SpiderRules(Mode.SPIDER_2).deal(Deals.orderedDeck(Mode.SPIDER_4)) }
        assertThrows<IllegalArgumentException> { SpiderRules(Mode.FREECELL) }
    }

    @ParameterizedTest
    @CsvSource(
        "'', 0",
        "KS |, 0",
        "KS | 9H 8H 7H, 3",
        "| 9H 8S, 1",
        "| 9H 7H, 1",
        "8H 7H | 9S, 1",
        "9H 8H | 7H 6H, 2",
        "9H | 8H 7H 6H, 3",
        "| KD QD JD 10D 9D 8D 7D 6D 5D 4D 3D 2D AD, 13",
    )
    fun `a column's run is its face-up top cards descending in one suit`(
        spec: String,
        expected: Int,
    ) {
        assertEquals(expected, RULES.runLength(spider(columns = listOf(spec)), COLUMNS[0]))
    }

    @Test
    fun `the stock and the foundations give no run`() {
        val table = spider(stock = "AC", foundations = listOf("AH 2H"))
        assertEquals(0, RULES.runLength(table, STOCK))
        assertEquals(0, RULES.runLength(table, FOUNDATIONS[0]))
    }

    @ParameterizedTest
    @CsvSource(
        "9H, 10C, true",
        "9H, 10H, true",
        "9H, JH, false",
        "9H, 9C, false",
        "9H, 8H, false",
        "9H, '', true",
    )
    fun `a card goes onto a card one higher of any suit, or onto an empty column`(
        card: String,
        target: String,
        legal: Boolean,
    ) {
        val table = spider(columns = listOf("| $card", if (target.isEmpty()) "" else "| $target"))
        assertEquals(legal, RULES.canMove(table, t(0, 1)))
    }

    @Test
    fun `any top part of a same-suit run moves, a mixed run does not move together`() {
        val table = spider(columns = listOf("| 9H 8H 7H", "| 9S", "| 8C", "| 9D 8S"))
        assertTrue(RULES.canMove(table, t(0, 2, 1)))
        assertTrue(RULES.canMove(table, t(0, 1, 2)))
        assertFalse(RULES.canMove(table, t(0, 1, 1)))
        assertFalse(RULES.canMove(table, t(0, 1, 3)))
        assertFalse(RULES.canMove(table, t(0, 1, 4)))
        assertFalse(RULES.canMove(table, t(3, 1, 2)))
        assertTrue(RULES.canMove(table, t(3, 1, 1)))
        assertFalse(RULES.canMove(table, t(3, 0, 1)))
    }

    @Test
    fun `nothing moves from or onto the stock and the foundations, draw and recycle are never legal`() {
        val table = spider(stock = "AC", foundations = listOf("AH", ""), columns = listOf("| 2H", "| KH"))
        assertFalse(RULES.canMove(table, Move.Transfer(STOCK, COLUMNS[0])))
        assertFalse(RULES.canMove(table, Move.Transfer(FOUNDATIONS[0], COLUMNS[0])))
        assertFalse(RULES.canMove(table, Move.Transfer(FOUNDATIONS[0], FOUNDATIONS[1])))
        assertFalse(RULES.canMove(table, Move.Transfer(COLUMNS[0], FOUNDATIONS[1])))
        assertFalse(RULES.canMove(table, Move.Transfer(COLUMNS[0], COLUMNS[0])))
        assertFalse(RULES.canMove(table, Move.Draw))
        assertFalse(RULES.canMove(table, Move.Recycle))
    }

    @Test
    fun `a transfer moves the cards and scores minus one`() {
        val table = spider(columns = listOf("| 9H 8H 7H", "| 9S"))
        val outcome = RULES.apply(table, t(0, 1, 2))

        assertEquals(cards("9H"), outcome.table.pile(COLUMNS[0]).cards)
        assertEquals(1, outcome.table.pile(COLUMNS[0]).faceUp)
        assertEquals(cards("9S 8H 7H"), outcome.table.pile(COLUMNS[1]).cards)
        assertEquals(3, outcome.table.pile(COLUMNS[1]).faceUp)
        assertEquals(-1, outcome.points)
        assertEquals(emptyList(), outcome.flipped)
        assertEquals(emptyList(), outcome.completedRuns)
    }

    @Test
    fun `uncovering a face-down card turns it face up`() {
        val table = spider(columns = listOf("4C | 8H", "| 9S"))
        val outcome = RULES.apply(table, t(0, 1))

        assertEquals(Pile(cards("4C"), 1), outcome.table.pile(COLUMNS[0]))
        assertEquals(listOf(COLUMNS[0]), outcome.flipped)
        assertEquals(-1, outcome.points)
    }

    @Test
    fun `emptying a column turns nothing`() {
        val outcome = RULES.apply(spider(columns = listOf("| 8H", "| 9S")), t(0, 1))
        assertEquals(Pile.EMPTY, outcome.table.pile(COLUMNS[0]))
        assertEquals(emptyList(), outcome.flipped)
    }

    @Test
    fun `a transfer that completes K to A of one suit sends the run to the first empty foundation`() {
        val table =
            spider(
                foundations = listOf("AC 2C 3C 4C 5C 6C 7C 8C 9C 10C JC QC KC"),
                columns = listOf("3C | $KING_TO_TWO", "2D | AH"),
            )
        val outcome = RULES.apply(table, t(1, 0))

        assertEquals(cards("$KING_TO_TWO AH"), outcome.table.pile(FOUNDATIONS[1]).cards)
        assertEquals(13, outcome.table.pile(FOUNDATIONS[1]).faceUp)
        assertEquals(Pile(cards("3C"), 1), outcome.table.pile(COLUMNS[0]))
        assertEquals(Pile(cards("2D"), 1), outcome.table.pile(COLUMNS[1]))
        assertEquals(listOf(COLUMNS[0]), outcome.completedRuns)
        assertEquals(listOf(COLUMNS[1], COLUMNS[0]), outcome.flipped)
        assertEquals(99, outcome.points)
    }

    @ParameterizedTest
    @CsvSource(
        "'| KH QH JH 10H 9H 8H 7S 6H 5H 4H 3H 2H'",
        "'KH | QH JH 10H 9H 8H 7H 6H 5H 4H 3H 2H'",
        "'| QH JH 10H 9H 8H 7H 6H 5H 4H 3H 2H'",
    )
    fun `a run that is mixed in suit, has a face-down King or lacks the King completes nothing`(spec: String) {
        val outcome = RULES.apply(spider(columns = listOf(spec, "| AH")), t(1, 0))
        assertEquals(emptyList(), outcome.completedRuns)
        assertEquals(Pile.EMPTY, outcome.table.pile(FOUNDATIONS[0]))
        assertEquals(
            13 - (if (spec.startsWith("| Q")) 1 else 0),
            outcome.table
                .pile(COLUMNS[0])
                .cards.size,
        )
        assertEquals(-1, outcome.points)
    }

    @Test
    fun `dealing a row puts the stock's top card on column 0, the next on column 1 and scores minus one`() {
        val table = spider(stock = "KD AS 2S 3S 4S 5S 6S 7S 8S 9S 10S", columns = kings())
        val outcome = RULES.apply(table, Move.DealRow)

        assertEquals(cards("KD"), outcome.table.pile(STOCK).cards)
        assertEquals(card("10S"), outcome.table.pile(COLUMNS[0]).top)
        COLUMNS.forEach { id ->
            assertEquals(
                2,
                outcome.table
                    .pile(id)
                    .cards.size,
            )
            assertEquals(2, outcome.table.pile(id).faceUp)
            assertEquals(
                card("KC"),
                outcome.table
                    .pile(id)
                    .cards
                    .first(),
            )
        }
        assertEquals(card("AS"), outcome.table.pile(COLUMNS[9]).top)
        assertEquals(card("9S"), outcome.table.pile(COLUMNS[1]).top)
        assertEquals(-1, outcome.points)
        assertEquals(emptyList(), outcome.flipped)
        assertEquals(emptyList(), outcome.completedRuns)
    }

    @Test
    fun `dealing a row completes the runs it finishes and turns what they uncover`() {
        val table =
            spider(
                stock = "2D 3D 4D 5D 6D 7D 8D 9D AS AH",
                foundations = listOf("AC 2C 3C 4C 5C 6C 7C 8C 9C 10C JC QC KC"),
                columns =
                    listOf("3C | $KING_TO_TWO", "4D | KS QS JS 10S 9S 8S 7S 6S 5S 4S 3S 2S") +
                        List(8) { "| KC" },
            )
        val outcome = RULES.apply(table, Move.DealRow)

        assertEquals(listOf(COLUMNS[0], COLUMNS[1]), outcome.completedRuns)
        assertEquals(listOf(COLUMNS[0], COLUMNS[1]), outcome.flipped)
        assertEquals(
            13,
            outcome.table
                .pile(FOUNDATIONS[1])
                .cards.size,
        )
        assertEquals(
            13,
            outcome.table
                .pile(FOUNDATIONS[2])
                .cards.size,
        )
        assertEquals(
            Suit.HEARTS,
            outcome.table
                .pile(FOUNDATIONS[1])
                .top
                ?.suit,
        )
        assertEquals(
            Suit.SPADES,
            outcome.table
                .pile(FOUNDATIONS[2])
                .top
                ?.suit,
        )
        assertEquals(Pile(cards("3C"), 1), outcome.table.pile(COLUMNS[0]))
        assertEquals(Pile(cards("4D"), 1), outcome.table.pile(COLUMNS[1]))
        assertEquals(card("9D"), outcome.table.pile(COLUMNS[2]).top)
        assertEquals(-1 + 200, outcome.points)
    }

    @Test
    fun `a row is dealt only with a stock and no empty column`() {
        val full = spider(stock = "AS", columns = kings())
        assertTrue(RULES.canMove(full, Move.DealRow))
        assertFalse(RULES.canMove(spider(stock = "AS", columns = kings("| KH", "")), Move.DealRow))
        assertFalse(RULES.canMove(spider(stock = "AS", columns = listOf("| KH")), Move.DealRow))
        assertFalse(RULES.canMove(spider(columns = kings()), Move.DealRow))
        assertThrows<IllegalArgumentException> { RULES.apply(spider(columns = kings()), Move.DealRow) }
    }

    @Test
    fun `lists legal moves in a stable order, without a row to deal while a column is empty`() {
        val table =
            spider(
                stock = "AS",
                columns = listOf("| 9H 8H", "| 9S", "| 10C", "") + List(6) { "| KC" },
            )
        val expected =
            listOf(t(0, 1, 1), t(0, 3, 1), t(0, 2, 2), t(0, 3, 2), t(1, 2), t(1, 3), t(2, 3)) +
                (4..9).map { t(it, 3) }
        assertEquals(expected, RULES.legalMoves(table))
    }

    @Test
    fun `lists the row to deal last`() {
        val table = spider(stock = "AS", columns = kings("| 9H 8H", "| 9S", "| 10C"))
        val moves = RULES.legalMoves(table)
        assertEquals(listOf(t(0, 1, 1), t(0, 2, 2), t(1, 2), Move.DealRow), moves)
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5])
    fun `legal moves are exactly the moves canMove allows, without duplicates`(seed: Int) {
        val ids = listOf(STOCK) + FOUNDATIONS + COLUMNS
        val candidates =
            ids.flatMap { from -> ids.flatMap { to -> (0..14).map { Move.Transfer(from, to, it) } } } +
                listOf(Move.Draw, Move.Recycle, Move.DealRow)
        SPIDER_MODES.forEach { mode ->
            val rules = SpiderRules(mode)
            val random = Random(seed)
            var table = rules.deal(Deals.deck(mode, seed))
            repeat(250) {
                val moves = rules.legalMoves(table)
                assertEquals(moves.distinct(), moves)
                assertEquals(candidates.filter { rules.canMove(table, it) }.toSet(), moves.toSet())
                if (moves.isEmpty()) return@forEach
                val move =
                    if (Move.DealRow in moves &&
                        random.nextInt(4) == 0
                    ) {
                        Move.DealRow
                    } else {
                        moves[random.nextInt(moves.size)]
                    }
                table = rules.apply(table, move).table
                assertEquals(104, ids.sumOf { table.pile(it).cards.size })
            }
        }
    }

    @Test
    fun `an illegal move is refused, as is a table of another mode`() {
        val table = spider(columns = listOf("| 9H", "| 9S"))
        assertThrows<IllegalArgumentException> { RULES.apply(table, t(0, 1)) }
        val other = spider(stock = "AS", columns = kings()).copy(mode = Mode.FREECELL)
        assertFalse(RULES.canMove(other, Move.DealRow))
        assertFalse(RULES.canMove(other, t(0, 1)))
        assertEquals(emptyList(), RULES.legalMoves(other))
        assertThrows<IllegalArgumentException> { RULES.apply(other, Move.DealRow) }
    }

    @Test
    fun `won with all 8 foundations full, never decided`() {
        val full = Suit.entries.flatMap { suit -> List(2) { (1..13).joinToString(" ") { Card(it, suit).code } } }
        assertTrue(RULES.isWon(spider(foundations = full)))
        assertFalse(RULES.isWon(spider(foundations = full.dropLast(1) + full.last().substringBeforeLast(' '))))
        val dealt = RULES.deal(Deals.deck(Mode.SPIDER_4, 1))
        assertFalse(RULES.isWon(dealt))
        assertFalse(RULES.isDecided(dealt))
        assertFalse(RULES.isDecided(spider(foundations = full)))
    }
}
