package dev.sluide.games.common

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZoomRangeTest {
    private val aboveFit = ZoomRange.ABOVE_FIT
    private val upToFit = ZoomRange.UP_TO_FIT

    @Test
    fun `the ranges reach from a quarter to twice the Fit size and to Fit`() {
        assertEquals(0.25, aboveFit.min)
        assertEquals(2.0, aboveFit.max)
        assertEquals(0.25, upToFit.min)
        assertEquals(1.0, upToFit.max)
    }

    @ParameterizedTest
    @CsvSource(
        // from a preset
        "0.25, 0.5",
        "0.75, 1.0",
        "1.0, 1.25",
        "1.5, 2.0",
        // from a value between presets, or a hair above a preset
        "0.6, 0.75",
        "1.1, 1.25",
        "1.9, 2.0",
        "0.9995, 1.25",
        // at and beyond the bounds
        "2.0, 2.0",
        "3.0, 2.0",
        "0.1, 0.25",
    )
    fun `zooming in steps to the next preset above, at most the maximum`(
        zoom: Double,
        expected: Double,
    ) {
        assertEquals(expected, aboveFit.zoomIn(zoom))
    }

    @ParameterizedTest
    @CsvSource(
        "2.0, 1.5",
        "1.25, 1.0",
        "1.0, 0.75",
        "0.5, 0.25",
        "1.9, 1.5",
        "0.6, 0.5",
        "1.0005, 0.75",
        "0.25, 0.25",
        "0.1, 0.25",
    )
    fun `zooming out steps to the next preset below, at least the minimum`(
        zoom: Double,
        expected: Double,
    ) {
        assertEquals(expected, aboveFit.zoomOut(zoom))
    }

    @Test
    fun `the range up to Fit stops at Fit`() {
        assertEquals(1.0, upToFit.zoomIn(0.75))
        assertEquals(1.0, upToFit.zoomIn(0.9))
        assertEquals(1.0, upToFit.zoomIn(1.0))
        assertEquals(0.75, upToFit.zoomOut(1.0))
        assertEquals(0.25, upToFit.zoomOut(0.3))
        assertEquals(1.0, upToFit.clamp(1.5))
    }

    @ParameterizedTest
    @CsvSource(
        "0.6, 0.6",
        "0.1, 0.25",
        "5.0, 2.0",
        "0.9996, 1.0",
        "1.4991, 1.5",
        "NaN, 1.0",
        "Infinity, 1.0",
        "-Infinity, 1.0",
    )
    fun `clamping keeps the zoom within the range, snaps it to a preset it is at and makes no number Fit`(
        zoom: Double,
        expected: Double,
    ) {
        assertEquals(expected, aboveFit.clamp(zoom))
    }

    @ParameterizedTest
    @CsvSource("1.0, Fit", "0.9999, Fit", "0.25, 25%", "0.634, 63%", "2.0, 200%", "1.006, 101%")
    fun `the label is Fit or the rounded percentage`(
        zoom: Double,
        expected: String,
    ) {
        assertEquals(expected, aboveFit.label(zoom))
    }

    @Test
    fun `a zoom at a preset finds it, one between presets none`() {
        assertEquals(0.5, aboveFit.preset(0.5))
        assertEquals(1.0, aboveFit.preset(0.9999))
        assertNull(aboveFit.preset(0.6))
        assertNull(upToFit.preset(1.5))
    }
}
