package dev.sluide.games.wordguess

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileSizeTest {
    @Test
    fun `the tile size follows the tighter dimension`() {
        assertEquals(40.0, tileSize(400.0, 395.0))
        assertEquals(40.0, tileSize(371.0, 5000.0))
    }

    @Test
    fun `the tile size is clamped`() {
        assertEquals(MAX_TILE, tileSize(5000.0, 5000.0))
        assertEquals(MIN_TILE, tileSize(100.0, 100.0))
        assertEquals(MIN_TILE, tileSize(0.0, 0.0))
    }

    @Test
    fun `grid and keyboard fit from 380 by 460 pixels of content`() {
        val side = tileSize(380.0 - 16, 460.0 - 16)

        assertTrue(side >= 38, "tile side $side")
        assertTrue(11 * side * KEY_WIDTH_FACTOR + 10 * side * GAP_FACTOR <= 364)
    }
}
