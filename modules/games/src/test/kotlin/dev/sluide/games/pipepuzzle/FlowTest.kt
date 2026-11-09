package dev.sluide.games.pipepuzzle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlowTest {
    private val source = Pos(0, 0)
    private val drain = Pos(2, 2)

    /** One snake through every cell, from the source at the top left to the drain at the bottom right. */
    private val snake =
        board(
            "╶─┐",
            "┌─┘",
            "└─╴",
        )

    @Test
    fun `a snake through every cell fills the board without leaks and solves both modes`() {
        val flow = Flow(3, snake, source)

        assertEquals(9, flow.filled.size)
        assertTrue(flow.leaks.isEmpty())
        assertTrue(flow.isSolved(Mode.NETWORK, drain))
        assertTrue(flow.isSolved(Mode.PATH, drain))
        assertEquals(9, flow.layers.size)
        assertEquals(listOf(source), flow.layers[0])
    }

    @Test
    fun `a turned tile stops the flow and leaves a leak at the opening it no longer meets`() {
        val tiles = snake.toMutableList().also { it[Pos(1, 1).index(3)] = Openings.of(Dir.N, Dir.S) }

        val flow = Flow(3, tiles, source)

        assertEquals(setOf(Pos(0, 0), Pos(0, 1), Pos(0, 2), Pos(1, 2)), flow.filled)
        assertEquals(listOf(Pos(1, 2) to Dir.W), flow.leaks)
        assertFalse(flow.isSolved(Mode.NETWORK, drain))
        assertFalse(flow.isSolved(Mode.PATH, drain))
    }

    @Test
    fun `an opening toward the board edge is a leak`() {
        val tiles = snake.toMutableList().also { it[Pos(0, 1).index(3)] = Openings.of(Dir.N, Dir.E, Dir.W) }

        val flow = Flow(3, tiles, source)

        assertEquals(9, flow.filled.size)
        assertEquals(listOf(Pos(0, 1) to Dir.N), flow.leaks)
        assertFalse(flow.isSolved(Mode.NETWORK, drain))
        assertFalse(flow.isSolved(Mode.PATH, drain))
    }

    /** A sealed route source → (0,1) → (1,1) → drain (1,2); every other tile is a decoy that meets nothing filled. */
    private val sealedRoute =
        board(
            "╶┐╷",
            "│└╴",
            "─┼╵",
        )

    @Test
    fun `a sealed route to the drain solves Path but not Network, whatever the unconnected decoys do`() {
        val flow = Flow(3, sealedRoute, source)

        assertEquals(setOf(Pos(0, 0), Pos(0, 1), Pos(1, 1), Pos(1, 2)), flow.filled)
        assertTrue(flow.leaks.isEmpty())
        assertTrue(flow.isSolved(Mode.PATH, Pos(1, 2)))
        assertFalse(flow.isSolved(Mode.NETWORK, Pos(1, 2)), "the decoys are not connected")
    }

    @Test
    fun `a route that reaches the drain but leaks into the decoys does not solve Path`() {
        val tiles = sealedRoute.toMutableList().also { it[Pos(1, 1).index(3)] = Openings.of(Dir.N, Dir.E, Dir.S) }

        val flow = Flow(3, tiles, source)

        assertTrue(Pos(1, 2) in flow.filled, "the drain is reached")
        assertEquals(
            setOf(Pos(2, 0) to Dir.W, Pos(2, 1) to Dir.E, Pos(2, 1) to Dir.S),
            flow.leaks.toSet(),
        )
        assertFalse(flow.isSolved(Mode.PATH, Pos(1, 2)))
        assertEquals(
            listOf(setOf(Pos(0, 0)), setOf(Pos(0, 1)), setOf(Pos(1, 1)), setOf(Pos(1, 2), Pos(2, 1)), setOf(Pos(2, 0))),
            flow.layers.map { it.toSet() },
        )
    }

    @Test
    fun `a route that stops short of the drain does not solve Path`() {
        val tiles = sealedRoute.toMutableList().also { it[Pos(1, 2).index(3)] = Openings.of(Dir.S) }

        val flow = Flow(3, tiles, source)

        assertFalse(Pos(1, 2) in flow.filled)
        assertEquals(listOf(Pos(1, 1) to Dir.E), flow.leaks)
        assertFalse(flow.isSolved(Mode.PATH, Pos(1, 2)))
    }
}
