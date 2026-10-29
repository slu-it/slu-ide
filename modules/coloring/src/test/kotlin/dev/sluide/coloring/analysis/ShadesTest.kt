package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.inSrgbGamut
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ShadesTest {
    private val teal = CssColor.fromRgb8(20, 160, 150)

    @Test
    fun `eleven steps with strictly decreasing lightness, all in gamut`() {
        val list = shades(teal)
        assertEquals(listOf(50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950), list.map { it.step })
        val ls = list.map { it.color.to(ColorSpace.OKLCH).c0 }
        for (i in 1 until ls.size) assertTrue(ls[i] < ls[i - 1], "step $i: $ls")
        assertTrue(list.all { it.color.inSrgbGamut() })
    }

    @Test
    fun `pale steps are less saturated than the middle`() {
        val list = shades(teal)

        fun chroma(step: Int) =
            list
                .first { it.step == step }
                .color
                .to(ColorSpace.OKLCH)
                .c1
        assertTrue(chroma(50) < chroma(500))
        assertTrue(chroma(950) < chroma(500))
    }

    @Test
    fun `exactly one step is the input itself`() {
        val list = shades(teal)
        val sources = list.filter { it.isSource }
        assertEquals(1, sources.size)
        assertSame(teal, sources.single().color)
        // L of teal is about 0.65: nearest target is 500 (.637).
        assertEquals(500, sources.single().step)
    }

    @Test
    fun `a gray gives grays`() {
        val gray = CssColor.fromRgb8(128, 128, 128)
        for (s in shades(gray)) {
            assertEquals(s.color.c0, s.color.c1, 1e-3)
            assertEquals(s.color.c1, s.color.c2, 1e-3)
        }
    }

    @Test
    fun `very light and very dark sources keep the order`() {
        for (c in listOf(CssColor.fromRgb8(255, 255, 255), CssColor.fromRgb8(0, 0, 0))) {
            val ls = shades(c).map { it.color.to(ColorSpace.OKLCH).c0 }
            for (i in 1 until ls.size) assertTrue(ls[i] < ls[i - 1], "$c: $ls")
        }
    }
}
