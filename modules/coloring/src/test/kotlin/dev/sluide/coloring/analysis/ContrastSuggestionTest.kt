package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.toSrgbGamut
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ContrastSuggestionTest {
    private val white = CssColor.fromRgb8(255, 255, 255)
    private val lightGray = CssColor.fromRgb8(0xaa, 0xaa, 0xaa)
    private val salmon = CssColor.fromRgb8(240, 150, 140)

    @Test
    fun `suggestion passes and keeps the hue`() {
        val s = assertNotNull(nearestPassing(salmon, white))
        assertTrue(contrastRatio(s, white) >= 4.5)
        assertTrue(contrastRatio(s, white) < 4.6)
        val hueIn = salmon.to(ColorSpace.OKLCH).c2
        val hueOut = s.to(ColorSpace.OKLCH).c2
        assertEquals(0.0, abs(hueIn - hueOut), 1.0)
    }

    @Test
    fun `a passing foreground is returned unchanged`() {
        val black = CssColor.fromRgb8(0, 0, 0)
        assertSame(black, nearestPassing(black, white))
    }

    @Test
    fun `lightness change is minimal against a coarse scan`() {
        val s = assertNotNull(nearestPassing(lightGray, white))
        val l0 = lightGray.to(ColorSpace.OKLCH)
        val found = abs(s.to(ColorSpace.OKLCH).c0 - l0.c0)
        val coarse =
            generateSequence(0.01) { it + 0.01 }
                .takeWhile { it < 1.0 }
                .first { d ->
                    contrastRatio(CssColor(ColorSpace.OKLCH, l0.c0 - d, l0.c1, l0.c2).toSrgbGamut(), white) >= 4.5
                }
        assertTrue(found <= coarse + 1e-9, "found $found coarse $coarse")
        assertTrue(found > coarse - 0.011, "found $found coarse $coarse")
    }

    @Test
    fun `on a dark background the answer is lighter`() {
        val dark = CssColor.fromRgb8(0x20, 0x20, 0x20)
        val fg = CssColor.fromRgb8(0x40, 0x40, 0x40)
        val s = assertNotNull(nearestPassing(fg, dark))
        assertTrue(contrastRatio(s, dark) >= 4.5)
        assertTrue(s.to(ColorSpace.OKLCH).c0 > fg.to(ColorSpace.OKLCH).c0)
    }

    @Test
    fun `on a mid gray the nearer direction wins`() {
        val mid = CssColor.fromRgb8(0x75, 0x75, 0x75)
        // Both black and white reach 4.5 on this background, so both directions can pass.
        assertTrue(contrastRatio(CssColor.fromRgb8(0, 0, 0), mid) >= 4.5)
        assertTrue(contrastRatio(white, mid) >= 4.5)
        val light = CssColor.fromRgb8(0x99, 0x99, 0x99)
        val dark = CssColor.fromRgb8(0x33, 0x33, 0x33)
        val lighter = assertNotNull(nearestPassing(light, mid)).to(ColorSpace.OKLCH).c0
        val darker = assertNotNull(nearestPassing(dark, mid)).to(ColorSpace.OKLCH).c0
        assertTrue(lighter > light.to(ColorSpace.OKLCH).c0)
        assertTrue(darker < dark.to(ColorSpace.OKLCH).c0)
    }

    @Test
    fun `an impossible target gives null`() {
        assertNull(nearestPassing(lightGray, white, 22.0))
    }

    @Test
    fun `a ratio function tests the real pair, so a translucent text passes on the found background`() {
        val text = CssColor.srgb(1.0, 1.0, 1.0, 0.5)
        val found = assertNotNull(nearestPassing(CssColor.fromRgb8(0x4d, 0x4d, 0x4d), 4.5) { contrastRatio(text, it) })
        assertTrue(contrastRatio(text, found) >= 4.5)
    }
}
