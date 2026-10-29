package dev.sluide.coloring.color

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GamutMappingTest {
    @Test
    fun `an sRGB color in the gamut is unchanged`() {
        val color = CssColor.srgb(0.2, 0.5, 0.9, 0.5)
        assertEquals(color, color.toSrgbGamut())
    }

    @Test
    fun `a color of another space in the gamut maps to its sRGB value`() {
        val orange = CssColor.fromRgb8(255, 128, 0)
        val mapped = orange.to(ColorSpace.OKLCH).toSrgbGamut()
        assertEquals(orange.c0, mapped.c0, 1e-6)
        assertEquals(orange.c1, mapped.c1, 1e-6)
        assertEquals(orange.c2, mapped.c2, 1e-6)
    }

    @Test
    fun `a color outside sRGB is mapped in keeping hue and lightness`() {
        val wide = CssColor(ColorSpace.OKLCH, 0.7, 0.4, 150.0)
        assertFalse(wide.inSrgbGamut())
        val mapped = wide.toSrgbGamut()
        assertTrue(mapped.inSrgbGamut(0.0))
        val lch = mapped.to(ColorSpace.OKLCH)
        // The final clip may move the hue by up to the JND (deltaEOK 0.02), here about 3 degrees.
        assertTrue(abs(lch.c2 - 150.0) < 5.0, "hue ${lch.c2}")
        assertTrue(abs(lch.c0 - 0.7) < 0.01, "lightness ${lch.c0}")
    }

    @Test
    fun `a color a clip moves by less than the JND maps to its clip`() {
        // A chroma search would lower green and blue; the early return only clips red.
        val mapped = CssColor.srgb(1.01, 0.5, 0.2).toSrgbGamut()
        assertEquals(1.0, mapped.c0, 1e-9)
        assertEquals(0.5, mapped.c1, 1e-9)
        assertEquals(0.2, mapped.c2, 1e-9)
    }

    @Test
    fun `lightness above 1 gives white and below 0 gives black`() {
        assertEquals(CssColor.srgb(1.0, 1.0, 1.0), CssColor(ColorSpace.OKLCH, 1.2, 0.1, 30.0).toSrgbGamut())
        assertEquals(CssColor.srgb(0.0, 0.0, 0.0), CssColor(ColorSpace.OKLCH, -0.1, 0.1, 30.0).toSrgbGamut())
    }

    @Test
    fun `rgb8 rounds the gamut-mapped color`() {
        assertEquals(Rgb8(255, 128, 0, 255), CssColor.fromRgb8(255, 128, 0).toRgb8())
        assertEquals(Rgb8(255, 255, 255, 128), CssColor.srgb(2.0, 1.0, 1.0, 0.5).toRgb8())
    }

    @Test
    fun `deltaEOK of equal colors is 0 and of different colors positive`() {
        val red = CssColor.fromRgb8(255, 0, 0)
        assertEquals(0.0, deltaEOK(red, red), 0.0)
        assertEquals(0.5370, deltaEOK(red, CssColor.fromRgb8(0, 0, 255)), 1e-3)
    }
}
