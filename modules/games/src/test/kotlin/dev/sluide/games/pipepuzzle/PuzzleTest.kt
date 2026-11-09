package dev.sluide.games.pipepuzzle

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test

class PuzzleTest {
    private val solution =
        board(
            "╶┐",
            "╶┘",
        )

    private fun puzzle(
        start: List<Openings> = solution,
        source: Pos = Pos(0, 0),
        drain: Pos = Pos(1, 0),
        tiles: List<Openings> = solution,
    ) = Puzzle(Mode.NETWORK, 2, source, drain, tiles, start)

    @Test
    fun `a start that is a turn of the solution per cell is accepted`() {
        puzzle(start = board("╶┘", "╶┐"))
    }

    @Test
    fun `lists of the wrong size are refused`() {
        assertThrows<IllegalArgumentException> { puzzle(start = solution.dropLast(1)) }
        assertThrows<IllegalArgumentException> { puzzle(tiles = solution + solution) }
    }

    @Test
    fun `a start tile that is no turn of the solution tile is refused`() {
        assertThrows<IllegalArgumentException> { puzzle(start = board("╶─", "╶┘")) }
    }

    @Test
    fun `an empty tile is refused`() {
        val empty = solution.toMutableList().also { it[3] = Openings.NONE }
        assertThrows<IllegalArgumentException> { puzzle(tiles = empty, start = empty) }
    }

    @Test
    fun `a source or drain off the board, on the same cell, turned or with more than one opening is refused`() {
        assertThrows<IllegalArgumentException> { puzzle(source = Pos(2, 0)) }
        assertThrows<IllegalArgumentException> { puzzle(drain = Pos(0, 0)) }
        assertThrows<IllegalArgumentException> { puzzle(start = board("╷┐", "╶┘")) }
        assertThrows<IllegalArgumentException> { puzzle(drain = Pos(0, 1)) }
    }
}
