package dev.sluide.games.minesweeper

import kotlin.test.Test
import kotlin.test.assertEquals

class CellSizeTest {
    @Test
    fun `cell size fills the space within the limits`() {
        assertEquals(30.0, cellSize(300.0, 600.0, 10, 10))
        assertEquals(20.0, cellSize(900.0, 200.0, 30, 10))
        assertEquals(37.0, cellSize(1000.0, 370.9, 9, 10))
    }

    @Test
    fun `cell size is clamped`() {
        assertEquals(MAX_CELL, cellSize(5000.0, 5000.0, 9, 9))
        assertEquals(MIN_CELL, cellSize(100.0, 100.0, 50, 30))
        assertEquals(MIN_CELL, cellSize(0.0, 0.0, 9, 9))
    }

    @Test
    fun `the zoom scales the Fit size, limits included, to at least 1 px`() {
        assertEquals(15.0, cellSize(300.0, 600.0, 10, 10, zoom = 0.5))
        assertEquals(18.0, cellSize(1000.0, 370.9, 9, 10, zoom = 0.5))
        assertEquals(60.0, cellSize(300.0, 600.0, 10, 10, zoom = 2.0))
        assertEquals(2 * MAX_CELL, cellSize(5000.0, 5000.0, 9, 9, zoom = 2.0))
        assertEquals(MIN_CELL / 4, cellSize(100.0, 100.0, 50, 30, zoom = 0.25))
        assertEquals(1.0, cellSize(0.0, 0.0, 9, 9, zoom = 0.01))
    }
}
