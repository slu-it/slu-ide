package dev.sluide.games.solitaire.solver

import dev.sluide.games.solitaire.Deals
import dev.sluide.games.solitaire.KlondikeRules
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.Pile
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.Rules
import dev.sluide.games.solitaire.Table
import dev.sluide.games.solitaire.card
import dev.sluide.games.solitaire.cards
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    @Test
    fun `a spent budget gives Unknown at once`() {
        var now = 0L
        val ticking =
            Solver {
                now += 1_000_000_000L
                now
            }
        val table = Rules.of(Mode.KLONDIKE_DRAW_1).deal(Deals.deck(Mode.KLONDIKE_DRAW_1, 3))

        assertEquals(SolveResult.Unknown, ticking.solve(table, 1.seconds))
        assertEquals(2_000_000_000L, now, "the clock was read only to set and to check the deadline")
    }

    @Test
    fun `a cancel gives Unknown at once`() {
        val table = Rules.of(Mode.KLONDIKE_DRAW_3).deal(Deals.deck(Mode.KLONDIKE_DRAW_3, 3))
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
    fun `the other games are not solved yet`(mode: Mode) {
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

    @Test
    fun `a position in the middle of a game is solved from there`() {
        val mode = Mode.KLONDIKE_DRAW_3
        val rules = Rules.of(mode)
        val start = rules.deal(Deals.deck(mode, DEAL_MID_GAME))
        val first = assertIs<SolveResult.Solved>(solver.solve(start, 20.seconds))
        val middle = first.moves.take(first.moves.size / 2).fold(start) { t, m -> rules.apply(t, m).table }

        val rest = assertIs<SolveResult.Solved>(solver.solve(middle, 20.seconds))

        replayedToWin(middle, rest.moves)
    }

    private companion object {
        val RANK_CODES = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
        const val DEAL_MID_GAME = 1
    }
}
