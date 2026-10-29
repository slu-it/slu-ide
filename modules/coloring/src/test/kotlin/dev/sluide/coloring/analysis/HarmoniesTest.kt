package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HarmoniesTest {
    private val orange = CssColor(ColorSpace.OKLCH, 0.7, 0.08, 60.0)

    private fun hue(c: CssColor) = c.to(ColorSpace.OKLCH).c2

    @Test
    fun `rotations of an in-gamut color`() {
        val h = harmonies(orange)
        assertEquals(listOf(240.0), h.complementary.map(::hue).map { Math.round(it * 10) / 10.0 })
        assertEquals(listOf(30.0, 90.0), h.analogous.map(::hue).map { Math.round(it * 10) / 10.0 })
        assertEquals(listOf(180.0, 300.0), h.triadic.map(::hue).map { Math.round(it * 10) / 10.0 })
        assertEquals(listOf(210.0, 270.0), h.splitComplementary.map(::hue).map { Math.round(it * 10) / 10.0 })
    }

    @Test
    fun `lightness is kept`() {
        val h = harmonies(orange)
        for (c in h.triadic) assertEquals(0.7, c.to(ColorSpace.OKLCH).c0, 1e-3)
    }

    @Test
    fun `hue wraps past 360`() {
        val h = harmonies(CssColor(ColorSpace.OKLCH, 0.7, 0.05, 350.0))
        assertEquals(20.0, hue(h.analogous[1]), 0.1)
    }

    @Test
    fun `achromatic colors rotate to themselves`() {
        val gray = CssColor.fromRgb8(100, 100, 100)
        val h = harmonies(gray)
        for (c in h.complementary + h.analogous + h.triadic + h.splitComplementary) {
            assertEquals(gray.c0, c.c0, 1e-6)
            assertEquals(gray.c2, c.c2, 1e-6)
        }
    }
}
