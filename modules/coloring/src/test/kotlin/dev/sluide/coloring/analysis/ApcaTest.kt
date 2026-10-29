package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.CssColor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ApcaTest {
    private val white = CssColor.fromRgb8(255, 255, 255)
    private val black = CssColor.fromRgb8(0, 0, 0)
    private val gray = CssColor.fromRgb8(0x88, 0x88, 0x88)

    @Test
    fun `published reference values`() {
        assertEquals(63.06, apcaContrast(gray, white), 0.05)
        assertEquals(-68.54, apcaContrast(white, gray), 0.05)
        assertEquals(106.04, apcaContrast(black, white), 0.05)
        assertEquals(-107.88, apcaContrast(white, black), 0.05)
    }

    @Test
    fun `low contrast below the clip gives zero`() {
        assertEquals(0.0, apcaContrast(CssColor.fromRgb8(0xe0, 0xe0, 0xe0), CssColor.fromRgb8(0xf0, 0xf0, 0xf0)), 0.0)
    }

    @Test
    fun `nearly equal colors give zero`() {
        assertEquals(0.0, apcaContrast(gray, gray), 0.0)
    }
}
