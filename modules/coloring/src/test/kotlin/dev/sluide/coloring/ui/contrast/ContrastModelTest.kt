package dev.sluide.coloring.ui.contrast

import dev.sluide.coloring.analysis.contrastRatio
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContrastModelTest {
    private val white = CssColor.srgb(1.0, 1.0, 1.0)
    private val black = CssColor.srgb(0.0, 0.0, 0.0)
    private val grey = CssColor.fromRgb8(150, 150, 150)

    @Test
    fun `the ratio is truncated to two decimals, never rounded up`() {
        assertEquals("4.49 : 1", ratioText(4.4999))
        assertEquals("4.54 : 1", ratioText(4.549))
        assertEquals("21.00 : 1", ratioText(21.0))
        assertEquals("4.50 : 1", ratioText(4.5))
    }

    @Test
    fun `black on white is 21 and passes everything`() {
        val s = contrastView(black, white, currentIsText = true)
        assertEquals("21.00 : 1", s.ratioText)
        assertTrue(s.badges.all { it.pass })
        assertNull(s.suggestion)
        assertTrue(s.apcaText.startsWith("Lc 10"), s.apcaText)
    }

    @Test
    fun `verdict flags at the thresholds`() {
        val s = contrastView(grey, white, currentIsText = true)
        assertEquals(
            listOf("AA normal", "AA large", "AAA normal", "AAA large", "UI components"),
            s.badges.map { it.label },
        )
        // 150 grey on white is about 2.9 : 1, 118 grey about 4.5 : 1
        assertEquals(listOf(false, false, false, false, false), s.badges.map { it.pass })
        val mid = contrastView(CssColor.fromRgb8(118, 118, 118), white, currentIsText = true)
        assertEquals(listOf(true, true, false, true, true), mid.badges.map { it.pass })
    }

    @Test
    fun `currentIsText decides which color is the text`() {
        val asText = contrastView(black, white, currentIsText = true)
        val asBackground = contrastView(black, white, currentIsText = false)
        assertEquals(black, asText.text)
        assertEquals(white, asBackground.text)
        assertEquals(black, asBackground.background)
        assertTrue(asBackground.apcaText.startsWith("Lc -"), asBackground.apcaText)
    }

    @Test
    fun `the suggestion targets AA when it fails, AAA when only that fails, none when both pass`() {
        val failing = contrastView(grey, white, true).suggestion
        assertEquals("AA", failing?.level)
        assertEquals(4.5, failing?.target)
        assertNotNull(failing?.color)
        assertNotNull(failing?.colorText)

        val aaOnly = contrastView(CssColor.fromRgb8(118, 118, 118), white, true).suggestion
        assertEquals("AAA", aaOnly?.level)
        assertEquals(7.0, aaOnly?.target)

        assertNull(contrastView(black, white, true).suggestion)
    }

    @Test
    fun `an unreachable AAA target gives a suggestion without color`() {
        // Black is already the darkest text; on mid grey AA passes (about 5.3) but AAA cannot be reached.
        val s = contrastView(black, CssColor.fromRgb8(128, 128, 128), true).suggestion
        assertNotNull(s)
        assertEquals("AAA", s.level)
        assertNull(s.color)
        assertNull(s.colorText)
        assertEquals("No color with this hue reaches it", s.noneText)
        assertEquals("Nearest text color passing AAA:", s.title)
    }

    @Test
    fun `the suggested color itself passes as an 8-bit color`() {
        val cases =
            listOf(
                Triple(grey, white, 4.5),
                Triple(CssColor.fromRgb8(118, 118, 118), white, 7.0),
                Triple(CssColor.fromRgb8(250, 120, 120), CssColor.fromRgb8(255, 240, 200), 4.5),
                Triple(CssColor.fromRgb8(90, 90, 160), black, 4.5),
                Triple(CssColor.fromRgb8(60, 130, 246), CssColor.fromRgb8(20, 30, 90), 4.5),
            )
        for ((text, bg, target) in cases) {
            val s = contrastView(text, bg, true).suggestion
            val color = assertNotNull(s?.color, "$text on $bg")
            assertTrue(contrastRatio(color, bg) >= target, "${s?.colorText} on $bg for $target")
            assertEquals(s?.colorText, cssText(color))
        }
        assertTrue(contrastView(grey, white, true).suggestion?.colorText != "#777777")
    }
}
