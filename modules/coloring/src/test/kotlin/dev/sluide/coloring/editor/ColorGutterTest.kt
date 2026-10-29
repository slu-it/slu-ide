package dev.sluide.coloring.editor

import dev.sluide.coloring.detect.ColorFinder
import dev.sluide.coloring.detect.SourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorGutterTest {
    private fun found(line: String) = ColorFinder.find(line, SourceKind.STYLE)

    @Test
    fun `the tooltip lists the color texts, up to ten and then how many more`() {
        assertEquals("#f00", ColorGutter.tooltipText(found("a { color: #f00; }")))
        val many = (1..12).joinToString(" ") { "#" + it.toString(16).repeat(3) }
        val lines = ColorGutter.tooltipText(found("a { box-shadow: $many; }")).lines()
        assertEquals(11, lines.size)
        assertEquals("#111", lines.first())
        assertEquals("…and 2 more", lines.last())
    }

    @Test
    fun `a long color text is shortened for the menu item`() {
        assertEquals("#3b82f6", ColorGutter.shortened("#3b82f6"))
        val long = "color(display-p3 0.123456 0.123456 0.123456 / 0.5)"
        assertEquals(ColorGutter.MAX_ITEM_TEXT, ColorGutter.shortened(long).length)
        assertTrue(ColorGutter.shortened(long).endsWith("…"))
    }
}
