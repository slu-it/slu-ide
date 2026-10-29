package dev.sluide.coloring.ui.gradient

import dev.sluide.coloring.analysis.Gradient
import dev.sluide.coloring.analysis.GradientStop
import dev.sluide.coloring.analysis.GradientType
import dev.sluide.coloring.analysis.gradientT
import dev.sluide.coloring.color.CssColor
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

    @Test
    fun `the picked position matches the conic raster`() {
        val stops = listOf(GradientStop(CssColor.fromRgb8(0, 0, 0), 0.0), GradientStop(CssColor.fromRgb8(1, 1, 1), 1.0))
        val lookup = IntArray(360) { it }
        for (from in listOf(0.0, 90.0, 200.0)) {
            val g = Gradient(GradientType.CONIC, from, stops, null)
            val pixels = conicPixels(40, 20, from, lookup)
            for ((x, y) in listOf(30 to 2, 35 to 10, 20 to 19, 3 to 12, 8 to 1)) {
                val picked = gradientT(g, x + 0.5, y + 0.5, 40.0, 20.0)
                assertEquals(pixels[y * 40 + x].toDouble(), picked * 360, 1.0, "from $from at $x,$y")
            }
        }
    }
}
