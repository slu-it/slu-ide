package dev.sluide.coloring.color

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CmykTest {
    @Test
    fun `CMYK of black and orange`() {
        assertEquals(Cmyk(0.0, 0.0, 0.0, 1.0), toCmyk(CssColor.fromRgb8(0, 0, 0)))
        val orange = toCmyk(CssColor.fromRgb8(255, 128, 0))
        assertEquals(0.0, orange.c, 1e-9)
        assertEquals(1 - 128 / 255.0, orange.m, 1e-9)
        assertEquals(1.0, orange.y, 1e-9)
        assertEquals(0.0, orange.k, 1e-9)
    }

    @Test
    fun `CMYK converts back to sRGB`() {
        val back = fromCmyk(toCmyk(CssColor.fromRgb8(200, 100, 50)))
        assertEquals(200 / 255.0, back.c0, 1e-9)
        assertEquals(100 / 255.0, back.c1, 1e-9)
        assertEquals(50 / 255.0, back.c2, 1e-9)
    }
}
