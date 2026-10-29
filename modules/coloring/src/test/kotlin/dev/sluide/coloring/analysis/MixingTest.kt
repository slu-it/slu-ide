package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.Rgb8
import dev.sluide.coloring.color.toRgb8
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MixingTest {
    private val red = CssColor.fromRgb8(255, 0, 0)
    private val blue = CssColor.fromRgb8(0, 0, 255)
    private val white = CssColor.fromRgb8(255, 255, 255)

    @Test
    fun `srgb mix with one percentage`() {
        val m = assertNotNull(colorMix(red, 25.0, blue, null, MixSpace.SRGB))
        assertEquals(0.25, m.c0, 1e-9)
        assertEquals(0.0, m.c1, 1e-9)
        assertEquals(0.75, m.c2, 1e-9)
        assertEquals(Rgb8(64, 0, 191), m.toRgb8())
        assertEquals(Rgb8(128, 0, 128), colorMix(red, null, blue, null, MixSpace.SRGB)?.toRgb8())
    }

    @Test
    fun `none alpha is read from the original color`() {
        val noAlpha = CssColor(ColorSpace.SRGB, 1.0, 0.0, 0.0, 0.0, 1 shl CssColor.ALPHA)
        val m = assertNotNull(colorMix(noAlpha, null, blue, null, MixSpace.OKLCH))
        assertEquals(1.0, m.alpha, 1e-9)
    }

    @Test
    fun `none components are carried into the mix space`() {
        val noL = CssColor(ColorSpace.OKLCH, 0.0, 0.1, 30.0, 1.0, 1)
        val m = assertNotNull(colorMix(noL, null, white, null, MixSpace.OKLAB))
        assertEquals(1.0, m.c0, 1e-6)
    }

    @Test
    fun `none components stay missing in the same space`() {
        val noL = CssColor(ColorSpace.OKLCH, 0.0, 0.1, 30.0, 1.0, 1)
        val other = CssColor(ColorSpace.OKLCH, 0.5, 0.1, 30.0)
        val m = assertNotNull(colorMix(noL, null, other, null, MixSpace.OKLCH))
        assertEquals(0.5, m.c0, 1e-9)
        val both = assertNotNull(colorMix(noL, null, noL, null, MixSpace.OKLCH))
        assertEquals(true, both.isMissing(0))
    }

    @Test
    fun `percentages below 100 scale the alpha`() {
        val m = assertNotNull(colorMix(red, 25.0, blue, 25.0, MixSpace.SRGB))
        assertEquals(0.5, m.alpha, 1e-9)
        assertEquals(0.5, m.c0, 1e-9)
    }

    @Test
    fun `percentages above 100 are scaled and zero is invalid`() {
        val m = assertNotNull(colorMix(red, 100.0, blue, 100.0, MixSpace.SRGB))
        assertEquals(1.0, m.alpha, 1e-9)
        assertEquals(0.5, m.c0, 1e-9)
        assertNull(colorMix(red, 0.0, blue, 0.0, MixSpace.SRGB))
    }

    @Test
    fun `powerless hue takes the other hue`() {
        val m = assertNotNull(colorMix(white, null, blue, null, MixSpace.OKLCH))
        val b = blue.to(ColorSpace.OKLCH)
        assertEquals(ColorSpace.OKLCH, m.space)
        assertEquals(b.c2, m.c2, 1e-6)
        assertEquals((1 + b.c0) / 2, m.c0, 1e-6)
    }

    @Test
    fun `hues interpolate along the shorter arc`() {
        val a = CssColor(ColorSpace.OKLCH, 0.6, 0.1, 350.0)
        val b = CssColor(ColorSpace.OKLCH, 0.6, 0.1, 10.0)
        val m = assertNotNull(colorMix(a, null, b, null, MixSpace.OKLCH))
        assertEquals(0.0, m.c2, 1e-9)
    }

    @Test
    fun `alpha is premultiplied`() {
        val a = CssColor.srgb(1.0, 0.0, 0.0, 1.0)
        val b = CssColor.srgb(0.0, 0.0, 1.0, 0.0)
        val m = assertNotNull(colorMix(a, null, b, null, MixSpace.SRGB))
        assertEquals(0.5, m.alpha, 1e-9)
        assertEquals(1.0, m.c0, 1e-9)
        assertEquals(0.0, m.c2, 1e-9)
    }

    @Test
    fun `flatten composites in encoded sRGB`() {
        val f = flatten(CssColor.srgb(0.0, 0.0, 0.0, 0.5), white)
        assertEquals(0.5, f.c0, 1e-9)
        assertEquals(0.5, f.c2, 1e-9)
        assertEquals(1.0, f.alpha, 1e-9)
        assertEquals(0.75, flatten(CssColor.srgb(0.0, 0.0, 0.0, 0.5), CssColor.srgb(1.0, 1.0, 1.0, 0.5)).alpha, 1e-9)
    }
}
