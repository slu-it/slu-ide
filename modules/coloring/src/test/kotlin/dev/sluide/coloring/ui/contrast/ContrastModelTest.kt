package dev.sluide.coloring.ui.contrast

import dev.sluide.coloring.analysis.contrastRatio
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import dev.sluide.coloring.color.toRgb8
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
        val s = contrastView(black, white, ContrastRole.TEXT)
        assertEquals("21.00 : 1", s.ratioText)
        assertTrue(s.badges.all { it.pass })
        assertNull(s.suggestion)
        assertTrue(s.apcaText.startsWith("Lc 10"), s.apcaText)
    }

    @Test
    fun `verdict flags at the thresholds`() {
        val s = contrastView(grey, white, ContrastRole.TEXT)
        assertEquals(
            listOf("AA normal", "AA large", "AAA normal", "AAA large", "UI components"),
            s.badges.map { it.label },
        )
        // 150 grey on white is about 2.9 : 1, 118 grey about 4.5 : 1
        assertEquals(listOf(false, false, false, false, false), s.badges.map { it.pass })
        val mid = contrastView(CssColor.fromRgb8(118, 118, 118), white, ContrastRole.TEXT)
        assertEquals(listOf(true, true, false, true, true), mid.badges.map { it.pass })
    }

    @Test
    fun `the sample is always the text on the background`() {
        val s = contrastView(black, white, ContrastRole.BACKGROUND)
        assertEquals(black, s.text)
        assertEquals(white, s.background)
        assertTrue(s.apcaText.startsWith("Lc 10"), s.apcaText)
    }

    @Test
    fun `the text suggestion is titled for the text`() {
        val s = contrastView(CssColor.fromRgb8(0x77, 0x77, 0x77), white, ContrastRole.TEXT).suggestion
        assertNotNull(s?.color)
        assertEquals(ContrastRole.TEXT, s.role)
        assertEquals("Nearest text color passing AA:", s.title)
    }

    @Test
    fun `the background suggestion keeps the text`() {
        val text = CssColor.fromRgb8(0x77, 0x77, 0x77)
        val s = contrastView(text, white, ContrastRole.BACKGROUND).suggestion
        val color = assertNotNull(s?.color)
        assertEquals(ContrastRole.BACKGROUND, s.role)
        assertEquals("Nearest background passing AA:", s.title)
        assertTrue(contrastRatio(text, color) >= 4.5)
        assertTrue(color != white)
    }

    @Test
    fun `a translucent text is measured over the suggested background`() {
        val text = CssColor.srgb(1.0, 1.0, 1.0, 0.5)
        val s = contrastView(text, CssColor.fromRgb8(0x4d, 0x4d, 0x4d), ContrastRole.BACKGROUND).suggestion
        val color = assertNotNull(s?.color)
        assertTrue(contrastRatio(text, color) >= 4.5)
    }

    @Test
    fun `the suggested background is the nearest one for a translucent text`() {
        val text = CssColor.srgb(0.0, 0.0, 0.0, 0.8)
        val s = contrastView(text, CssColor.fromRgb8(0x80, 0x80, 0x80), ContrastRole.BACKGROUND).suggestion
        val color = assertNotNull(s?.color)
        assertTrue(contrastRatio(text, color) >= 4.5)
        assertTrue(color.toRgb8().r < 0x90, cssText(color))
    }

    @Test
    fun `the suggestion targets AA when it fails, AAA when only that fails, none when both pass`() {
        val failing = contrastView(grey, white, ContrastRole.TEXT).suggestion
        assertEquals("AA", failing?.level)
        assertEquals(4.5, failing?.target)
        assertNotNull(failing?.color)
        assertNotNull(failing?.colorText)

        val aaOnly = contrastView(CssColor.fromRgb8(118, 118, 118), white, ContrastRole.TEXT).suggestion
        assertEquals("AAA", aaOnly?.level)
        assertEquals(7.0, aaOnly?.target)

        assertNull(contrastView(black, white, ContrastRole.TEXT).suggestion)
    }

    @Test
    fun `an unreachable AAA target gives a suggestion without color`() {
        // Black is already the darkest text; on mid grey AA passes (about 5.3) but AAA cannot be reached.
        val s = contrastView(black, CssColor.fromRgb8(128, 128, 128), ContrastRole.TEXT).suggestion
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
            val s = contrastView(text, bg, ContrastRole.TEXT).suggestion
            val color = assertNotNull(s?.color, "$text on $bg")
            assertTrue(contrastRatio(color, bg) >= target, "${s?.colorText} on $bg for $target")
            assertEquals(s?.colorText, cssText(color))
        }
        assertTrue(contrastView(grey, white, ContrastRole.TEXT).suggestion?.colorText != "#777777")
    }
}
