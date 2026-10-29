package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.CssColorParser
import dev.sluide.coloring.color.toRgb8
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GradientsTest {
    private val blue = CssColor.fromRgb8(0x3b, 0x82, 0xf6)
    private val white = CssColor.fromRgb8(255, 255, 255)
    private val pureBlue = CssColor.fromRgb8(0, 0, 255)
    private val yellow = CssColor.fromRgb8(255, 255, 0)

    private fun gradient(
        type: GradientType = GradientType.LINEAR,
        angle: Double = 90.0,
        space: MixSpace? = null,
        a: CssColor = blue,
        b: CssColor = white,
    ) = Gradient(type, angle, listOf(GradientStop(a, 0.0), GradientStop(b, 1.0)), space)

    private fun threeStops(middle: Double) =
        gradient().copy(stops = listOf(GradientStop(blue, 0.0), GradientStop(white, middle), GradientStop(blue, 1.0)))

    @Test
    fun `css of a linear gradient with and without a space`() {
        assertEquals("linear-gradient(90deg, #3b82f6 0%, #ffffff 100%)", css(gradient()))
        assertEquals(
            "linear-gradient(in oklch 45.5deg, #3b82f6 0%, #ffffff 100%)",
            css(gradient(angle = 45.5, space = MixSpace.OKLCH)),
        )
    }

    @Test
    fun `css of radial and conic gradients puts the space where CSS Images 4 allows it`() {
        assertEquals("radial-gradient(circle, #3b82f6 0%, #ffffff 100%)", css(gradient(GradientType.RADIAL)))
        assertEquals(
            "radial-gradient(circle in oklab, #3b82f6 0%, #ffffff 100%)",
            css(gradient(GradientType.RADIAL, space = MixSpace.OKLAB)),
        )
        assertEquals(
            "conic-gradient(from 90deg in oklch, #3b82f6 0%, #ffffff 100%)",
            css(gradient(GradientType.CONIC, space = MixSpace.OKLCH)),
        )
    }

    @Test
    fun `a stop with a missing component is written with none like the mix text`() {
        val noChroma = CssColorParser.parse("oklch(0.7 none 150)")!!
        val noHue = CssColorParser.parse("oklch(0.7 0.1 none)")!!
        assertEquals(
            "linear-gradient(in oklch 90deg, oklch(70% none 150) 0%, oklch(70% 0.1 none) 100%)",
            css(gradient(space = MixSpace.OKLCH, a = noChroma, b = noHue)),
        )
    }

    @Test
    fun `srgb without a space is written explicitly when a stop is not in legacy syntax`() {
        val modern = CssColorParser.parse("oklch(0.7 0.35 150)")!!
        assertEquals(
            "linear-gradient(in srgb 90deg, oklch(70% 0.35 150) 0%, #ffffff 100%)",
            css(gradient(a = modern)),
        )
        assertEquals("linear-gradient(90deg, #3b82f6 0%, #ffffff 100%)", css(gradient()))
    }

    @Test
    fun `positions are written as trimmed percents`() {
        val g = threeStops(1.0 / 3)
        assertEquals("linear-gradient(90deg, #3b82f6 0%, #ffffff 33.3%, #3b82f6 100%)", css(g))
        assertEquals("linear-gradient(90deg, #3b82f6 0%, #ffffff 50%, #3b82f6 100%)", css(threeStops(0.5)))
    }

    @Test
    fun `colorAt is the stop color at a stop and the ends beyond`() {
        val g = gradient()
        assertEquals(blue, colorAt(g, 0.0))
        assertEquals(white, colorAt(g, 1.0))
        assertEquals(blue, colorAt(g, -1.0))
    }

    @Test
    fun `colorAt in srgb and oklch differ in the middle of blue and yellow`() {
        val srgb = colorAt(gradient(a = pureBlue, b = yellow, space = null), 0.5).toRgb8()
        val oklch = colorAt(gradient(a = pureBlue, b = yellow, space = MixSpace.OKLCH), 0.5).toRgb8()
        assertEquals(listOf(128, 128, 128), listOf(srgb.r, srgb.g, srgb.b))
        assertNotEquals(srgb, oklch)
        assertTrue(maxOf(oklch.r, oklch.g, oklch.b) - minOf(oklch.r, oklch.g, oklch.b) > 40, "not gray: $oklch")
    }

    @Test
    fun `sample gives evenly spaced colors including both ends`() {
        val colors = sample(gradient(a = CssColor.fromRgb8(0, 0, 0), b = white), 3)
        assertEquals(3, colors.size)
        assertEquals(128, colors[1].toRgb8().r)
        assertEquals(white, colors[2])
    }

    @Test
    fun `linear endpoints put the corners on the 0 and 100 percent lines`() {
        fun assertPoint(
            x: Double,
            y: Double,
            p: Point,
        ) {
            assertEquals(x, p.x, 1e-9)
            assertEquals(y, p.y, 1e-9)
        }
        val (s0, e0) = linearEndpoints(0.0, 100.0, 100.0)
        assertPoint(50.0, 100.0, s0)
        assertPoint(50.0, 0.0, e0)
        val (s90, e90) = linearEndpoints(90.0, 200.0, 100.0)
        assertPoint(0.0, 50.0, s90)
        assertPoint(200.0, 50.0, e90)
        // 45deg in a square: from the bottom-left corner to the top-right corner.
        val (s45, e45) = linearEndpoints(45.0, 100.0, 100.0)
        assertPoint(0.0, 100.0, s45)
        assertPoint(100.0, 0.0, e45)
    }

    @Test
    fun `linear endpoints at 45deg in a wide box keep the corners at 0 and 100 percent`() {
        val (s, e) = linearEndpoints(45.0, 200.0, 100.0)
        val length = hypot(e.x - s.x, e.y - s.y)

        fun position(
            x: Double,
            y: Double,
        ) = ((x - s.x) * (e.x - s.x) + (y - s.y) * (e.y - s.y)) / (length * length)
        assertEquals(0.0, position(0.0, 100.0), 1e-9)
        assertEquals(1.0, position(200.0, 0.0), 1e-9)
        assertEquals(sin(Math.toRadians(45.0)), (e.x - s.x) / length, 1e-9)
        assertEquals(-cos(Math.toRadians(45.0)), (e.y - s.y) / length, 1e-9)
    }

    @Test
    fun `add stop inserts at the middle of the widest gap in the interpolated color`() {
        val g = threeStops(0.25)
        val (added, index) = addStop(g)
        assertEquals(2, index)
        assertEquals(4, added.stops.size)
        assertEquals(0.625, added.stops[2].position, 1e-9)
        assertEquals(colorAt(g, 0.625), added.stops[2].color)
    }

    @Test
    fun `remove keeps two stops`() {
        val three = threeStops(0.5)
        assertEquals(2, removeStop(three, 1).stops.size)
        assertEquals(2, removeStop(removeStop(three, 1), 0).stops.size)
    }

    @Test
    fun `move limits the position to 0 to 1 and keeps the order while it stays between the neighbours`() {
        val three = threeStops(0.5)
        assertEquals(0.4, moveStop(three, 1, 0.4).first.stops[1].position, 1e-9)
        assertEquals(1, moveStop(three, 1, 0.4).second)
        assertEquals(0.0, moveStop(three, 0, -3.0).first.stops[0].position, 1e-9)
    }

    @Test
    fun `a stop passes its neighbour and keeps its identity, also with two stops`() {
        val two = gradient()
        val (moved, index) = moveStop(two, 0, 1.0)
        assertEquals(1, index)
        assertEquals(listOf(white, blue), moved.stops.map { it.color })
        // Dragged back out of the stack at 100 %.
        val (back, backIndex) = moveStop(moved, index, 0.2)
        assertEquals(0, backIndex)
        assertEquals(listOf(blue, white), back.stops.map { it.color })

        val (past, pastIndex) = moveStop(threeStops(0.5), 0, 0.7)
        assertEquals(1, pastIndex)
        assertEquals(listOf(0.5, 0.7, 1.0), past.stops.map { it.position })
        assertEquals(blue, past.stops[1].color)
    }

    @Test
    fun `preview stops add samples inside each segment for oklch but use the real stops for srgb`() {
        assertEquals(listOf(0.0, 1.0), previewStops(gradient(space = null)).map { it.position })
        val oklch = previewStops(gradient(space = MixSpace.OKLCH), perSegment = 3)
        assertEquals(listOf(0.0, 0.25, 0.5, 0.75, 1.0), oklch.map { it.position })
        assertEquals(colorAt(gradient(space = MixSpace.OKLCH), 0.25), oklch[1].color)
    }

    @Test
    fun `two stops at one position stay a sharp edge in the preview stops`() {
        val hard =
            gradient(space = MixSpace.OKLCH).copy(
                stops =
                    listOf(
                        GradientStop(blue, 0.0),
                        GradientStop(white, 0.5),
                        GradientStop(yellow, 0.5),
                        GradientStop(blue, 1.0),
                    ),
            )
        val stops = previewStops(hard, perSegment = 2)
        assertEquals(listOf(white, yellow), stops.filter { it.position == 0.5 }.map { it.color })
        // 4 real stops and 2 samples in each of the 2 segments with a width.
        assertEquals(8, stops.size)
    }

    private fun t(
        g: Gradient,
        x: Double,
        y: Double,
    ) = gradientT(g, x, y, 200.0, 100.0)

    @Test
    fun `linear position runs along the gradient line`() {
        val to90 = gradient(angle = 90.0)
        assertEquals(0.0, t(to90, 0.0, 30.0), 1e-9)
        assertEquals(0.5, t(to90, 100.0, 80.0), 1e-9)
        assertEquals(1.0, t(to90, 200.0, 0.0), 1e-9)
        val up = gradient(angle = 0.0)
        assertEquals(1.0, t(up, 50.0, 0.0), 1e-9)
        assertEquals(0.0, t(up, 50.0, 100.0), 1e-9)
        // At 45deg the corners lie on the 0% and 100% lines.
        val diagonal = gradient(angle = 45.0)
        assertEquals(0.0, t(diagonal, 0.0, 100.0), 1e-9)
        assertEquals(1.0, t(diagonal, 200.0, 0.0), 1e-9)
        assertEquals(0.5, t(diagonal, 100.0, 50.0), 1e-9)
    }

    @Test
    fun `radial position is the distance from the center relative to the farthest corner`() {
        val radial = gradient(type = GradientType.RADIAL)
        assertEquals(0.0, t(radial, 100.0, 50.0), 1e-9)
        assertEquals(1.0, t(radial, 0.0, 0.0), 1e-9)
        assertEquals(50.0 / hypot(100.0, 50.0), t(radial, 100.0, 0.0), 1e-9)
    }

    @Test
    fun `conic position counts clockwise from the start angle`() {
        val conic = gradient(type = GradientType.CONIC, angle = 0.0)
        assertEquals(0.0, t(conic, 100.0, 0.0), 1e-9)
        assertEquals(0.25, t(conic, 200.0, 50.0), 1e-9)
        assertEquals(0.5, t(conic, 100.0, 100.0), 1e-9)
        assertEquals(0.75, t(conic, 0.0, 50.0), 1e-9)
        assertEquals(0.25, t(conic.copy(angleDeg = 90.0), 100.0, 100.0), 1e-9)
    }
}
