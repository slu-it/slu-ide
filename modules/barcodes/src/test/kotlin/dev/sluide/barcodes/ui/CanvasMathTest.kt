package dev.sluide.barcodes.ui

import dev.sluide.barcodes.engine.Box
import javafx.scene.paint.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CanvasMathTest {
    @Test
    fun `a code gets a whole scale and a whole offset, centred`() {
        val fit = fit(29, 29, 400.0, 380.0, wholePixels = true)
        assertEquals(Fit(13.0, 11.0, 1.0, 29, 29), fit)
    }

    @Test
    fun `at a render scale of 1_25 the edges are whole device pixels`() {
        val fit = checkNotNull(fit(29, 29, 400.0, 400.0, wholePixels = true, renderScaleX = 1.25, renderScaleY = 1.25))
        // 500 device pixels: 17 per module, 493 for the code, 3 device pixels on each side.
        assertEquals(17 / 1.25, fit.scale, 1e-9)
        assertEquals(3 / 1.25, fit.x, 1e-9)
        assertEquals(3 / 1.25, fit.y, 1e-9)
        assertEquals(0.0, (fit.x * 1.25) % 1, 1e-9)
        assertEquals(0.0, (fit.scale * 1.25) % 1, 1e-9)
    }

    @Test
    fun `a code too big for its room shrinks without a whole scale`() {
        val fit = checkNotNull(fit(200, 100, 100.0, 100.0, wholePixels = true))
        assertEquals(0.5, fit.scale)
        assertEquals(0.0, fit.x)
        assertEquals(25.0, fit.y)
    }

    @Test
    fun `an image is fitted down but never enlarged`() {
        assertEquals(0.5, fit(200, 100, 100.0, 100.0, wholePixels = false)?.scale)
        assertEquals(1.0, fit(10, 10, 100.0, 100.0, wholePixels = false)?.scale)
    }

    @Test
    fun `nothing fits into no room`() {
        assertNull(fit(10, 10, 0.0, 50.0, wholePixels = true))
        assertNull(fit(0, 10, 50.0, 50.0, wholePixels = true))
    }

    @Test
    fun `colours convert to ARGB and back`() {
        val argb = 0x80336699.toInt()
        assertEquals(argb, colorOf(argb).toArgb())
        assertEquals(0xFF000000.toInt(), Color.BLACK.toArgb())
    }

    @Test
    fun `a box maps by the fit's scale and offset`() {
        val placed = checkNotNull(fit(200, 100, 100.0, 100.0, wholePixels = false))
        // Scale 0.5, offset (0, 25).
        assertEquals(Rect(10.0, 35.0, 20.0, 10.0), imageToCanvas(Box(20.0, 20.0, 40.0, 20.0), placed))
    }

    @Test
    fun `a click picks the box under it, the smallest of nested ones, and none outside`() {
        val placed = Fit(0.5, 0.0, 25.0, 200, 100)
        val outer = Box(0.0, 0.0, 200.0, 100.0)
        val inner = Box(80.0, 30.0, 40.0, 40.0)
        val boxes = listOf(outer, inner)
        assertEquals(1, hitTest(50.0, 50.0, boxes, placed))
        assertEquals(0, hitTest(5.0, 30.0, boxes, placed))
        assertNull(hitTest(5.0, 5.0, boxes, placed))
        assertNull(hitTest(50.0, 50.0, emptyList(), placed))
    }
}
