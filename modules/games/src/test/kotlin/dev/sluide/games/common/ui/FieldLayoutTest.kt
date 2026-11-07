package dev.sluide.games.common.ui

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldLayoutTest {
    private val layout = FieldLayout(320, 240)

    @Test
    fun `a wide area is limited by its height and the field is centred horizontally`() {
        val p = layout.fit(1000.0, 480.0, 1.0)

        assertEquals(2.0, p.scale)
        assertEquals((1000.0 - 640.0) / 2, p.offsetX)
        assertEquals(0.0, p.offsetY)
    }

    @Test
    fun `a tall area is limited by its width and the field is centred vertically`() {
        val p = layout.fit(640.0, 1000.0, 1.0)

        assertEquals(2.0, p.scale)
        assertEquals(0.0, p.offsetX)
        assertEquals((1000.0 - 480.0) / 2, p.offsetY)
    }

    @Test
    fun `the scale is at least 1 in a small area`() {
        val p = layout.fit(100.0, 50.0, 1.0)

        assertEquals(1.0, p.scale)
    }

    @Test
    fun `positions snap to device pixels at output scale 1`() {
        val p = FieldPlacement(1.5, 10.4, 3.0, 1.0, 320, 240)

        assertEquals(10.0 + 1.0, p.x(1.0)) // round(10.4) + floor(1.5)
        assertEquals(10.0 + 4.0, p.x(3.0)) // floor(4.5)
        assertEquals(3.0 + 7.0, p.y(5.0)) // floor(7.5)
    }

    @Test
    fun `positions snap to device pixels at output scale 1_5 and 2`() {
        val p15 = FieldPlacement(1.3, 2.0, 0.0, 1.5, 320, 240)
        // round(3) + floor(2 * 1.3 * 1.5 = 3.9) = 6 device pixels
        assertEquals(6.0 / 1.5, p15.x(2.0))

        val p2 = FieldPlacement(1.3, 0.3, 0.0, 2.0, 320, 240)
        // round(0.6) + floor(3 * 1.3 * 2 = 7.8) = 1 + 7 = 8 device pixels
        assertEquals(8.0 / 2.0, p2.x(3.0))
    }

    @ParameterizedTest
    @ValueSource(doubles = [1.0, 1.5, 2.0, 2.5])
    fun `the edges of adjacent field pixels touch without gaps and the field has its snapped size`(os: Double) {
        val p = layout.fit(777.0, 555.0, os)

        // One edge function serves both neighbours, so every pixel is between 1 and ceil(scale) device pixels wide.
        var sum = 0.0
        for (px in 0 until 320) {
            val w = (p.x(px + 1.0) - p.x(px.toDouble())) * os
            assertTrue(w >= 1.0 - 1e-9 && w <= Math.ceil(p.scale * os) + 1e-9, "pixel $px is $w device pixels wide")
            sum += w
        }
        assertEquals(p.width * os, sum, 1e-6)
        assertEquals(p.x(320.0) - p.x(0.0), p.width)
        assertEquals(p.y(240.0) - p.y(0.0), p.height)
        // Every edge lies on a device pixel.
        for (px in 0..320) {
            val device = p.x(px.toDouble()) * os
            assertEquals(Math.rint(device).toDouble(), device, 1e-9)
        }
    }
}
