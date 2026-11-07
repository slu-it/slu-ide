package dev.sluide.games.solitaire

import dev.sluide.games.solitaire.KlondikeRules.Companion.COLUMNS
import dev.sluide.games.solitaire.KlondikeRules.Companion.FOUNDATIONS
import dev.sluide.games.solitaire.KlondikeRules.Companion.STOCK
import dev.sluide.games.solitaire.KlondikeRules.Companion.WASTE
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val DRAW_1 = KlondikeRules(Mode.KLONDIKE_DRAW_1)
private val DRAW_3 = KlondikeRules(Mode.KLONDIKE_DRAW_3)

/** A column from codes: the cards before `|` face down, after it face up; without `|` all face up. */
private fun column(spec: String): Pile {
    val parts = spec.split('|')
    if (parts.size == 1) return Pile.allFaceUp(cards(spec))
    val up = cards(parts[1])
    return Pile(cards(parts[0]) + up, up.size)
}

/** A Klondike table from codes, bottom to top; missing foundations and columns are empty. */
private fun klondike(
    mode: Mode = Mode.KLONDIKE_DRAW_1,
    stock: String = "",
    waste: String = "",
    fan: Int = if (waste.isEmpty()) 0 else 1,
    foundations: List<String> = emptyList(),
    columns: List<String> = emptyList(),
): Table {
    val piles =
        mapOf(STOCK to Pile(cards(stock)), WASTE to Pile.allFaceUp(cards(waste))) +
            FOUNDATIONS.mapIndexed { i, id -> id to Pile.allFaceUp(cards(foundations.getOrElse(i) { "" })) } +
            COLUMNS.mapIndexed { i, id -> id to column(columns.getOrElse(i) { "" }) }
    return Table(mode, piles, fan)
}

private fun pileId(
    kind: String,
    index: Int,
) = PileId(PileKind.valueOf(kind), index)

private fun Rules.play(
    table: Table,
    vararg moves: Move,
): Table = moves.fold(table) { t, move -> apply(t, move).table }

class KlondikeRulesTest {
    @Test
    fun `deals row by row, one face-up card per column, the rest to the stock`() {
        val deck = Deals.orderedDeck(Mode.KLONDIKE_DRAW_1)
        val table = DRAW_1.deal(deck)

        assertEquals(Mode.KLONDIKE_DRAW_1, table.mode)
        assertEquals(listOf(deck[0]), table.pile(COLUMNS[0]).cards)
        assertEquals(listOf(deck[1], deck[7]), table.pile(COLUMNS[1]).cards)
        assertEquals(listOf(6, 12, 17, 21, 24, 26, 27).map { deck[it] }, table.pile(COLUMNS[6]).cards)
        COLUMNS.forEachIndexed { i, id ->
            assertEquals(i + 1, table.pile(id).cards.size)
            assertEquals(1, table.pile(id).faceUp)
        }
        assertEquals(Pile(deck.subList(28, 52)), table.pile(STOCK))
        assertEquals(deck.last(), table.pile(STOCK).top)
        assertEquals(Pile.EMPTY, table.pile(WASTE))
        FOUNDATIONS.forEach { assertEquals(Pile.EMPTY, table.pile(it)) }
        assertEquals(0, table.fan)
        assertEquals(COLUMNS, table.ids(PileKind.TABLEAU))
        assertEquals(FOUNDATIONS, table.ids(PileKind.FOUNDATION))
        assertEquals(emptyList(), table.ids(PileKind.CELL))
    }

    @Test
    fun `refuses a deck that is not one full deck`() {
        val deck = Deals.orderedDeck(Mode.KLONDIKE_DRAW_1)
        assertThrows<IllegalArgumentException> { DRAW_1.deal(deck.drop(1)) }
        assertThrows<IllegalArgumentException> { DRAW_1.deal(deck.drop(1) + deck[1]) }
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `Rules of gives the rules of the mode, Klondike rules refuse the other variants`(mode: Mode) {
        val rules = Rules.of(mode)
        assertEquals(mode, rules.mode)
        when (mode.variant) {
            Variant.KLONDIKE -> assertIs<KlondikeRules>(rules)
            Variant.FREECELL -> assertIs<FreeCellRules>(rules)
            Variant.SPIDER -> assertIs<SpiderRules>(rules)
        }
        if (mode.variant != Variant.KLONDIKE) assertThrows<IllegalArgumentException> { KlondikeRules(mode) }
    }

    @ParameterizedTest
    @CsvSource(
        "'', 0",
        "KS QH |, 0",
        "KS QH, 2",
        "KS | 9H 8S 7H, 3",
        "| 9H 8H 7S, 2",
        "| 9H 7S, 1",
        "KS QH | JC, 1",
        "| KD QC JH 10S 9D 8C 7H 6S 5D 4C 3H 2S AD, 13",
    )
    fun `a column's run is its face-up top cards descending in alternating colours`(
        spec: String,
        run: Int,
    ) {
        assertEquals(run, DRAW_1.runLength(klondike(columns = listOf(spec)), COLUMNS[0]))
    }

    @Test
    fun `waste and foundations give their top card, the stock nothing`() {
        val table = klondike(stock = "2C 3C", waste = "5D 9H", foundations = listOf("AS 2S"))
        assertEquals(1, DRAW_1.runLength(table, WASTE))
        assertEquals(1, DRAW_1.runLength(table, FOUNDATIONS[0]))
        assertEquals(0, DRAW_1.runLength(table, FOUNDATIONS[1]))
        assertEquals(0, DRAW_1.runLength(table, STOCK))
        assertEquals(0, DRAW_1.runLength(klondike(), WASTE))
        assertFalse(DRAW_1.canMove(table, Move.Transfer(WASTE, COLUMNS[0], 2)))
        assertFalse(DRAW_1.canMove(table, Move.Transfer(STOCK, COLUMNS[0])))
    }

    @ParameterizedTest
    @CsvSource(
        "0, 1, 3, true", // 9H 8S 7H onto 10C
        "0, 1, 2, false", // 8S onto 10C
        "0, 1, 4, false", // more than the face-up run
        "0, 1, 0, false",
        "0, 4, 3, false", // 9H onto 10H: same colour
        "0, 5, 1, false", // 7H onto 8D: same colour
        "0, 6, 1, true", // 7H onto 8C
        "0, 0, 1, false",
        "3, 2, 2, true", // a King run onto an empty column
        "3, 2, 1, false", // QC onto an empty column
        "1, 2, 1, false", // 10C onto an empty column
        "4, 1, 1, false", // 10H onto 10C
    )
    fun `tableau to tableau builds down in alternating colours, Kings onto empty columns`(
        from: Int,
        to: Int,
        count: Int,
        legal: Boolean,
    ) {
        val table = klondike(columns = listOf("KS | 9H 8S 7H", "| 10C", "", "| KD QC", "| 10H", "| 8D", "QS | 8C"))
        assertEquals(legal, DRAW_1.canMove(table, Move.Transfer(COLUMNS[from], COLUMNS[to], count)))
    }

    @ParameterizedTest
    @CsvSource(
        "WASTE, 0, FOUNDATION, 1, true", // AH onto an empty foundation
        "WASTE, 0, FOUNDATION, 3, true",
        "TABLEAU, 0, FOUNDATION, 0, true", // 2S onto AS
        "TABLEAU, 1, FOUNDATION, 0, false", // 3S onto AS
        "TABLEAU, 2, FOUNDATION, 0, false", // 2C onto AS
        "TABLEAU, 2, FOUNDATION, 1, false", // 2C onto an empty foundation
        "TABLEAU, 3, FOUNDATION, 1, false", // a face-down Ace
        "FOUNDATION, 0, FOUNDATION, 1, false",
        "FOUNDATION, 0, TABLEAU, 4, true", // AS onto 2D
        "FOUNDATION, 0, TABLEAU, 2, false", // AS onto 2C
        "FOUNDATION, 1, TABLEAU, 4, false", // from an empty foundation
        "WASTE, 0, STOCK, 0, false",
        "TABLEAU, 0, WASTE, 0, false",
    )
    fun `foundations take an Ace when empty and build up in suit`(
        fromKind: String,
        from: Int,
        toKind: String,
        to: Int,
        legal: Boolean,
    ) {
        val table =
            klondike(
                waste = "5C AH",
                foundations = listOf("AS"),
                columns = listOf("| 2S", "| 3S", "| 2C", "AD |", "| 2D"),
            )
        assertEquals(legal, DRAW_1.canMove(table, Move.Transfer(pileId(fromKind, from), pileId(toKind, to))))
    }

    @Test
    fun `a run goes to a foundation one card at a time only`() {
        val table = klondike(foundations = listOf("AH"), columns = listOf("| 3S 2H"))
        assertTrue(DRAW_1.canMove(table, Move.Transfer(COLUMNS[0], FOUNDATIONS[0])))
        assertFalse(DRAW_1.canMove(table, Move.Transfer(COLUMNS[0], FOUNDATIONS[0], 2)))
    }

    @Test
    fun `draw needs a stock, recycle an empty stock and a waste, and there is no row to deal`() {
        val full = klondike(stock = "AC")
        assertTrue(DRAW_1.canMove(full, Move.Draw))
        assertFalse(DRAW_1.canMove(full, Move.Recycle))
        val drawn = klondike(waste = "AC")
        assertFalse(DRAW_1.canMove(drawn, Move.Draw))
        assertTrue(DRAW_1.canMove(drawn, Move.Recycle))
        assertFalse(DRAW_1.canMove(klondike(), Move.Recycle))
        assertFalse(DRAW_1.canMove(full, Move.DealRow))
    }

    @Test
    fun `an illegal move is refused, as is a table of another mode`() {
        val table = klondike(columns = listOf("| 10H", "| 10C"))
        assertThrows<IllegalArgumentException> { DRAW_1.apply(table, Move.Transfer(COLUMNS[0], COLUMNS[1])) }
        assertThrows<IllegalArgumentException> { DRAW_1.apply(table, Move.Draw) }
        val drawThree = klondike(mode = Mode.KLONDIKE_DRAW_3, stock = "AC")
        assertFalse(DRAW_1.canMove(drawThree, Move.Draw))
        assertEquals(emptyList(), DRAW_1.legalMoves(drawThree))
        assertThrows<IllegalArgumentException> { DRAW_1.apply(drawThree, Move.Draw) }
    }

    @Test
    fun `lists legal moves in a stable order, foundation moves first`() {
        val table =
            klondike(
                waste = "AH",
                foundations = listOf("", "", "AD", "AC 2C"),
                columns = listOf("| AS", "", "| KD QS", "| 3H", "| 5H", "| 5D", "| 9H"),
            )
        val expected =
            listOf(
                Move.Transfer(WASTE, FOUNDATIONS[0]),
                Move.Transfer(WASTE, FOUNDATIONS[1]),
                Move.Transfer(COLUMNS[0], FOUNDATIONS[0]),
                Move.Transfer(COLUMNS[0], FOUNDATIONS[1]),
                Move.Transfer(COLUMNS[2], COLUMNS[1], 2),
                Move.Transfer(FOUNDATIONS[3], COLUMNS[3]),
                Move.Recycle,
            )
        assertEquals(expected, DRAW_1.legalMoves(table))
    }

    @Test
    fun `a King run lists every empty column`() {
        val table = klondike(stock = "2C", columns = listOf("| KH QS", "", "| 5D", "", "| 5D", "", "| 5D"))
        val expected =
            listOf(1, 3, 5).map { Move.Transfer(COLUMNS[0], COLUMNS[it], 2) } + Move.Draw
        assertEquals(expected, DRAW_1.legalMoves(table))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5])
    fun `legal moves are exactly the moves canMove allows, without duplicates`(seed: Int) {
        val random = Random(seed)
        val candidates =
            run {
                val ids = listOf(STOCK, WASTE) + FOUNDATIONS + COLUMNS
                ids.flatMap { from -> ids.flatMap { to -> (0..14).map { Move.Transfer(from, to, it) } } } +
                    listOf(Move.Draw, Move.Recycle, Move.DealRow)
            }
        listOf(DRAW_1, DRAW_3).forEach { rules ->
            var table = rules.deal(Deals.deck(rules.mode, seed))
            repeat(150) {
                val moves = rules.legalMoves(table)
                assertEquals(moves.distinct(), moves)
                assertEquals(candidates.filter { rules.canMove(table, it) }.toSet(), moves.toSet())
                if (moves.isEmpty()) return@forEach
                table = rules.apply(table, moves[random.nextInt(moves.size)]).table
            }
        }
    }

    @Test
    fun `uncovering a face-down card turns it face up and scores it`() {
        val table = klondike(foundations = listOf("AH"), columns = listOf("KS | 2H", "QD JC | 10D", "| JS"))
        val toFoundation = DRAW_1.apply(table, Move.Transfer(COLUMNS[0], FOUNDATIONS[0]))
        assertEquals(Pile(cards("KS"), 1), toFoundation.table.pile(COLUMNS[0]))
        assertEquals(listOf(COLUMNS[0]), toFoundation.flipped)
        assertEquals(15, toFoundation.points)
        val toColumn = DRAW_1.apply(table, Move.Transfer(COLUMNS[1], COLUMNS[2]))
        assertEquals(Pile(cards("QD JC"), 1), toColumn.table.pile(COLUMNS[1]))
        assertEquals(Pile.allFaceUp(cards("JS 10D")), toColumn.table.pile(COLUMNS[2]))
        assertEquals(listOf(COLUMNS[1]), toColumn.flipped)
        assertEquals(5, toColumn.points)
        assertEquals(emptyList(), toColumn.completedRuns)
    }

    @Test
    fun `moving a run off face-up cards turns nothing`() {
        val table = klondike(columns = listOf("KS | 9H 8S 7H", "| 9D", "| KD QC"))
        val moved = DRAW_1.apply(table, Move.Transfer(COLUMNS[0], COLUMNS[1], 2))
        assertEquals(Pile(cards("KS 9H"), 1), moved.table.pile(COLUMNS[0]))
        assertEquals(Pile.allFaceUp(cards("9D 8S 7H")), moved.table.pile(COLUMNS[1]))
        assertEquals(emptyList(), moved.flipped)
        val emptied = DRAW_1.apply(table, Move.Transfer(COLUMNS[2], COLUMNS[3], 2))
        assertEquals(Pile.EMPTY, emptied.table.pile(COLUMNS[2]))
        assertEquals(emptyList(), emptied.flipped)
    }

    @ParameterizedTest
    @CsvSource(
        "WASTE, 0, TABLEAU, 0, 5",
        "WASTE, 0, FOUNDATION, 0, 10",
        "TABLEAU, 1, FOUNDATION, 2, 10",
        "FOUNDATION, 1, TABLEAU, 2, -15",
        "TABLEAU, 3, TABLEAU, 4, 0",
    )
    fun `scores a transfer by where it goes`(
        fromKind: String,
        from: Int,
        toKind: String,
        to: Int,
        points: Int,
    ) {
        val table =
            klondike(
                waste = "9H 2S",
                foundations = listOf("AS", "AD 2D 3D 4D 5D 6D"),
                columns = listOf("| 3H", "| AC", "| 7S", "| 8H", "| 9C"),
            )
        val outcome = DRAW_1.apply(table, Move.Transfer(pileId(fromKind, from), pileId(toKind, to)))
        assertEquals(points, outcome.points)
        assertEquals(emptyList(), outcome.flipped)
    }

    @ParameterizedTest
    @CsvSource("KLONDIKE_DRAW_1, -100", "KLONDIKE_DRAW_3, -20")
    fun `drawing scores nothing, recycling costs by the draw count and restores the stock's order`(
        mode: Mode,
        recyclePoints: Int,
    ) {
        val rules = KlondikeRules(mode)
        var table = klondike(mode = mode, stock = "AC 2C 3C 4C 5C 6C 7C")
        val stock = table.pile(STOCK)
        while (rules.canMove(table, Move.Draw)) {
            val drawn = rules.apply(table, Move.Draw)
            assertEquals(0, drawn.points)
            table = drawn.table
        }
        assertEquals(Pile.allFaceUp(cards("7C 6C 5C 4C 3C 2C AC")), table.pile(WASTE))
        val recycled = rules.apply(table, Move.Recycle)
        assertEquals(recyclePoints, recycled.points)
        assertEquals(stock, recycled.table.pile(STOCK))
        assertEquals(Pile.EMPTY, recycled.table.pile(WASTE))
        assertEquals(0, recycled.table.fan)
    }

    @Test
    fun `draw 3 turns three cards, fewer when fewer are left, and fans what it turned`() {
        val columns = listOf("| 4H", "| 5D", "| 6H", "", "| 10S", "| 9D")
        val table = klondike(mode = Mode.KLONDIKE_DRAW_3, stock = "AD KH 3C 4C 5C", columns = columns)

        val three = DRAW_3.apply(table, Move.Draw).table
        assertEquals(Pile(cards("AD KH")), three.pile(STOCK))
        assertEquals(Pile.allFaceUp(cards("5C 4C 3C")), three.pile(WASTE))
        assertEquals(3, three.fan)
        val two = DRAW_3.play(three, Move.Transfer(WASTE, COLUMNS[0]))
        assertEquals(2, two.fan)
        val one = DRAW_3.play(two, Move.Transfer(WASTE, COLUMNS[1]))
        assertEquals(1, one.fan)
        assertEquals(1, DRAW_3.play(one, Move.Transfer(COLUMNS[5], COLUMNS[4])).fan, "a tableau move keeps the fan")

        val fewer = DRAW_3.apply(one, Move.Draw).table
        assertEquals(Pile.allFaceUp(cards("5C KH AD")), fewer.pile(WASTE))
        assertEquals(2, fewer.fan)
        val ace = DRAW_3.play(fewer, Move.Transfer(WASTE, FOUNDATIONS[0]))
        assertEquals(1, ace.fan)
        val king = DRAW_3.play(ace, Move.Transfer(WASTE, COLUMNS[3]))
        assertEquals(1, king.fan, "the next card shows while the waste is not empty")
        val empty = DRAW_3.play(king, Move.Transfer(WASTE, COLUMNS[2]))
        assertEquals(Pile.EMPTY, empty.pile(WASTE))
        assertEquals(0, empty.fan)
    }

    @Test
    fun `draw 1 turns one card and fans one while the waste is not empty`() {
        val table = klondike(stock = "4C 5C", columns = listOf("| 5D", "| 6H"))
        val first = DRAW_1.apply(table, Move.Draw).table
        assertEquals(Pile.allFaceUp(cards("5C")), first.pile(WASTE))
        assertEquals(1, first.fan)
        val second = DRAW_1.play(first, Move.Draw)
        assertEquals(Pile.allFaceUp(cards("5C 4C")), second.pile(WASTE))
        assertEquals(1, second.fan)
        val played = DRAW_1.play(second, Move.Transfer(WASTE, COLUMNS[0]))
        assertEquals(1, played.fan)
        val empty = DRAW_1.play(played, Move.Transfer(WASTE, COLUMNS[1]))
        assertEquals(0, empty.fan)
    }

    @Test
    fun `won with all 52 cards on the foundations`() {
        val won = wonTable()
        assertTrue(DRAW_1.isWon(won))
        assertTrue(DRAW_1.isDecided(won))
        val notYet = DRAW_1.play(won, Move.Transfer(FOUNDATIONS[0], COLUMNS[0]))
        assertFalse(DRAW_1.isWon(notYet))
        assertFalse(DRAW_1.isWon(DRAW_1.deal(Deals.deck(Mode.KLONDIKE_DRAW_1, 1))))
    }

    @ParameterizedTest
    @CsvSource(
        "AC, '', '| KH', false", // a stock
        "'', AC, '| KH', false", // a waste
        "'', '', 'AC | KH', false", // a face-down card
        "'', '', '| KH', true",
    )
    fun `decided once stock and waste are empty and every card is face up`(
        stock: String,
        waste: String,
        column: String,
        decided: Boolean,
    ) {
        assertEquals(decided, DRAW_1.isDecided(klondike(stock = stock, waste = waste, columns = listOf(column))))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8])
    fun `a decided table is won by foundation moves alone`(seed: Int) {
        // Builds a decided table by playing back from a won one: random legal moves onto columns.
        val random = Random(seed)
        var table = wonTable()
        repeat(20 + 10 * seed) {
            val back = DRAW_1.legalMoves(table).filter { it is Move.Transfer && it.to.kind == PileKind.TABLEAU }
            if (back.isNotEmpty()) table = DRAW_1.apply(table, back[random.nextInt(back.size)]).table
        }
        assertTrue(DRAW_1.isDecided(table))
        assertFalse(DRAW_1.isWon(table))
        repeat(52) {
            if (!DRAW_1.isWon(table)) {
                val home =
                    assertNotNull(
                        DRAW_1.legalMoves(table).firstOrNull {
                            it is Move.Transfer && it.to.kind == PileKind.FOUNDATION
                        },
                    )
                table = DRAW_1.apply(table, home).table
            }
        }
        assertTrue(DRAW_1.isWon(table))
    }

    private fun wonTable(): Table =
        klondike(foundations = Suit.entries.map { suit -> codes((1..13).map { Card(it, suit) }) })
}
