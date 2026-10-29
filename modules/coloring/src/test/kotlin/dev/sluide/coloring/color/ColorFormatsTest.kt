package dev.sluide.coloring.color

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColorFormatsTest {
    private fun parse(text: String): CssColor = assertNotNull(CssColorParser.parse(text), text)

    private fun texts(color: CssColor): Map<ColorFormat, String?> =
        ColorFormat.entries.associateWith { format(color, it)?.text }

    @Test
    fun `orange in every format`() {
        assertEquals(
            mapOf(
                ColorFormat.HEX to "#ff8000",
                ColorFormat.RGB to "rgb(255, 128, 0)",
                ColorFormat.HSL to "hsl(30.1, 100%, 50%)",
                ColorFormat.NAME to null,
                ColorFormat.HWB to "hwb(30.1 0% 0%)",
                ColorFormat.LAB to "lab(67.82 45.49 74.84)",
                ColorFormat.LCH to "lch(67.82 87.58 58.71)",
                ColorFormat.OKLAB to "oklab(73.19% 0.11186 0.14836)",
                ColorFormat.OKLCH to "oklch(73.19% 0.1858 52.98)",
                ColorFormat.HSV to "hsv(30.1, 100%, 100%)",
                ColorFormat.CMYK to "cmyk(0%, 49.8%, 100%, 0%)",
            ),
            texts(parse("#ff8000")),
        )
    }

    @Test
    fun `alpha, short hex, white, named and transparent`() {
        val half = texts(parse("#ff000080"))
        assertEquals("#ff000080", half[ColorFormat.HEX])
        assertEquals("rgba(255, 0, 0, 0.502)", half[ColorFormat.RGB])
        assertTrue(half[ColorFormat.HWB]!!.endsWith(" / 0.502)"))
        assertTrue(half[ColorFormat.HSL]!!.startsWith("hsla(0, 100%, 50%, 0.502"))
        assertNull(half[ColorFormat.NAME])
        assertEquals("#000000", format(parse("#000"), ColorFormat.HEX)?.text)
        assertEquals("white", format(parse("#fff"), ColorFormat.NAME)?.text)
        assertEquals("rebeccapurple", format(parse("rebeccapurple"), ColorFormat.NAME)?.text)
        assertEquals("transparent", format(parse("transparent"), ColorFormat.NAME)?.text)
        assertEquals("#00000000", format(parse("transparent"), ColorFormat.HEX)?.text)
    }

    @Test
    fun `wide gamut colors are approximate only in sRGB based formats`() {
        val color = parse("oklch(0.7 0.4 150)")
        val all = ColorFormat.entries.mapNotNull { format(color, it) }
        for (f in all) {
            val srgbBased = f.format !in setOf(ColorFormat.LAB, ColorFormat.LCH, ColorFormat.OKLAB, ColorFormat.OKLCH)
            assertEquals(srgbBased, f.approximate, f.format.toString())
        }
        assertTrue(color.outOfSrgb)
        assertEquals("oklch(70% 0.4 150)", format(color, ColorFormat.OKLCH)?.text)
        assertFalse(parse("#336699").outOfSrgb)
    }

    @Test
    fun `groups hold the named formats and skip a missing name`() {
        val groups = formatGroups(parse("#336699"))
        assertEquals(listOf("CSS", "Modern CSS", "Design tools"), groups.map { it.title })
        assertEquals(listOf("HEX", "RGB", "HSL"), groups[0].entries.map { it.format.name })
        assertEquals(5, groups[1].entries.size)
        assertEquals(2, groups[2].entries.size)
        assertEquals(4, formatGroups(parse("red"))[0].entries.size)
    }

    @Test
    fun `achromatic hue prints 0 and no number is negative zero`() {
        assertEquals("hsl(0, 0%, 50.2%)", format(parse("#808080"), ColorFormat.HSL)?.text)
        assertEquals("lch(53.59 0 0)", format(parse("#808080"), ColorFormat.LCH)?.text)
        assertEquals("oklch(50% 0 0)", format(parse("oklch(0.5 none none)"), ColorFormat.OKLCH)?.text)
        assertEquals(
            "oklch(50% 0.1 0)",
            format(CssColor(ColorSpace.OKLCH, 0.5, 0.1, 359.9999), ColorFormat.OKLCH)?.text,
        )
    }

    @Test
    fun `a parsed notation is reproduced in its own space`() {
        assertEquals("oklch(62.8% 0.2577 29.23)", format(parse("oklch(0.628 0.2577 29.23)"), ColorFormat.OKLCH)?.text)
        assertEquals("lab(54.29 80.8 69.89)", format(parse("lab(54.29 80.8 69.89)"), ColorFormat.LAB)?.text)
        assertEquals("oklab(62.8% 0.2249 0.1258)", format(parse("oklab(62.8% 0.2249 0.1258)"), ColorFormat.OKLAB)?.text)
    }

    @Test
    fun `every format of an 8-bit color parses back to the same 8-bit color`() {
        val random = Random(42)
        val colors =
            (0 until 20000).map { Rgb8(random.nextInt(256), random.nextInt(256), random.nextInt(256)) } +
                (0 until 100).map {
                    Rgb8(random.nextInt(256), random.nextInt(256), random.nextInt(256), random.nextInt(256))
                } + listOf(Rgb8(0, 0, 0), Rgb8(255, 255, 255), Rgb8(0, 0, 0, 0))
        val failures = mutableListOf<String>()
        for (rgb in colors) {
            val original = CssColor.fromRgb8(rgb.r, rgb.g, rgb.b, rgb.a)
            for (f in ColorFormat.entries - ColorFormat.NAME) {
                val text = format(original, f)!!.text
                val back = CssColorParser.parse(text)?.toRgb8()
                if (back != rgb) failures += "$rgb $text -> $back"
            }
        }
        assertEquals(emptyList(), failures.take(10), "${failures.size} failures")
    }

    @Test
    fun `nearest named color`() {
        assertEquals(NamedMatch("rebeccapurple", 0.0, true), nearestNamed(parse("#663399")))
        val near = nearestNamed(parse("#ff0001"))
        assertEquals("red", near.name)
        assertFalse(near.exact)
        assertTrue(near.distance > 0)
        assertEquals("red", nearestNamed(parse("#ff000080")).name)
        val wide = nearestNamed(parse("color(srgb 1.2 0 0)"))
        assertFalse(wide.exact)
        assertTrue(wide.distance > 0)
    }

    @Test
    fun `css text is hex inside the gamut and oklch outside`() {
        assertEquals("#ff8000", cssText(parse("rgb(255 128 0)")))
        assertEquals("#ff800080", cssText(parse("#ff800080")))
        assertEquals(
            "rgb(255, 128, 0)",
            format(CssColor.fromRgb8(255, 128, 0, 254).copy(alpha = 0.9999), ColorFormat.RGB)?.text,
        )
        assertEquals("oklch(70% 0.4 150)", cssText(parse("oklch(0.7 0.4 150)")))
    }

    @Test
    fun `css text keeps none in oklch colors`() {
        assertEquals("oklch(70% 0.1 none)", cssText(parse("oklch(0.7 0.1 none)")))
        assertEquals("oklch(none 0.1 150 / none)", cssText(parse("oklch(none 0.1 150 / none)")))
    }
}
