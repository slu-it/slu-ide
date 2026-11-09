package dev.sluide.games.pipepuzzle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NetworkSolverTest {
    /** A board that edge agreement and closed borders alone leave stuck; only the loop rule finishes it. */
    private val needsLoops =
        board(
            "╶┐╷╷",
            "┌┘└┤",
            "└┬┬┤",
            "╶┘╵╵",
        )

    /** A board that edge agreement, borders and the loop rule leave stuck; only the closed-part rule finishes it. */
    private val needsClosedParts =
        board(
            "╷╷┌╴",
            "└┴┴┐",
            "╶┬┬┘",
            "╶┘└╴",
        )

    private fun scrambled(tiles: List<Openings>) = tiles.map { it.rotatedCw() }

    @Test
    fun `a board with one solution is solved from any turn`() {
        val result = NetworkSolver.solve(4, scrambled(needsLoops), emptySet())

        assertEquals(NetworkSolver.Result.Solved(needsLoops), result)
        assertEquals(1, countNetworkSolutions(4, needsLoops))
    }

    @Test
    fun `the loop rule is needed for a board`() {
        val withoutLoops = NetworkSolver.solve(4, scrambled(needsLoops), emptySet(), noLoops = false)

        assertIs<NetworkSolver.Result.Stuck>(withoutLoops)
        val withLoops = NetworkSolver.solve(4, scrambled(needsLoops), emptySet())
        assertEquals(NetworkSolver.Result.Solved(needsLoops), withLoops)
    }

    @Test
    fun `the closed-part rule is needed for a board`() {
        val withoutClosedParts =
            NetworkSolver.solve(4, scrambled(needsClosedParts), emptySet(), noClosedParts = false)

        assertIs<NetworkSolver.Result.Stuck>(withoutClosedParts)
        assertEquals(1, countNetworkSolutions(4, needsClosedParts))
        assertEquals(
            NetworkSolver.Result.Solved(needsClosedParts),
            NetworkSolver.solve(4, scrambled(needsClosedParts), emptySet()),
        )
    }

    @Test
    fun `a board with two solutions is stuck at the cells that differ`() {
        val tiles =
            board(
                "╷┌╴",
                "└┼┐",
                "╶┘╵",
            )

        val result = NetworkSolver.solve(3, tiles, emptySet())

        assertEquals(2, countNetworkSolutions(3, tiles))
        val stuck = assertIs<NetworkSolver.Result.Stuck>(result)
        assertTrue(stuck.ambiguous.isNotEmpty())
        assertFalse(Pos(1, 1) in stuck.ambiguous, "a cross has only one orientation")
    }

    @Test
    fun `a fixed tile decides an otherwise ambiguous board`() {
        val tiles =
            board(
                "╷┌╴",
                "└┼┐",
                "╶┘╵",
            )

        val result = NetworkSolver.solve(3, tiles, setOf(Pos(0, 0)))

        assertEquals(1, countNetworkSolutions(3, tiles, setOf(Pos(0, 0))))
        assertEquals(NetworkSolver.Result.Solved(tiles), result)
    }

    @Test
    fun `a straight in a corner is a contradiction`() {
        val tiles =
            board(
                "│╷",
                "└┘",
            )

        assertEquals(NetworkSolver.Result.Contradiction, NetworkSolver.solve(2, tiles, emptySet()))
    }

    @Test
    fun `four ends can only pair up into closed-off parts, a contradiction`() {
        val tiles =
            board(
                "╶╴",
                "╶╴",
            )

        assertEquals(NetworkSolver.Result.Contradiction, NetworkSolver.solve(2, tiles, emptySet()))
        assertIs<NetworkSolver.Result.Stuck>(NetworkSolver.solve(2, tiles, emptySet(), noClosedParts = false))
    }

    @Test
    fun `a fixed tile turned the wrong way is a contradiction`() {
        val tiles =
            board(
                "╶┐",
                "╶┘",
            )

        val turned = tiles.toMutableList().also { it[0] = Openings.of(Dir.S) }

        val result = NetworkSolver.solve(2, turned, setOf(Pos(0, 0)))

        assertEquals(1, countNetworkSolutions(2, tiles))
        assertEquals(NetworkSolver.Result.Contradiction, result)
    }
}
