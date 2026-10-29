package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.CssColor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WcagTest {
    private val white = CssColor.fromRgb8(255, 255, 255)
    private val black = CssColor.fromRgb8(0, 0, 0)

    @Test
    fun `known grays on white`() {
        assertEquals(4.54, contrastRatio(CssColor.fromRgb8(0x76, 0x76, 0x76), white), 0.005)
        assertEquals(4.48, contrastRatio(CssColor.fromRgb8(0x77, 0x77, 0x77), white), 0.005)
    }

    @Test
    fun `black on white is 21 and a color on itself is 1`() {
        assertEquals(21.0, contrastRatio(black, white), 1e-9)
        assertEquals(21.0, contrastRatio(white, black), 1e-9)
        assertEquals(1.0, contrastRatio(CssColor.fromRgb8(10, 200, 90), CssColor.fromRgb8(10, 200, 90)), 1e-9)
    }

    @Test
    fun `verdicts use the unrounded ratio`() {
        val v = wcagVerdicts(4.4999)
        assertFalse(v.aaNormal)
        assertTrue(v.aaLarge)
        assertFalse(v.aaaLarge)
        assertTrue(wcagVerdicts(4.5).aaNormal)
        assertTrue(wcagVerdicts(4.5).aaaLarge)
        assertFalse(wcagVerdicts(6.99).aaaNormal)
        assertTrue(wcagVerdicts(7.0).aaaNormal)
        assertFalse(wcagVerdicts(2.99).nonText)
        assertTrue(wcagVerdicts(3.0).nonText)
    }

    @Test
    fun `translucent foreground is flattened over the background`() {
        val half = CssColor.srgb(0.0, 0.0, 0.0, 0.5)
        val flat = CssColor.srgb(0.5, 0.5, 0.5)
        assertEquals(contrastRatio(flat, white), contrastRatio(half, white), 1e-9)
    }

    @Test
    fun `translucent background is flattened over white`() {
        val half = CssColor.srgb(0.0, 0.0, 0.0, 0.5)
        assertEquals(contrastRatio(black, CssColor.srgb(0.5, 0.5, 0.5)), contrastRatio(black, half), 1e-9)
    }
}
