package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.CssColorParser
import dev.sluide.coloring.color.cssText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorMixCssTest {
    private val blue = CssColor.fromRgb8(0x3b, 0x82, 0xf6)
    private val white = CssColor.srgb(1.0, 1.0, 1.0)
    private val black = CssColor.srgb(0.0, 0.0, 0.0)

    @Test
    fun `color-mix text writes both percentages`() {
        assertEquals(
            "color-mix(in oklch, #3b82f6 50%, #ffffff 50%)",
            colorMixText(blue, white, 50.0, MixSpace.OKLCH),
        )
        assertEquals(
            "color-mix(in srgb, #3b82f6 87.5%, #ffffff 12.5%)",
            colorMixText(blue, white, 12.5, MixSpace.SRGB),
        )
    }

    @Test
    fun `an out-of-gamut input is written as oklch`() {
        val wide = CssColorParser.parse("oklch(70% 0.4 150)")!!

        assertEquals(
            "color-mix(in srgb, oklch(70% 0.4 150) 50%, #ffffff 50%)",
            colorMixText(wide, white, 50.0, MixSpace.SRGB),
        )
    }

    @Test
    fun `nine steps move monotonically from the first towards the second color`() {
        val steps = mixSteps(blue, black, MixSpace.OKLAB)

        assertEquals(9, steps.size)
        val lightness = steps.map { it.to(ColorSpace.OKLAB).c0 }
        for (i in 1 until lightness.size) assertTrue(lightness[i] < lightness[i - 1], "$lightness")
    }

    @Test
    fun `the shown mix is that of the colors as the emitted text reads them`() {
        val exact = CssColor.srgb(0.123, 0.5, 0.7)

        assertEquals(
            colorMix(CssColorParser.parse(cssText(exact))!!, 50.0, white, 50.0, MixSpace.SRGB),
            mixColor(exact, white, 50.0, MixSpace.SRGB),
        )
    }

    @Test
    fun `flatten text is the solid color of a translucent overlay`() {
        assertEquals("#808080", flattenText(black, 50.0, white))
        assertEquals(cssText(white), flattenText(black, 0.0, white))
    }

    @Test
    fun `the opacity multiplies the alpha of a translucent foreground`() {
        val half = CssColor.srgb(0.0, 0.0, 0.0, 0.5)

        assertEquals("#bfbfbf", flattenText(half, 50.0, white))
    }

    @Test
    fun `emitted css keeps none and the hue that is still known`() {
        assertEquals("oklch(70% none 150)", emittedCss(CssColorParser.parse("oklch(0.7 none 150)")!!))
        assertEquals("oklch(70% 0.1 none)", emittedCss(CssColorParser.parse("oklch(0.7 0.1 none)")!!))
        assertEquals("oklch(none 0.1 150 / none)", emittedCss(CssColorParser.parse("oklch(none 0.1 150 / none)")!!))
        assertEquals("#3b82f6", emittedCss(blue))
    }

    @Test
    fun `a missing component is mixed as a browser mixes it`() {
        val noChroma = CssColorParser.parse("oklch(0.7 none 150)")!!

        assertEquals(
            "color-mix(in oklch, oklch(70% none 150) 50%, #ffffff 50%)",
            colorMixText(noChroma, white, 50.0, MixSpace.OKLCH),
        )
        assertEquals(noChroma, asEmitted(noChroma))
    }
}
