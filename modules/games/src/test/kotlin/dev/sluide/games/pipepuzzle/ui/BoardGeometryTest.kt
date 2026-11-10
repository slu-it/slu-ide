package dev.sluide.games.pipepuzzle.ui

import dev.sluide.games.pipepuzzle.Pos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BoardGeometryTest {
    @Test
    fun `cell size follows the smaller side`() {
        assertEquals(57.0, BoardGeometry(400.0, 900.0, 7).cellSize)
        assertEquals(57.0, BoardGeometry(900.0, 400.0, 7).cellSize)
    }

    @Test
    fun `cell size is clamped at both ends`() {
        assertEquals(BoardGeometry.MIN_CELL, BoardGeometry(100.0, 100.0, 12).cellSize)
        assertEquals(BoardGeometry.MAX_CELL, BoardGeometry(2000.0, 2000.0, 5).cellSize)
    }

    @Test
    fun `board is centred`() {
        val g = BoardGeometry(500.0, 300.0, 5)
        assertEquals(60.0, g.cellSize)
        assertEquals(300.0, g.boardSize)
        assertEquals(100.0, g.originX)
        assertEquals(0.0, g.originY)
    }

    @Test
    fun `origin never goes negative when the board is larger than the area`() {
        val g = BoardGeometry(100.0, 100.0, 12)
        assertEquals(0.0, g.originX)
        assertEquals(0.0, g.originY)
    }

    @Test
    fun `posAt finds cells including the edges`() {
        val g = BoardGeometry(500.0, 300.0, 5)
        assertEquals(Pos(0, 0), g.posAt(100.0, 0.0))
        assertEquals(Pos(1, 2), g.posAt(100.0 + 2 * 60.0 + 59.9, 60.0))
        assertEquals(Pos(4, 4), g.posAt(399.9, 299.9))
    }

    @Test
    fun `posAt is null outside the board`() {
        val g = BoardGeometry(500.0, 300.0, 5)
        assertNull(g.posAt(99.9, 10.0))
        assertNull(g.posAt(400.0, 10.0))
        assertNull(g.posAt(200.0, 300.0))
        assertNull(g.posAt(200.0, -0.1))
    }

    @Test
    fun `cell corners follow origin and cell size`() {
        val g = BoardGeometry(500.0, 300.0, 5)
        assertEquals(100.0 + 3 * 60.0, g.cellX(Pos(1, 3)))
        assertEquals(60.0, g.cellY(Pos(1, 3)))
    }

    @Test
    fun `an empty area gives the smallest cells at the origin`() {
        val g = BoardGeometry(0.0, 0.0, 7)
        assertEquals(BoardGeometry.MIN_CELL, g.cellSize)
        assertEquals(0.0, g.originX)
        assertNull(g.posAt(-1.0, -1.0))
    }

    @Test
    fun `a board larger than the area ends after its last cell`() {
        val g = BoardGeometry(100.0, 100.0, 12)
        assertEquals(Pos(3, 3), g.posAt(99.9, 99.9))
        assertNull(g.posAt(g.boardSize, 0.0))
        assertNull(g.posAt(0.0, g.boardSize))
    }

    @Test
    fun `origin is on whole pixels`() {
        assertEquals(52.0, BoardGeometry(301.0, 200.0, 7).originX)
    }

    @Test
    fun `the zoom scales the Fit cell, which is clamped first`() {
        assertEquals(57.0, BoardGeometry(400.0, 900.0, 7, 1.0).cellSize)
        assertEquals(28.0, BoardGeometry(400.0, 900.0, 7, 0.5).cellSize)
        assertEquals(114.0, BoardGeometry(400.0, 900.0, 7, 2.0).cellSize)
        // 100 px fit the minimum cell of 28 px; half of it is below the minimum, and zoom 0 stays at 1 px
        assertEquals(14.0, BoardGeometry(100.0, 100.0, 12, 0.5).cellSize)
        assertEquals(1.0, BoardGeometry(100.0, 100.0, 12, 0.001).cellSize)
        assertEquals(144.0, BoardGeometry(2000.0, 2000.0, 5, 2.0).cellSize)
    }

    @Test
    fun `posAt finds cells of the zoomed size, with the cell taken from the Fit area`() {
        // Fit 360 px: 72 px cells, 144 px at zoom 2: a 720 px board in a 800 px wide canvas, centred at x = 40
        val g = BoardGeometry(800.0, 720.0, 5, 2.0, 360.0, 360.0)
        assertEquals(144.0, g.cellSize)
        assertEquals(40.0, g.originX)
        assertEquals(Pos(0, 0), g.posAt(40.0, 0.0))
        assertEquals(Pos(2, 3), g.posAt(40.0 + 3 * 144.0 + 143.9, 2 * 144.0 + 1.0))
        assertNull(g.posAt(39.9, 10.0))
        assertNull(g.posAt(40.0 + 720.0, 10.0))
    }
}
