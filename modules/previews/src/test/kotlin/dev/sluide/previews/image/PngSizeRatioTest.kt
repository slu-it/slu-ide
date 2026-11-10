package dev.sluide.previews.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PngSizeRatioTest {
    @Test
    fun `a square keeps its sides equal`() {
        assertEquals(32, PngSizeRatio(800, 800).heightFor(32))
    }

    @Test
    fun `the other side follows the natural ratio and is at least 1`() {
        val ratio = PngSizeRatio(300, 200)
        assertEquals(100, ratio.heightFor(150))
        assertEquals(2, ratio.widthFor(1))
        assertEquals(1, ratio.heightFor(1))
    }

    @Test
    fun `going back and forth does not drift`() {
        val ratio = PngSizeRatio(301, 199)
        var width = 57
        val first = ratio.heightFor(width)
        repeat(20) {
            val height = ratio.heightFor(width)
            assertEquals(first, height)
            width = ratio.widthFor(height)
        }
        assertEquals(ratio.widthFor(first), width)
    }

    @Test
    fun `the limits are 8192 per side and 2 to the 25 pixels`() {
        val ratio = PngSizeRatio(1, 1)
        assertFalse(ratio.tooLarge(8192, 1))
        assertTrue(ratio.tooLarge(8193, 1))
        assertTrue(ratio.tooLarge(1, 8193))
        assertFalse(ratio.tooLarge(8192, 4096))
        assertTrue(ratio.tooLarge(8192, 4097))
    }
}
