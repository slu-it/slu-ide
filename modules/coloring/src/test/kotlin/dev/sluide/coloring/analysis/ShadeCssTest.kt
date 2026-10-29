package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.CssColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShadeCssTest {
    @Test
    fun `css variables text has one line per shade with the prefix`() {
        val lines = cssVariablesText(shades(CssColor.fromRgb8(0x3b, 0x82, 0xf6)), "brand").lines()

        assertEquals(11, lines.size)
        assertTrue(lines.first().startsWith("--brand-50: #"), lines.first())
        assertTrue(lines.last().startsWith("--brand-950: #") && lines.last().endsWith(";"), lines.last())
    }
}
