package dev.sluide.games.pipepuzzle

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipePuzzleGameTest {
    private val snake =
        board(
            "╶─┐",
            "┌─┘",
            "└─╴",
        )

    /** The snake with the straight at (0,1) and the corner at (1,2) turned wrong. */
    private val snakeStart =
        board(
            "╶│┐",
            "┌─┐",
            "└─╴",
        )

    private fun network(random: Random = Random(0)) =
        PipePuzzleGame(Puzzle(Mode.NETWORK, 3, Pos(0, 0), Pos(2, 2), snake, snakeStart), random)

    /** A Path route source (0,0) → (0,1) → drain (0,2); the second and third rows are decoys, one a cross. */
    private val route =
        board(
            "╶─╴",
            "┼└╷",
            "│┬╵",
        )

    private val routeStart =
        board(
            "╶│╴",
            "┼┌╴",
            "─┴╷",
        )

    private fun path(random: Random = Random(0)) =
        PipePuzzleGame(Puzzle(Mode.PATH, 3, Pos(0, 0), Pos(0, 2), route, routeStart), random)

    private fun PipePuzzleGame.tiles() = puzzle.cells().map { tile(it) }

    @Test
    fun `a turn rotates the tile clockwise or counter-clockwise and updates the flow`() {
        val game = network()
        assertEquals(setOf(Pos(0, 0)), game.filled)
        assertEquals(listOf(Pos(0, 0) to Dir.E), game.leaks)

        assertTrue(game.turn(Pos(1, 2), clockwise = true))
        assertEquals(Openings.of(Dir.N, Dir.W), game.tile(Pos(1, 2)))
        assertTrue(game.turn(Pos(1, 2), clockwise = false))
        assertEquals(Openings.of(Dir.S, Dir.W), game.tile(Pos(1, 2)))

        assertTrue(game.turn(Pos(0, 1), clockwise = false))
        assertEquals(Openings.of(Dir.E, Dir.W), game.tile(Pos(0, 1)))
        assertEquals(setOf(Pos(0, 0), Pos(0, 1), Pos(0, 2)), game.filled)
        assertEquals(listOf(Pos(0, 2) to Dir.S), game.leaks)
        assertFalse(game.isSolved)
    }

    @Test
    fun `fixed tiles, crosses and positions off the board do not turn`() {
        val game = path()
        val before = game.tiles()

        assertFalse(game.turn(Pos(0, 0), clockwise = true), "source")
        assertFalse(game.turn(Pos(0, 2), clockwise = false), "drain")
        assertFalse(game.turn(Pos(1, 0), clockwise = true), "cross")
        assertFalse(game.turn(Pos(3, 0), clockwise = true), "off the board")
        assertFalse(game.turn(Pos(0, -1), clockwise = true), "off the board")

        assertEquals(before, game.tiles())
        assertTrue(game.isFixed(Pos(0, 0)))
        assertTrue(game.isFixed(Pos(0, 2)))
        assertFalse(game.isFixed(Pos(1, 0)))
    }

    @Test
    fun `the last right turn solves the puzzle, and after that nothing turns`() {
        val game = network()
        game.turn(Pos(0, 1), clockwise = true)
        game.turn(Pos(1, 2), clockwise = true)

        assertTrue(game.isSolved)
        assertEquals(9, game.filled.size)
        assertFalse(game.turn(Pos(1, 1), clockwise = true))
        assertEquals(snake, game.tiles())
        assertNull(game.hint(), "nothing is wrong")
        assertEquals(0, game.hints)
    }

    @Test
    fun `a hint turns a wrong tile right, and a straight turned half way round already counts as right`() {
        val game = network()
        repeat(3) { game.turn(Pos(0, 1), clockwise = true) }
        assertEquals(snake[1], game.tile(Pos(0, 1)), "three quarter turns make │ a ─")

        assertEquals(Pos(1, 2), game.hint())
        assertEquals(snake[Pos(1, 2).index(3)], game.tile(Pos(1, 2)))
        assertEquals(1, game.hints)
        assertTrue(game.isSolved)
        assertNull(game.hint())
        assertEquals(1, game.hints)
    }

    @Test
    fun `hints pick among the wrong tiles at random and leave the right ones alone`() {
        val picks =
            (0 until 20).map { seed ->
                val game = network(Random(seed))
                val pick = game.hint()
                val expected =
                    snakeStart.toMutableList().also {
                        if (pick !=
                            null
                        ) {
                            it[pick.index(3)] = snake[pick.index(3)]
                        }
                    }
                assertEquals(expected, game.tiles(), "seed $seed: only the hinted tile turns")
                pick
            }

        assertEquals(setOf(Pos(0, 1), Pos(1, 2)), picks.toSet())
    }

    @Test
    fun `in Path mode a hint follows the generated route and never turns a decoy`() {
        repeat(20) { seed ->
            val game = path(Random(seed))

            assertEquals(Pos(0, 1), game.hint(), "seed $seed")
            assertTrue(game.isSolved)
            assertEquals(routeStart.drop(3), game.tiles().drop(3), "the decoys keep their turn")
        }
    }

    @Test
    fun `restart turns every tile back and clears the hints`() {
        val game = network()
        game.turn(Pos(1, 1), clockwise = true)
        repeat(3) { game.hint() }
        assertTrue(game.isSolved)
        assertEquals(3, game.hints)

        game.restart()

        assertEquals(snakeStart, game.tiles())
        assertEquals(0, game.hints)
        assertFalse(game.isSolved)
        assertTrue(game.turn(Pos(1, 1), clockwise = true), "turns work again")
    }

    @Test
    fun `fill order groups the filled tiles by their distance from the source`() {
        val game = network()
        assertEquals(listOf(listOf(Pos(0, 0))), game.fillOrder())

        game.turn(Pos(0, 1), clockwise = true)
        game.turn(Pos(1, 2), clockwise = true)

        assertEquals(
            listOf(Pos(0, 0), Pos(0, 1), Pos(0, 2), Pos(1, 2), Pos(1, 1), Pos(1, 0), Pos(2, 0), Pos(2, 1), Pos(2, 2))
                .map { listOf(it) },
            game.fillOrder(),
        )
    }
}
