package dev.sluide.coloring.color

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CssColorParserTest {
    data class Case(
        val text: String,
        val expected: CssColor,
        val lenient: Boolean = false,
    ) {
        override fun toString() = text
    }

    private fun c(
        space: ColorSpace,
        a: Double,
        b: Double,
        c: Double,
        alpha: Double = 1.0,
        missing: Int = 0,
    ) = CssColor(space, a, b, c, alpha, missing)

    private val s = ColorSpace.SRGB

    fun valid(): List<Case> =
        listOf(
            Case("#F00", c(s, 1.0, 0.0, 0.0)),
            Case("#f008", c(s, 1.0, 0.0, 0.0, 0x88 / 255.0)),
            Case("#ff000080", c(s, 1.0, 0.0, 0.0, 128 / 255.0)),
            Case("  #00ff00  ", c(s, 0.0, 1.0, 0.0)),
            Case("rgb(255,0,0)", c(s, 1.0, 0.0, 0.0)),
            Case("RGB(255, 0, 0)", c(s, 1.0, 0.0, 0.0)),
            Case("rgba(255, 0, 0, .5)", c(s, 1.0, 0.0, 0.0, 0.5)),
            Case("rgb(100%, 0%, 50%)", c(s, 1.0, 0.0, 0.5)),
            Case("rgb(300 -5 0)", c(s, 1.0, 0.0, 0.0)),
            Case("rgb(100% 0% 0% / 50%)", c(s, 1.0, 0.0, 0.0, 0.5)),
            Case("rgb(255 0 0/0.25)", c(s, 1.0, 0.0, 0.0, 0.25)),
            Case("rgb(255 0% 0)", c(s, 1.0, 0.0, 0.0)),
            Case("rgb(255 none 0)", c(s, 1.0, 0.0, 0.0, missing = 2)),
            Case("rgb(255 0 0 / none)", c(s, 1.0, 0.0, 0.0, 0.0, missing = 8)),
            Case("hsl(120deg 100% 50%)", c(ColorSpace.HSL, 120.0, 100.0, 50.0)),
            Case("hsl(0.5turn, 50%, 50%)", c(ColorSpace.HSL, 180.0, 50.0, 50.0)),
            Case("hsla(200, 50%, 40%, 0.3)", c(ColorSpace.HSL, 200.0, 50.0, 40.0, 0.3)),
            Case("hsl(200 50 40)", c(ColorSpace.HSL, 200.0, 50.0, 40.0)),
            Case("hsl(200 -50% 40%)", c(ColorSpace.HSL, 200.0, 0.0, 40.0)),
            Case("hsl(200grad 10% 10%)", c(ColorSpace.HSL, 180.0, 10.0, 10.0)),
            Case("hsl(-90 10% 10%)", c(ColorSpace.HSL, 270.0, 10.0, 10.0)),
            Case("hwb(90 10% 10%)", c(ColorSpace.HWB, 90.0, 10.0, 10.0)),
            Case("lab(50% 40 -20)", c(ColorSpace.LAB, 50.0, 40.0, -20.0)),
            Case("lab(120 100% -100%)", c(ColorSpace.LAB, 100.0, 125.0, -125.0)),
            Case("lch(52.2 72.2 50 / .5)", c(ColorSpace.LCH, 52.2, 72.2, 50.0, 0.5)),
            Case("lch(50% 100% 0.25turn)", c(ColorSpace.LCH, 50.0, 150.0, 90.0)),
            Case("oklab(0.5 0.1 -0.1)", c(ColorSpace.OKLAB, 0.5, 0.1, -0.1)),
            Case("oklab(50% 100% -50%)", c(ColorSpace.OKLAB, 0.5, 0.4, -0.2)),
            Case("oklch(70% 0.1 200)", c(ColorSpace.OKLCH, 0.7, 0.1, 200.0)),
            Case("oklch(0.5 0.1 none)", c(ColorSpace.OKLCH, 0.5, 0.1, 0.0, missing = 4)),
            Case("color(srgb 1 0 0)", c(s, 1.0, 0.0, 0.0)),
            Case("color(srgb 100% 0% 0% / 0.5)", c(s, 1.0, 0.0, 0.0, 0.5)),
            Case("color(srgb-linear 0.5 0.25 0)", c(ColorSpace.SRGB_LINEAR, 0.5, 0.25, 0.0)),
            Case("color(xyz 0.1 0.2 0.3)", c(ColorSpace.XYZ_D65, 0.1, 0.2, 0.3)),
            Case("color(xyz-d65 0.1 0.2 0.3)", c(ColorSpace.XYZ_D65, 0.1, 0.2, 0.3)),
            Case("color(xyz-d50 0.1 0.2 0.3)", c(ColorSpace.XYZ_D50, 0.1, 0.2, 0.3)),
            Case("RebeccaPurple", c(s, 0x66 / 255.0, 0x33 / 255.0, 0x99 / 255.0)),
            Case("transparent", c(s, 0.0, 0.0, 0.0, 0.0)),
            Case("hsv(30, 100%, 100%)", c(ColorSpace.HSV, 30.0, 100.0, 100.0)),
            Case("hsb(30 50% 40% / 0.5)", c(ColorSpace.HSV, 30.0, 50.0, 40.0, 0.5)),
            Case("cmyk(0%, 50%, 100%, 0%)", c(s, 1.0, 0.5, 0.0)),
            Case("device-cmyk(0 0.5 1 0 / 0.5)", c(s, 1.0, 0.5, 0.0, 0.5)),
            Case("rgb(1 2 3 / 2)", c(s, 1 / 255.0, 2 / 255.0, 3 / 255.0, 1.0)),
            Case("rgb(1 2 3 / -1)", c(s, 1 / 255.0, 2 / 255.0, 3 / 255.0, 0.0)),
            Case("rgb(1 2 3 / 150%)", c(s, 1 / 255.0, 2 / 255.0, 3 / 255.0, 1.0)),
            Case("lch(50 -10 0)", c(ColorSpace.LCH, 50.0, 0.0, 0.0)),
            Case("oklch(0.5 100% 0)", c(ColorSpace.OKLCH, 0.5, 0.4, 0.0)),
            Case("oklab(150% 0 0)", c(ColorSpace.OKLAB, 1.0, 0.0, 0.0)),
            Case("lab(-5 0 0)", c(ColorSpace.LAB, 0.0, 0.0, 0.0)),
            Case("rgb(1e3 +.5 -0)", c(s, 1.0, 0.5 / 255.0, 0.0)),
            Case("hsl(3.14159265358979rad 10% 10%)", c(ColorSpace.HSL, 180.0, 10.0, 10.0)),
            Case("hsv(10 -5% 50%)", c(ColorSpace.HSV, 10.0, 0.0, 50.0)),
            Case("ff8000", c(s, 1.0, 128 / 255.0, 0.0), lenient = true),
            Case("#fff;", c(s, 1.0, 1.0, 1.0), lenient = true),
            Case("rgb(1 2 3);", c(s, 1 / 255.0, 2 / 255.0, 3 / 255.0), lenient = true),
            Case("abc", c(s, 0xaa / 255.0, 0xbb / 255.0, 0xcc / 255.0), lenient = true),
        )

    @ParameterizedTest
    @MethodSource("valid")
    fun `valid notations parse in their own space`(case: Case) {
        val got = assertNotNull(CssColorParser.parse(case.text, case.lenient))
        val e = case.expected
        assertEquals(e.space, got.space)
        assertEquals(e.c0, got.c0, 1e-9)
        assertEquals(e.c1, got.c1, 1e-9)
        assertEquals(e.c2, got.c2, 1e-9)
        assertEquals(e.alpha, got.alpha, 1e-9)
        assertEquals(e.missing, got.missing)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "   ", "#", "#ff", "#ggg", "#fffff", "rgb(255 0)", "rgb(255, 0 0)", "rgb(255 0 0, 1)", "rgb(1,2,3,4,5)",
            "rgb(255, 0%, 0)", "rgb(255, none, 0)", "rgb(255 0 0 / 1 / 1)", "rgb(255 0 0 /)", "rgb(255 0 0 0)",
            "hsl(120, 100, 50)", "hsl(120, 100%, 50%, )", "hwb(90, 10%, 10%)", "lab(50, 40, -20)", "oklch(1 2 3deg4)",
            "lab(50% 40deg 1)", "hsl(50% 10% 10%)", "rgb(var(--x))", "rgb(from red r g b)", "calc(1 + 2)",
            "rgb(calc(1) 2 3)", "ff8000", "abc", "color(display-p3 1 0 0)", "color(srgb 1 0)", "color(srgb, 1, 0, 0)",
            "color()", "currentcolor", "inherit", "rgb(255 0 0) x", "x rgb(255 0 0)", "rgb(255 0 0", "foo(1 2 3)",
            "hsl(1e999 0% 0%)", "lab(50 1e200 0)", "rgb (1 2 3)", "rgb\n(1 2 3)", "rgb(1. 2 3)",
            "hsl(none, 10%, 10%)", "rgba(255, 0, 0, none)", "cmyk(0, 0, 0)", "cmyk(none 0 0 0)", "#fff;", "notacolor",
        ],
    )
    fun `invalid notations are null`(text: String) {
        assertNull(CssColorParser.parse(text))
    }

    @Test
    fun `lenient still rejects garbage`() {
        assertNull(CssColorParser.parse("ff800", lenient = true))
        assertNull(CssColorParser.parse("gg0000", lenient = true))
        assertNull(CssColorParser.parse(";", lenient = true))
    }

    @Test
    fun `equivalent notations give the same sRGB`() {
        val texts = listOf("rgb(255 0 0)", "#f00", "red", "hsl(0 100% 50%)", "hwb(0 0% 0%)", "color(srgb 1 0 0)")
        for (t in texts) {
            val srgb = assertNotNull(CssColorParser.parse(t)).toSrgb()
            assertEquals(1.0, srgb.c0, 1e-6, t)
            assertEquals(0.0, srgb.c1, 1e-6, t)
            assertEquals(0.0, srgb.c2, 1e-6, t)
        }
    }
}
