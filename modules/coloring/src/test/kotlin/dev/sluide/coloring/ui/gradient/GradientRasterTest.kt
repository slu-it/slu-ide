package dev.sluide.coloring.ui.gradient

import kotlin.test.Test
import kotlin.test.assertEquals

class GradientRasterTest {
    @Test
    fun `conic pixels count clockwise from the start angle`() {
        // Four quadrants as four lookup entries: 0 = up-right, 1 = down-right, 2 = down-left, 3 = up-left.
        val lookup = intArrayOf(0, 1, 2, 3)
        val up = conicPixels(10, 10, 0.0, lookup)
        assertEquals(0, up[9])
        assertEquals(1, up[9 * 10 + 9])
        assertEquals(2, up[9 * 10])
        assertEquals(3, up[0])
        // Starting at 90deg, the down-right pixel (135deg) is 45deg into the gradient.
        assertEquals(0, conicPixels(10, 10, 90.0, lookup)[9 * 10 + 9])
    }
}
