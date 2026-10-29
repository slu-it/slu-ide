package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.Gradient
import dev.sluide.coloring.analysis.GradientStop
import dev.sluide.coloring.analysis.GradientType
import dev.sluide.coloring.analysis.MixSpace
import dev.sluide.coloring.analysis.colorAt
import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FxColorsTest {
    @Test
    fun `an sRGB color becomes the same 8-bit color`() {
        val color = CssColor.fromRgb8(59, 130, 246).toFxColor()

        assertEquals(listOf(59, 130, 246), listOf(color.red, color.green, color.blue).map { (it * 255).toInt() })
        assertEquals(1.0, color.opacity)
    }

    @Test
    fun `alpha is kept in 8 bits`() {
        val color = CssColor.fromRgb8(255, 0, 0, a8 = 128).toFxColor()

        assertEquals(128, (color.opacity * 255).toInt())
    }

    @Test
    fun `a color outside sRGB is gamut-mapped to a valid color`() {
        val vivid = CssColor(ColorSpace.OKLCH, 0.7, 0.4, 145.0).toFxColor()

        assertEquals(true, listOf(vivid.red, vivid.green, vivid.blue).all { it in 0.0..1.0 })
        assertEquals(true, vivid.green > vivid.red && vivid.green > vivid.blue)
    }

    @Test
    fun `in a gradient a color outside sRGB is clipped per channel as browsers paint it`() {
        // Blue to yellow in OKLCH one step of 31 from blue: the blue channel is above 1 and clips to 255.
        val gradient =
            Gradient(
                GradientType.LINEAR,
                90.0,
                listOf(
                    GradientStop(CssColor.fromRgb8(0, 0, 255), 0.0),
                    GradientStop(CssColor.fromRgb8(255, 255, 0), 1.0),
                ),
                MixSpace.OKLCH,
            )
        val color = colorAt(gradient, 1.0 / 31)

        assertEquals(0xff002aff.toInt(), color.toArgbClipped())
        assertEquals(1.0, color.toFxColorClipped().blue)
        assertNotEquals(color.toFxColor(), color.toFxColorClipped())
    }
}
