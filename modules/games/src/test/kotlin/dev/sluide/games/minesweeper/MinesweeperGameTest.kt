package dev.sluide.games.minesweeper

import org.junit.jupiter.api.assertThrows
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MinesweeperGameTest {
    /** 5×5 with one mine in the top-left corner: everything else is reachable by one flood from the far corner. */
    private fun cornerMine() = MinesweeperGame(BoardSize(5, 5, 1), setOf(0 to 0))

    /** 5×5 with mines at (0,0) and (4,4). */
    private fun twoMines() = MinesweeperGame(BoardSize(5, 5, 2), setOf(0 to 0, 4 to 4))

    private fun MinesweeperGame.mines(): Set<Pair<Int, Int>> {
        // Only valid on a lost game, where every mine is shown.
        return (0 until size.height)
            .flatMap { y -> (0 until size.width).map { x -> x to y } }
            .filter { (x, y) -> cell(x, y) in setOf(CellKind.MINE, CellKind.HIT_MINE) }
            .toSet()
    }

    @Test
    fun `mines are never placed in the 3x3 around the first open`() {
        val clicks = listOf(0 to 0, 4 to 4, 4 to 0, 2 to 2, 1 to 3)
        for (seed in 0 until 200) {
            for ((cx, cy) in clicks) {
                val game = MinesweeperGame(BoardSize(5, 5, 16), Random(seed))
                game.open(cx, cy)
                assertNotEquals(GameState.LOST, game.state, "seed $seed click $cx,$cy")
                // On the densest board a click in the middle opens every safe cell at once.
                if (cx == 2 && cy == 2) assertEquals(GameState.WON, game.state, "seed $seed")
                assertEquals(CellKind.OPENED, game.cell(cx, cy))
            }
        }
    }

    @Test
    fun `placement has the requested number of mines and spares the first area on expert`() {
        for (seed in 0 until 100) {
            val game = MinesweeperGame(Preset.EXPERT.size, Random(seed))
            game.open(0, 0)
            assertEquals(GameState.RUNNING, game.state)
            // Lose on purpose to see the mines: open closed cells until a mine is hit.
            var guard = 0
            while (game.state == GameState.RUNNING && guard++ < 1000) {
                val (x, y) = closedCells(game).first()
                game.open(x, y)
            }
            assertEquals(GameState.LOST, game.state, "seed $seed")
            assertEquals(99, game.mines().size + flaggedMines(game))
            assertTrue(game.mines().none { (x, y) -> x <= 1 && y <= 1 })
        }
    }

    @Test
    fun `placement is spread over all cells outside the first area`() {
        val everMine = mutableSetOf<Pair<Int, Int>>()
        val alwaysMine = (0 until 9).flatMap { y -> (0 until 9).map { x -> x to y } }.toMutableSet()
        for (seed in 0 until 200) {
            val game = MinesweeperGame(Preset.BEGINNER.size, Random(seed))
            game.open(0, 0)
            while (game.state == GameState.RUNNING) {
                val (x, y) = closedCells(game).first()
                game.open(x, y)
            }
            val mines = game.mines()
            everMine += mines
            alwaysMine.retainAll(mines)
        }

        val outside = (0 until 9).flatMap { y -> (0 until 9).map { x -> x to y } }.filter { (x, y) -> x > 1 || y > 1 }
        assertEquals(outside.toSet(), everMine)
        assertTrue(alwaysMine.isEmpty())
    }

    private fun closedCells(game: MinesweeperGame) =
        (0 until game.size.height).flatMap { y ->
            (0 until game.size.width).map { x -> x to y }.filter { (x, y) -> game.cell(x, y) == CellKind.CLOSED }
        }

    private fun flaggedMines(game: MinesweeperGame) =
        (0 until game.size.height).sumOf { y ->
            (0 until game.size.width).count { x -> game.cell(x, y) == CellKind.FLAGGED }
        }

    @Test
    fun `the same seed places the same mines`() {
        fun lostMines(): Set<Pair<Int, Int>> {
            val game = MinesweeperGame(Preset.BEGINNER.size, Random(7))
            game.open(4, 4)
            while (game.state == GameState.RUNNING) {
                val (x, y) = closedCells(game).first()
                game.open(x, y)
            }
            return game.mines()
        }

        assertEquals(lostMines(), lostMines())
    }

    @Test
    fun `the first open starts the game, flags before it do not`() {
        val game = MinesweeperGame(Preset.BEGINNER.size, Random(1))

        assertTrue(game.toggleFlag(0, 0))
        assertEquals(GameState.NOT_STARTED, game.state)
        assertEquals(9, game.minesLeft)
        assertTrue(game.open(4, 4))
        assertEquals(GameState.RUNNING, game.state)
    }

    @Test
    fun `opening a zero floods the region and its border numbers but not past them`() {
        // Mine at (0,0): (1,0), (0,1), (1,1) show 1; everything else is 0 and floods.
        val game = cornerMine()

        assertTrue(game.open(4, 4))

        assertEquals(GameState.WON, game.state)
        assertEquals(1, game.adjacentMines(1, 1))
    }

    @Test
    fun `a flood stops at numbered cells`() {
        // Mines at (2,0),(2,1),(2,2),(2,3),(2,4): a wall, the left side floods from (0,0) but not beyond the wall.
        val wall = (0..4).map { 2 to it }.toSet()
        val game = MinesweeperGame(BoardSize(5, 5, 5), wall)

        game.open(0, 0)

        for (y in 0..4) {
            assertEquals(CellKind.OPENED, game.cell(0, y))
            assertEquals(CellKind.OPENED, game.cell(1, y))
            assertEquals(CellKind.CLOSED, game.cell(3, y))
            assertEquals(CellKind.CLOSED, game.cell(4, y))
        }
        assertEquals(GameState.RUNNING, game.state)
        assertEquals(2, game.adjacentMines(1, 0))
        assertEquals(3, game.adjacentMines(1, 1))
    }

    @Test
    fun `a flood does not open flagged cells`() {
        val game = cornerMine()
        game.toggleFlag(4, 4)

        game.open(4, 0)

        assertEquals(CellKind.FLAGGED, game.cell(4, 4))
        assertEquals(GameState.RUNNING, game.state)
        assertEquals(CellKind.OPENED, game.cell(3, 4))
    }

    @Test
    fun `a flagged cell cannot be opened and an opened cell opens again to nothing`() {
        val game = twoMines()
        game.toggleFlag(1, 1)

        assertFalse(game.open(1, 1))
        assertEquals(CellKind.FLAGGED, game.cell(1, 1))
        game.open(2, 2)
        assertFalse(game.open(2, 2))
        assertFalse(game.toggleFlag(2, 2))
    }

    @Test
    fun `flag toggling counts minesLeft, also below zero`() {
        val game = cornerMine()

        game.toggleFlag(1, 1)
        game.toggleFlag(2, 2)
        assertEquals(-1, game.minesLeft)
        game.toggleFlag(2, 2)
        assertEquals(0, game.minesLeft)
        game.toggleFlag(1, 1)
        assertEquals(1, game.minesLeft)
    }

    @Test
    fun `opening a mine loses and shows the board`() {
        val game = twoMines()
        game.toggleFlag(4, 4)
        game.toggleFlag(2, 2)

        assertTrue(game.open(0, 0))

        assertEquals(GameState.LOST, game.state)
        assertEquals(CellKind.HIT_MINE, game.cell(0, 0))
        assertEquals(CellKind.FLAGGED, game.cell(4, 4))
        assertEquals(CellKind.WRONG_FLAG, game.cell(2, 2))
        assertEquals(CellKind.CLOSED, game.cell(3, 3))
    }

    @Test
    fun `an unflagged other mine shows as MINE after losing`() {
        val game = twoMines()

        game.open(4, 4)

        assertEquals(CellKind.HIT_MINE, game.cell(4, 4))
        assertEquals(CellKind.MINE, game.cell(0, 0))
    }

    @Test
    fun `chord opens the other neighbours when the flags match`() {
        val game = twoMines()
        game.open(1, 1)
        game.toggleFlag(0, 0)

        assertTrue(game.chord(1, 1))

        assertEquals(CellKind.OPENED, game.cell(0, 1))
        assertEquals(CellKind.OPENED, game.cell(2, 2))
        assertEquals(GameState.WON, game.state)
    }

    @Test
    fun `chord with too few or too many flags does nothing`() {
        val game = twoMines()
        game.open(1, 1)

        assertFalse(game.chord(1, 1))
        game.toggleFlag(0, 0)
        game.toggleFlag(2, 2)
        assertFalse(game.chord(1, 1))
        assertEquals(CellKind.CLOSED, game.cell(0, 1))
    }

    @Test
    fun `chord on a closed cell or a zero does nothing`() {
        val game = twoMines()
        assertFalse(game.chord(1, 1))
        game.open(2, 2)
        assertEquals(0, game.adjacentMines(2, 2))
        assertFalse(game.chord(2, 2))
    }

    @Test
    fun `chord with a wrong flag loses and marks every hit mine`() {
        // Both mines touch the opened 2 at (1,1); the flags next to it are on safe cells.
        val game = MinesweeperGame(BoardSize(5, 5, 2), setOf(0 to 0, 2 to 0))
        game.open(1, 1)
        assertEquals(2, game.adjacentMines(1, 1))
        game.toggleFlag(0, 1)
        game.toggleFlag(1, 0)

        assertTrue(game.chord(1, 1))

        assertEquals(GameState.LOST, game.state)
        assertEquals(CellKind.HIT_MINE, game.cell(0, 0))
        assertEquals(CellKind.HIT_MINE, game.cell(2, 0))
        assertEquals(CellKind.WRONG_FLAG, game.cell(0, 1))
        assertEquals(CellKind.WRONG_FLAG, game.cell(1, 0))
    }

    @Test
    fun `a chord that hits one mine leaves a flagged mine flagged and shows the others`() {
        val game = MinesweeperGame(BoardSize(5, 5, 3), setOf(0 to 0, 2 to 0, 4 to 4))
        game.open(1, 1)
        game.toggleFlag(0, 0)
        game.toggleFlag(0, 1)

        game.chord(1, 1)

        assertEquals(GameState.LOST, game.state)
        assertEquals(CellKind.FLAGGED, game.cell(0, 0))
        assertEquals(CellKind.HIT_MINE, game.cell(2, 0))
        assertEquals(CellKind.WRONG_FLAG, game.cell(0, 1))
        assertEquals(CellKind.MINE, game.cell(4, 4))
    }

    @Test
    fun `reveal opens a closed cell and chords an opened one`() {
        val game = twoMines()

        assertTrue(game.reveal(1, 1))
        assertEquals(CellKind.OPENED, game.cell(1, 1))
        assertFalse(game.reveal(1, 1))
        game.toggleFlag(0, 0)
        assertTrue(game.reveal(1, 1))
        assertEquals(CellKind.OPENED, game.cell(2, 2))
    }

    @Test
    fun `opening the last safe cell wins and flags every mine`() {
        val game = twoMines()
        game.toggleFlag(2, 2) // a wrong flag; the flood and clicks skip it

        for (y in 0..4) for (x in 0..4) if (!(x to y in setOf(0 to 0, 4 to 4))) game.open(x, y)
        assertEquals(GameState.RUNNING, game.state)
        game.toggleFlag(2, 2)
        game.open(2, 2)

        assertEquals(GameState.WON, game.state)
        assertEquals(CellKind.FLAGGED, game.cell(0, 0))
        assertEquals(CellKind.FLAGGED, game.cell(4, 4))
        assertEquals(0, game.minesLeft)
    }

    @Test
    fun `after the end every action returns false and nothing changes`() {
        for (win in listOf(true, false)) {
            val game = cornerMine()
            if (win) game.open(4, 4) else game.open(0, 0)
            val before = (0..4).flatMap { y -> (0..4).map { x -> game.cell(x, y) } }
            val left = game.minesLeft

            assertFalse(game.open(1, 1))
            assertFalse(game.toggleFlag(1, 1))
            assertFalse(game.chord(1, 1))
            assertFalse(game.reveal(1, 1))
            assertFalse(game.reveal(0, 0))

            assertEquals(before, (0..4).flatMap { y -> (0..4).map { x -> game.cell(x, y) } })
            assertEquals(left, game.minesLeft)
        }
    }

    @Test
    fun `flood on the largest board does not overflow the stack`() {
        val game = MinesweeperGame(BoardSize(50, 30, 1), setOf(0 to 0))

        game.open(49, 29)

        assertEquals(GameState.WON, game.state)
    }

    @Test
    fun `coordinates outside the board throw`() {
        val game = cornerMine()

        for ((x, y) in listOf(-1 to 0, 0 to -1, 5 to 0, 0 to 5)) {
            assertThrows<IllegalArgumentException> { game.cell(x, y) }
            assertThrows<IllegalArgumentException> { game.open(x, y) }
            assertThrows<IllegalArgumentException> { game.toggleFlag(x, y) }
            assertThrows<IllegalArgumentException> { game.chord(x, y) }
            assertThrows<IllegalArgumentException> { game.reveal(x, y) }
            assertThrows<IllegalArgumentException> { game.adjacentMines(x, y) }
        }
    }
}
