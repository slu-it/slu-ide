package dev.sluide.games.doubles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileSizeTest {
    @Test
    fun `the tile size follows the tighter dimension`() {
        // 4x4: 4.6 tile sides wide, 0.7 + 0.25 + 4.6 = 5.55 high.
        assertEquals(100.0, tileSize(461.0, 5000.0, 4))
        assertEquals(100.0, tileSize(5000.0, 556.0, 4))
        assertEquals(50.0, tileSize(5000.0, 278.0, 4))
        assertEquals(50.0, tileSize(231.0, 5000.0, 4))
    }

    @Test
    fun `more cells make smaller tiles`() {
        assertTrue(tileSize(500.0, 700.0, 6) < tileSize(500.0, 700.0, 3))
    }

    @Test
    fun `the tile size is clamped`() {
        assertEquals(MAX_TILE, tileSize(5000.0, 5000.0, 4))
        assertEquals(MIN_TILE, tileSize(100.0, 100.0, 6))
        assertEquals(MIN_TILE, tileSize(0.0, 0.0, 3))
    }

    @Test
    fun `the font shrinks with the number of digits`() {
        assertEquals(45.0, fontSize(100.0, 2), 1e-9)
        assertEquals(45.0, fontSize(100.0, 64), 1e-9)
        assertEquals(38.0, fontSize(100.0, 128), 1e-9)
        assertEquals(30.0, fontSize(100.0, 2048), 1e-9)
        assertEquals(24.0, fontSize(100.0, 16384), 1e-9)
    }
}
