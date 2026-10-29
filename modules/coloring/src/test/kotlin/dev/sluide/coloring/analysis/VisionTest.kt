package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.Rgb8
import dev.sluide.coloring.color.toRgb8
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class VisionTest {
    @Test
    fun `grays are unchanged by every deficiency`() {
        for (v in listOf(0, 10, 77, 128, 200, 255)) {
            val gray = CssColor.fromRgb8(v, v, v)
            for (d in Deficiency.entries) {
                val s = simulate(gray, d)
                assertEquals(gray.c0, s.c0, 1e-6, "$d $v")
                assertEquals(gray.c1, s.c1, 1e-6, "$d $v")
                assertEquals(gray.c2, s.c2, 1e-6, "$d $v")
            }
        }
    }

    @Test
    fun `achromatopsia gives equal channels and keeps the alpha`() {
        val s = simulate(CssColor.srgb(0.9, 0.2, 0.4, 0.3), Deficiency.ACHROMATOPSIA)
        assertEquals(s.c0, s.c1, 1e-12)
        assertEquals(s.c1, s.c2, 1e-12)
        assertEquals(0.3, s.alpha, 1e-12)
    }

    @Test
    fun `protanopia red loses its red`() {
        val s = simulate(CssColor.fromRgb8(255, 0, 0), Deficiency.PROTANOPIA).toRgb8()
        // Linear (0.152, 0.115, 0) encodes to (109, 95, 0): red and green are close, as protanopes see red.
        assertEquals(Rgb8(109, 95, 0), s)
    }

    @Test
    fun `deuteranopia green and tritanopia blue`() {
        // Linear (0.861, 0.673, 0.043) encodes to (239, 214, 58); linear (0, 0.148, 0.304) to (0, 107, 150).
        assertEquals(Rgb8(239, 214, 58), simulate(CssColor.fromRgb8(0, 255, 0), Deficiency.DEUTERANOPIA).toRgb8())
        assertEquals(Rgb8(0, 107, 150), simulate(CssColor.fromRgb8(0, 0, 255), Deficiency.TRITANOPIA).toRgb8())
    }
}
