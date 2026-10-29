package dev.sluide.coloring.ui.vision

import dev.sluide.coloring.analysis.Deficiency
import dev.sluide.coloring.analysis.simulate
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisionModelTest {
    private val red = CssColor.fromRgb8(255, 0, 0)
    private val darkGreen = CssColor.fromRgb8(0, 20, 0)
    private val white = CssColor.fromRgb8(255, 255, 255)
    private val black = CssColor.fromRgb8(0, 0, 0)

    @Test
    fun `there are five rows in order starting with normal vision`() {
        val rows = visionRows(red, darkGreen, true)

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
        val rows = visionRows(red, darkGreen, true)

        assertEquals(red, rows[0].current)
        assertEquals(red, rows[0].text)
        assertEquals(darkGreen, rows[0].background)
        assertEquals(simulate(red, Deficiency.DEUTERANOPIA), rows[2].current)
        assertEquals(simulate(darkGreen, Deficiency.DEUTERANOPIA), rows[2].background)
    }

    @Test
    fun `the roles of the pair follow currentIsText`() {
        val asText = visionRows(red, darkGreen, true)[1]
        val asBackground = visionRows(red, darkGreen, false)[1]

        assertEquals(asText.text, asBackground.background)
        assertEquals(asText.background, asBackground.text)
        assertEquals(asText.current, asBackground.current)
    }

    @Test
    fun `the ratio text of a row is the truncated WCAG ratio`() {
        assertEquals("21.00 : 1", visionRows(white, black, false)[0].ratioText)
    }

    @Test
    fun `a pair that passes normally but fails for protanopia gets a warning`() {
        val rows = visionRows(red, darkGreen, true)

        assertEquals("4.77 : 1", rows[0].ratioText)
        assertEquals(listOf(true, false, true, true, true), rows.map { it.passes })
        assertEquals(
            "The pair loses contrast for people with protanopia; increase the lightness difference.",
            warning(rows),
        )
    }

    @Test
    fun `no warning when every row passes`() {
        assertNull(warning(visionRows(black, white, true)))
    }

    @Test
    fun `no warning when the pair already fails with normal vision`() {
        val rows = visionRows(red, CssColor.fromRgb8(0, 50, 0), true)

        assertFalse(rows[0].passes)
        assertNull(warning(rows))
    }

    @Test
    fun `several failing deficiencies are listed with and before the last`() {
        val rows = visionRows(red, darkGreen, true).map { if (it.deficiency == null) it else it.copy(passes = false) }

        assertEquals(
            "The pair loses contrast for people with protanopia, deuteranopia, tritanopia and achromatopsia; " +
                "increase the lightness difference.",
            warning(rows),
        )
    }

    @Test
    fun `a translucent pair is composited before it is simulated`() {
        val rows = visionRows(CssColor.srgb(1.0, 0.0, 0.0, 0.5), white, true)

        val gray = rows[4]
        assertEquals("#ffffff", cssText(gray.background))
        assertEquals(1.0, gray.text.alpha, 1e-9)
        assertEquals("#a6a6a6", cssText(gray.text))
        assertEquals("2.43 : 1", gray.ratioText)
        assertEquals(0.5, gray.current.alpha, 1e-9)
    }
}
