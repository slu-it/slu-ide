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
}
