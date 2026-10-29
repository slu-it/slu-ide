package dev.sluide.coloring.ui.vision

import dev.sluide.coloring.analysis.Deficiency
import dev.sluide.coloring.analysis.simulate
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class VisionModelTest {
    private val red = CssColor.fromRgb8(255, 0, 0)
    private val darkGreen = CssColor.fromRgb8(0, 20, 0)
    private val white = CssColor.fromRgb8(255, 255, 255)
    private val black = CssColor.fromRgb8(0, 0, 0)

    @Test
    fun `there are five rows in order starting with normal vision`() {
        val rows = visionRows(red, darkGreen)

        assertEquals(
            listOf(
                null,
                Deficiency.PROTANOPIA,
                Deficiency.DEUTERANOPIA,
                Deficiency.TRITANOPIA,
                Deficiency.ACHROMATOPSIA,
            ),
            rows.map { it.deficiency },
        )
        assertEquals(
            listOf("Normal vision", "Protanopia", "Deuteranopia", "Tritanopia", "Achromatopsia"),
            rows.map { it.label },
        )
    }

    @Test
    fun `the normal row shows the inputs and the other rows the simulation`() {
        val rows = visionRows(red, darkGreen)

        assertEquals(red, rows[0].text)
        assertEquals(darkGreen, rows[0].background)
        assertEquals(simulate(red, Deficiency.DEUTERANOPIA), rows[2].text)
        assertEquals(simulate(darkGreen, Deficiency.DEUTERANOPIA), rows[2].background)
    }

    @Test
    fun `swapping the pair swaps text and background of every row`() {
        val normal = visionRows(red, darkGreen)
        val swapped = visionRows(darkGreen, red)

        normal.zip(swapped).forEach { (n, s) ->
            assertEquals(n.text, s.background)
            assertEquals(n.background, s.text)
        }
    }

    @Test
    fun `the ratio text of a row is the truncated WCAG ratio`() {
        assertEquals("21.00 : 1", visionRows(white, black)[0].ratioText)
    }

    @Test
    fun `a pair that passes normally but fails for protanopia gets a warning`() {
        val rows = visionRows(red, darkGreen)

        assertEquals("4.77 : 1", rows[0].ratioText)
        assertEquals(listOf(true, false, true, true, true), rows.map { it.passes })
        assertEquals(
            "The pair loses contrast for people with protanopia; increase the lightness difference.",
            warning(rows),
        )
    }

    @Test
    fun `no warning when every row passes`() {
        assertNull(warning(visionRows(black, white)))
    }

    @Test
    fun `no warning when the pair already fails with normal vision`() {
        val rows = visionRows(red, CssColor.fromRgb8(0, 50, 0))

        assertFalse(rows[0].passes)
        assertNull(warning(rows))
    }

    @Test
    fun `several failing deficiencies are listed with and before the last`() {
        val rows = visionRows(red, darkGreen).map { if (it.deficiency == null) it else it.copy(passes = false) }

        assertEquals(
            "The pair loses contrast for people with protanopia, deuteranopia, tritanopia and achromatopsia; " +
                "increase the lightness difference.",
            warning(rows),
        )
    }

    @Test
    fun `a translucent pair is composited before it is simulated`() {
        val rows = visionRows(CssColor.srgb(1.0, 0.0, 0.0, 0.5), white)

        val gray = rows[4]
        assertEquals("#ffffff", cssText(gray.background))
        assertEquals(1.0, gray.text.alpha, 1e-9)
        assertEquals("#a6a6a6", cssText(gray.text))
        assertEquals("2.43 : 1", gray.ratioText)
    }
}
