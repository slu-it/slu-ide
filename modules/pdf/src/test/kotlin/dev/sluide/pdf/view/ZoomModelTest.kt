package dev.sluide.pdf.view

import dev.sluide.fileviewers.zoom.ZoomSteps
import dev.sluide.pdf.engine.PageSize
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoomModelTest {
    private val zoom = ZoomModel()

    // Fit Page gives 2 (the height decides), Fit Width 5.
    private val target = FitTarget(PageSize(100.0, 200.0), 524.0, 424.0, 0.0)

    @Test
    fun `starts at Fit Page`() {
        assertEquals(FitMode.PAGE, zoom.fit)
    }

    @Test
    fun `fits the page or its width to the viewport`() {
        zoom.fit(FitMode.WIDTH, target)
        assertEquals(FitMode.WIDTH, zoom.fit)
        assertEquals(5.0, zoom.scale)
        zoom.fit(FitMode.PAGE, target)
        assertEquals(FitMode.PAGE, zoom.fit)
        assertEquals(2.0, zoom.scale)
    }

    @Test
    fun `both fits leave room for the scroll bar`() {
        // A wide page: the width decides Fit Page too. Without the 20 px of the scroll bar both would give 2.1.
        val wide = FitTarget(PageSize(200.0, 100.0), 444.0, 424.0, 20.0)
        zoom.fit(FitMode.PAGE, wide)
        assertEquals(2.0, zoom.scale)
        zoom.fit(FitMode.WIDTH, wide)
        assertEquals(2.0, zoom.scale)
    }

    @Test
    fun `zooming in and out steps through the presets and leaves the fit`() {
        zoom.fit(FitMode.PAGE, target)
        zoom.zoomIn()
        assertEquals(3.0, zoom.scale)
        assertNull(zoom.fit)
        zoom.fit(FitMode.PAGE, target)
        zoom.zoomOut()
        assertEquals(1.5, zoom.scale)
        assertNull(zoom.fit)
    }

    @Test
    fun `actual size is 100 percent and leaves the fit`() {
        zoom.fit(FitMode.PAGE, target)
        zoom.actualSize()
        assertEquals(1.0, zoom.scale)
        assertNull(zoom.fit)
    }

    @Test
    fun `zooming by a factor multiplies the scale within the limits and leaves the fit`() {
        zoom.fit(FitMode.PAGE, target)
        zoom.zoomBy(1.5)
        assertEquals(3.0, zoom.scale, 1e-9)
        assertNull(zoom.fit)
        zoom.zoomBy(100.0)
        assertEquals(ZoomSteps.MAX, zoom.scale)
        zoom.actualSize()
        zoom.zoomBy(Double.MAX_VALUE)
        assertEquals(ZoomSteps.MAX, zoom.scale)
    }

    @Test
    fun `zooming out of a fit below the smallest zoom never enlarges`() {
        zoom.fit(FitMode.PAGE, FitTarget(PageSize(10_000.0, 20_000.0), 524.0, 424.0, 0.0))
        val fitted = zoom.scale
        zoom.zoomBy(0.5)
        assertEquals(fitted, zoom.scale)
        zoom.zoomOut()
        assertEquals(fitted, zoom.scale)
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY])
    fun `a factor that is no size changes nothing`(factor: Double) {
        zoom.fit(FitMode.PAGE, target)
        zoom.zoomBy(factor)
        assertEquals(2.0, zoom.scale)
        assertEquals(FitMode.PAGE, zoom.fit)
    }

    @Test
    fun `a resize re-fits in a fit mode and says whether the scale changed`() {
        zoom.fit(FitMode.WIDTH, target)
        assertTrue(zoom.refit(target.copy(viewWidth = 224.0)))
        assertEquals(2.0, zoom.scale)
        assertFalse(zoom.refit(target.copy(viewWidth = 224.0)))
        assertEquals(FitMode.WIDTH, zoom.fit)
    }

    @Test
    fun `a resize keeps a zoom that is no fit`() {
        zoom.fit(FitMode.PAGE, target)
        zoom.actualSize()
        assertFalse(zoom.refit(target.copy(viewWidth = 224.0)))
        assertEquals(1.0, zoom.scale)
    }
}
