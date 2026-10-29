package dev.sluide.coloring.color

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.Random
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorConversionsTest {
    private val red = CssColor.fromRgb8(255, 0, 0)
    private val white = CssColor.fromRgb8(255, 255, 255)

    private fun assertColor(
        expected: List<Double>,
        actual: CssColor,
        tol: Double,
        hueTol: Double = tol,
    ) {
        val values = listOf(actual.c0, actual.c1, actual.c2)
        val hue = actual.space.hueIndex
        expected.forEachIndexed { i, e ->
            assertEquals(e, values[i], if (i == hue) hueTol else tol, "${actual.space} component $i of $actual")
        }
    }

    @Test
    fun `red in OKLab and OKLCH`() {
        assertColor(listOf(0.62796, 0.22486, 0.12585), red.to(ColorSpace.OKLAB), 1e-4)
        assertColor(listOf(0.62796, 0.25768, 29.2339), red.to(ColorSpace.OKLCH), 1e-4, 1e-2)
    }

    @Test
    fun `red in Lab and LCH`() {
        assertColor(listOf(54.2905, 80.8049, 69.8910), red.to(ColorSpace.LAB), 1e-3)
        assertColor(listOf(54.2905, 106.8372, 40.8577), red.to(ColorSpace.LCH), 1e-3)
    }

    @Test
    fun `white in OKLab and Lab`() {
        assertColor(listOf(1.0, 0.0, 0.0), white.to(ColorSpace.OKLAB), 1e-4)
        assertColor(listOf(100.0, 0.0, 0.0), white.to(ColorSpace.LAB), 1e-3)
    }

    @Test
    fun `gray in HSL`() {
        assertColor(listOf(0.0, 0.0, 50.196), CssColor.fromRgb8(128, 128, 128).to(ColorSpace.HSL), 1e-3)
    }

    @Test
    fun `orange in HSV and HWB`() {
        val orange = CssColor.fromRgb8(255, 128, 0)
        assertColor(listOf(30.118, 100.0, 100.0), orange.to(ColorSpace.HSV), 1e-3)
        assertColor(listOf(30.118, 0.0, 0.0), orange.to(ColorSpace.HWB), 1e-3)
    }

    @Test
    fun `blue in OKLCH reaches the upper hue range`() {
        assertColor(listOf(0.45201, 0.31321, 264.052), CssColor.fromRgb8(0, 0, 255).to(ColorSpace.OKLCH), 1e-4, 1e-2)
    }

    @ParameterizedTest
    @MethodSource("hueSpaces")
    fun `an achromatic color has hue 0 and the hue is marked missing`(space: ColorSpace) {
        val gray = CssColor.fromRgb8(128, 128, 128).to(space)
        assertEquals(0.0, listOf(gray.c0, gray.c1, gray.c2)[space.hueIndex!!])
        assertTrue(gray.isHueMissing)
        assertEquals(1 shl space.hueIndex!!, gray.missing)
    }

    @Test
    fun `a chromatic color has no missing component`() {
        ColorSpace.entries.forEach { assertEquals(0, red.to(it).missing, "$it") }
    }

    @Test
    fun `conversion into another space drops the mask and into the same space keeps it`() {
        val none = CssColor(ColorSpace.OKLCH, 0.5, 0.2, 0.0, missing = 1 shl 2 or (1 shl CssColor.ALPHA))
        assertEquals(none, none.to(ColorSpace.OKLCH))
        assertEquals(0, none.to(ColorSpace.OKLAB).missing)
        assertTrue(none.isMissing(CssColor.ALPHA))
    }

    @Test
    fun `hues are normalized into 0 to 360 also within the same space`() {
        assertEquals(260.0, CssColor(ColorSpace.HSL, -100.0, 100.0, 50.0).to(ColorSpace.HSL).c0, 1e-9)
        assertEquals(30.0, CssColor(ColorSpace.OKLCH, 0.5, 0.1, 390.0).to(ColorSpace.OKLCH).c2, 1e-9)
        val negative = CssColor(ColorSpace.HSL, -100.0, 100.0, 50.0).to(ColorSpace.SRGB)
        val positive = CssColor(ColorSpace.HSL, 260.0, 100.0, 50.0).to(ColorSpace.SRGB)
        assertColor(listOf(positive.c0, positive.c1, positive.c2), negative, 1e-12)
    }

    @Test
    fun `an achromatic hue given in the same space becomes 0 and missing`() {
        val gray = CssColor(ColorSpace.HSL, 120.0, 0.0, 50.0).to(ColorSpace.HSL)
        assertEquals(0.0, gray.c0)
        assertTrue(gray.isHueMissing)
    }

    @Test
    fun `normalizeHue never returns 360`() {
        assertEquals(0.0, normalizeHue(-1e-17))
        assertEquals(0.0, normalizeHue(360.0))
        assertEquals(359.0, normalizeHue(-1.0), 1e-12)
    }

    @Test
    fun `a color with negative HSL saturation is turned into a positive one with the opposite hue`() {
        val outside = CssColor.srgb(1.5, 1.2, 1.0)
        val hsl = outside.to(ColorSpace.HSL)
        assertTrue(hsl.c1 >= 0.0)
        assertColor(listOf(1.5, 1.2, 1.0), hsl.to(ColorSpace.SRGB), 1e-9)
    }

    @ParameterizedTest
    @MethodSource("roundTrips")
    fun `a round trip through a space returns the color`(
        color: CssColor,
        space: ColorSpace,
    ) {
        assertColor(listOf(color.c0, color.c1, color.c2), color.to(space).to(ColorSpace.SRGB), 1e-6)
    }

    companion object {
        @JvmStatic
        fun hueSpaces(): Stream<ColorSpace> = ColorSpace.entries.filter { it.hasHue }.stream()

        @JvmStatic
        fun roundTrips(): Stream<Arguments> {
            val random = Random(42)
            val colors =
                mutableListOf(
                    CssColor.fromRgb8(255, 0, 0),
                    CssColor.fromRgb8(0, 255, 0),
                    CssColor.fromRgb8(0, 0, 255),
                    CssColor.fromRgb8(0, 0, 0),
                    CssColor.fromRgb8(255, 255, 255),
                    CssColor.fromRgb8(128, 128, 128),
                )
            repeat(4) { colors += CssColor.fromRgb8(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
            return colors.flatMap { c -> ColorSpace.entries.map { Arguments.of(c, it) } }.stream()
        }
    }
}
