package dev.sluide.games.solitaire.solver

import dev.sluide.games.solitaire.Deals
import dev.sluide.games.solitaire.FreeCellRules
import dev.sluide.games.solitaire.KlondikeRules
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.Pile
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.Rules
import dev.sluide.games.solitaire.SpiderRules
import dev.sluide.games.solitaire.Table
import dev.sluide.games.solitaire.Variant
import dev.sluide.games.solitaire.card
import dev.sluide.games.solitaire.cards
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SolverTest {
    private val solver = Solver()
    private val columns = KlondikeRules.COLUMNS
    private val foundations = KlondikeRules.FOUNDATIONS
    private val allSuits =
        "AC 2C 3C 4C 5C 6C 7C 8C 9C 10C JC QC KC AD 2D 3D 4D 5D 6D 7D 8D 9D 10D JD QD KD " +
            "AH 2H 3H 4H 5H 6H 7H 8H 9H 10H JH QH KH"

    /** Clubs, diamonds and hearts complete, spades up to [spades]; [piles] are the columns' piles from the left. */
    private fun table(
        spades: String,
        vararg piles: Pile,
        mode: Mode = Mode.KLONDIKE_DRAW_1,
    ): Table {
        val suitCards = (allSuits.split(' ')).chunked(13)
        val spadeCards = cards(spades)
        val foundationPiles = suitCards.map { Pile.allFaceUp(cards(it.joinToString(" "))) } + Pile.allFaceUp(spadeCards)
        val map = HashMap<PileId, Pile>()
        foundations.forEachIndexed { i, id -> map[id] = foundationPiles[i] }
        columns.forEachIndexed { i, id -> map[id] = piles.getOrElse(i) { Pile.EMPTY } }
        map[KlondikeRules.STOCK] = Pile.EMPTY
        map[KlondikeRules.WASTE] = Pile.EMPTY
        return Table(mode, map)
    }

    private fun replayedToWin(
        start: Table,
        moves: List<Move>,
    ) {
        val rules = Rules.of(start.mode)
        var current = start
        for (move in moves) {
            assertEquals(true, rules.canMove(current, move), "illegal $move")
            current = rules.apply(current, move).table
        }
        assertEquals(true, rules.isWon(current))
    }

    @Test
    fun `an almost won table is solved with the one move that is left`() {
        val table = table("AS 2S 3S 4S 5S 6S 7S 8S 9S 10S JS QS", Pile(cards("KS"), 1))

        val result = solver.solve(table, 5.seconds)

        assertEquals(SolveResult.Solved(listOf(Move.Transfer(columns[0], foundations[3]))), result)
    }

    @Test
    fun `a King has to make room for the card under it`() {
        val table = table("AS 2S 3S 4S 5S 6S 7S 8S 9S 10S JS", Pile(cards("QS KS"), 1))

        val result = solver.solve(table, 5.seconds)

        val expected =
            listOf(
                Move.Transfer(columns[0], columns[1]),
                Move.Transfer(columns[0], foundations[3]),
                Move.Transfer(columns[1], foundations[3]),
            )
        assertEquals(SolveResult.Solved(expected), result)
        replayedToWin(table, expected)
    }

    @Test
    fun `a full deck with no move left at all is unsolvable`() {
        val tops = cards("KC KS QC QS JC JS 10C")
        val buried = Deals.orderedDeck(Mode.KLONDIKE_DRAW_1) - tops.toSet()
        val piles =
            columns.mapIndexed { i, id ->
                id to Pile(buried.filterIndexed { k, _ -> k % columns.size == i } + tops[i], 1)
            } + foundations.map { it to Pile.EMPTY } + (KlondikeRules.STOCK to Pile.EMPTY) +
                (KlondikeRules.WASTE to Pile.EMPTY)
        val table = Table(Mode.KLONDIKE_DRAW_1, piles.toMap())

        assertEquals(SolveResult.Unsolvable, solver.solve(table, 5.seconds))
    }

    /**
     * Winnable only by taking the 3 of spades back from its foundation onto the 4 of hearts, so that the 2 of diamonds
     * can uncover the Ace of diamonds. The 4 of hearts fits its foundation but is not safe to play: the first pass
     * (no cards back from foundations) cannot win it, only the second one can.
     */
    @ParameterizedTest
    @EnumSource(Mode::class, names = ["KLONDIKE_DRAW_1", "KLONDIKE_DRAW_3"])
    fun `a card needed as a base for one taken back from a foundation is not played up`(mode: Mode) {
        val rest =
            cards("3D 4S 4C 4D") +
                (5..13).flatMap { rank -> listOf("H", "S", "C", "D").map { suit -> card(RANK_CODES[rank - 1] + suit) } }
        val columnZero = rest.take(20).reversed() + cards("AD 2D")
        val columnTwo = rest.drop(20).reversed()
        val piles =
            mapOf(
                foundations[0] to Pile.allFaceUp(cards("AC 2C 3C")),
                foundations[1] to Pile.EMPTY,
                foundations[2] to Pile.allFaceUp(cards("AH 2H 3H")),
                foundations[3] to Pile.allFaceUp(cards("AS 2S 3S")),
                columns[0] to Pile(columnZero, 1),
                columns[1] to Pile(cards("4H"), 1),
                columns[2] to Pile(columnTwo, 1),
                KlondikeRules.STOCK to Pile.EMPTY,
                KlondikeRules.WASTE to Pile.EMPTY,
            )
        val table = Table(mode, piles + columns.drop(3).associateWith { Pile.EMPTY })

        val result = solver.solve(table, 20.seconds)

        assertIs<SolveResult.Solved>(result)
        replayedToWin(table, result.moves)
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `a spent budget gives Unknown at once`(mode: Mode) {
        var now = 0L
        val ticking =
            Solver {
                now += 1_000_000_000L
                now
            }
        val table = Rules.of(mode).deal(Deals.deck(mode, 3))

        assertEquals(SolveResult.Unknown, ticking.solve(table, 1.seconds))
        assertEquals(2_000_000_000L, now, "the clock was read only to set and to check the deadline")
    }

    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `a cancel gives Unknown at once`(mode: Mode) {
        val table = Rules.of(mode).deal(Deals.deck(mode, 3))
        var polls = 0

        val result =
            solver.solve(table, 60.seconds, cancelled = {
                polls++
                true
            })

        assertEquals(SolveResult.Unknown, result)
        assertEquals(1, polls)
    }

    @Test
    fun `a real deal whose search is exhausted is unsolvable`() {
        val mode = Mode.KLONDIKE_DRAW_3
        val table = Rules.of(mode).deal(Deals.deck(mode, 6))

        assertEquals(SolveResult.Unsolvable, solver.solve(table, 20.seconds))
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["FREECELL", "SPIDER_1", "SPIDER_2", "SPIDER_4"])
    fun `a table without cards is no position`(mode: Mode) {
        assertEquals(SolveResult.Unknown, solver.solve(Table(mode, emptyMap()), 1.seconds))
    }

    @ParameterizedTest
    @CsvSource(
        "KLONDIKE_DRAW_1, 1",
        "KLONDIKE_DRAW_1, 2",
        "KLONDIKE_DRAW_1, 5",
        "KLONDIKE_DRAW_3, 1",
        "KLONDIKE_DRAW_3, 7",
        "KLONDIKE_DRAW_3, 3",
        "FREECELL, 1",
        "FREECELL, 2",
        "FREECELL, 3",
        "SPIDER_1, 1",
        "SPIDER_1, 2",
        "SPIDER_2, 1",
        "SPIDER_2, 2",
        "SPIDER_4, 1",
        "SPIDER_4, 2",
    )
    fun `real deals are solved by a line the rules replay to a win`(
        mode: Mode,
        number: Int,
    ) {
        val table = Rules.of(mode).deal(Deals.deck(mode, number))

        val result = solver.solve(table, 20.seconds)

        assertIs<SolveResult.Solved>(result)
        replayedToWin(table, result.moves)
    }

    /**
     * Positions from real play: the first moves of a deal's solution. Spider plays on past the first DealRow, so the
     * stock is part dealt and face-down cards lie under face-up runs.
     */
    @ParameterizedTest
    @CsvSource(
        "KLONDIKE_DRAW_1, 1",
        "KLONDIKE_DRAW_3, 1",
        "FREECELL, 1",
        "SPIDER_1, 1",
        "SPIDER_2, 1",
        "SPIDER_4, 2",
    )
    fun `a position in the middle of a game is solved from there`(
        mode: Mode,
        number: Int,
    ) {
        val rules = Rules.of(mode)
        val start = rules.deal(Deals.deck(mode, number))
        val first = assertIs<SolveResult.Solved>(solver.solve(start, 20.seconds))
        val played =
            if (mode.variant == Variant.SPIDER) first.moves.indexOf(Move.DealRow) + 1 else first.moves.size / 2
        val middle = first.moves.take(played).fold(start) { t, m -> rules.apply(t, m).table }
        assertTrue(played > 0 && middle != start)
        if (mode.variant == Variant.SPIDER) {
            assertTrue(middle.pile(SpiderRules.STOCK).cards.size < start.pile(SpiderRules.STOCK).cards.size)
            assertTrue(SpiderRules.COLUMNS.any { middle.pile(it).faceDown > 0 && middle.pile(it).faceUp > 1 })
        }

        val rest = assertIs<SolveResult.Solved>(solver.solve(middle, 20.seconds))

        replayedToWin(middle, rest.moves)
    }

    /** FreeCell with clubs, diamonds and hearts complete, spades up to [spades]; [columns] from the left, [cells] too. */
    private fun freeCellTable(
        spades: String,
        vararg columns: List<String>,
        cells: List<String> = emptyList(),
    ): Table {
        val suitCards = allSuits.split(' ').chunked(13)
        val foundationPiles =
            suitCards.map { Pile.allFaceUp(cards(it.joinToString(" "))) } + Pile.allFaceUp(cards(spades))
        val map = HashMap<PileId, Pile>()
        FreeCellRules.FOUNDATIONS.forEachIndexed { i, id -> map[id] = foundationPiles[i] }
        FreeCellRules.COLUMNS.forEachIndexed { i, id ->
            map[id] = columns.getOrNull(i)?.let { Pile.allFaceUp(cards(it.joinToString(" "))) } ?: Pile.EMPTY
        }
        FreeCellRules.CELLS.forEachIndexed { i, id ->
            map[id] = cells.getOrNull(i)?.let { Pile.allFaceUp(cards(it)) } ?: Pile.EMPTY
        }
        return Table(Mode.FREECELL, map)
    }

    @Test
    fun `an almost won FreeCell table is solved with the one move that is left`() {
        val table = freeCellTable("AS 2S 3S 4S 5S 6S 7S 8S 9S 10S JS QS", listOf("KS"))

        val result = solver.solve(table, 5.seconds)

        val move = Move.Transfer(FreeCellRules.COLUMNS[0], FreeCellRules.FOUNDATIONS[3])
        assertEquals(SolveResult.Solved(listOf(move)), result)
    }

    @Test
    fun `a FreeCell King has to make room for the card under it`() {
        val table = freeCellTable("AS 2S 3S 4S 5S 6S 7S 8S 9S 10S JS", listOf("QS", "KS"))

        val result = solver.solve(table, 5.seconds)

        val solved = assertIs<SolveResult.Solved>(result)
        assertEquals(3, solved.moves.size)
        replayedToWin(table, solved.moves)
    }

    /**
     * Found by search: with the four cells full, a card that fits its foundation but is not safe (a card of the other
     * colour still needs it as a base) must stay down. A search that played every fitting card up at once would
     * run out of room here and prove the position lost, so the safety rule of [FreeCellSpace] is checked by the win.
     */
    @Test
    fun `a FreeCell card that is still needed as a base is not played up at once`() {
        val foundations = listOf(7, 11, 9, 6)
        val columns =
            listOf("9S JH", "KC", "KS", "QH 10C", "QD 8C 10S", "8S", "7S 9C 10H", "QS JS")
        val piles = HashMap<PileId, Pile>()
        FreeCellRules.FOUNDATIONS.forEachIndexed { i, id ->
            piles[id] = Pile.allFaceUp(cards(RANK_CODES.take(foundations[i]).joinToString(" ") { it + "CDHS"[i] }))
        }
        FreeCellRules.COLUMNS.forEachIndexed { i, id -> piles[id] = Pile.allFaceUp(cards(columns[i])) }
        cards("KD JC KH QC").forEachIndexed { i, card -> piles[FreeCellRules.CELLS[i]] = Pile.allFaceUp(listOf(card)) }
        val table = Table(Mode.FREECELL, piles)

        val result = solver.solve(table, 20.seconds)

        val solved = assertIs<SolveResult.Solved>(result)
        replayedToWin(table, solved.moves)
    }

    @Test
    fun `a full FreeCell table with no move left is unsolvable`() {
        val kings = cards("KC KD KH KS")
        val tops = cards("QC QS JC JS 10C 10S 9C 9S")
        val buried = Deals.orderedDeck(Mode.FREECELL) - kings.toSet() - tops.toSet()
        val piles =
            FreeCellRules.COLUMNS.mapIndexed { i, id ->
                id to Pile.allFaceUp(buried.filterIndexed { k, _ -> k % 8 == i } + tops[i])
            } + FreeCellRules.CELLS.mapIndexed { i, id -> id to Pile.allFaceUp(listOf(kings[i])) } +
                FreeCellRules.FOUNDATIONS.map { it to Pile.EMPTY }
        val table = Table(Mode.FREECELL, piles.toMap())

        assertEquals(SolveResult.Unsolvable, solver.solve(table, 5.seconds))
    }

    /** Seven runs complete; the last one is a King-to-3 run in one column with the 2 and the Ace on their own. */
    private fun spiderTable(mode: Mode): Table {
        val run = cards("KS QS JS 10S 9S 8S 7S 6S 5S 4S 3S")
        val map = HashMap<PileId, Pile>()
        SpiderRules.FOUNDATIONS.forEachIndexed { i, id ->
            map[id] = if (i < 7) Pile.allFaceUp(run + cards("2S AS")) else Pile.EMPTY
        }
        SpiderRules.COLUMNS.forEachIndexed { i, id ->
            map[id] =
                when (i) {
                    0 -> Pile.allFaceUp(run)
                    1 -> Pile.allFaceUp(cards("2S"))
                    2 -> Pile.allFaceUp(cards("AS"))
                    else -> Pile.EMPTY
                }
        }
        map[SpiderRules.STOCK] = Pile.EMPTY
        return Table(mode, map)
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["SPIDER_1", "SPIDER_2", "SPIDER_4"])
    fun `an almost won Spider table is solved with the two moves it takes`(mode: Mode) {
        val table = spiderTable(mode)

        val result = solver.solve(table, 5.seconds)

        val solved = assertIs<SolveResult.Solved>(result)
        assertEquals(2, solved.moves.size)
        replayedToWin(table, solved.moves)
    }

    @Test
    fun `a Spider table with no move left is unsolvable`() {
        // Not a real deck: ten Kings can neither move nor be dealt on, which is all the search needs to see.
        val king = Pile.allFaceUp(cards("KS"))
        val piles = SpiderRules.COLUMNS.associateWith { king } + (SpiderRules.STOCK to Pile.EMPTY)

        assertEquals(SolveResult.Unsolvable, solver.solve(Table(Mode.SPIDER_4, piles), 5.seconds))
    }

    @ParameterizedTest
    @EnumSource(Mode::class, names = ["FREECELL", "SPIDER_1", "SPIDER_2"])
    fun `solving the same deals on several threads gives what one thread gives`(mode: Mode) {
        val tables = (1..4).map { Rules.of(mode).deal(Deals.deck(mode, it)) }
        val alone = tables.map { solver.solve(it, 20.seconds) }
        val pool = Executors.newFixedThreadPool(3)
        try {
            val jobs = (1..3).flatMap { tables.map { table -> Callable { solver.solve(table, 60.seconds) } } }

            val results = pool.invokeAll(jobs).map { it.get() }

            assertEquals(jobs.indices.map { alone[it % tables.size] }, results)
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        val RANK_CODES = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
    }
}
