package dev.sluide.fileviewers.preview

import kotlin.test.Test
import kotlin.test.assertEquals

class ZoomStepsTest {
    @Test
    fun `zooming steps through the presets`() {
        val up = generateSequence(0.1) { scale -> ZoomSteps.zoomIn(scale).takeIf { it != scale } }.toList()
        assertEquals(ZoomSteps.presets, up)
        val down = generateSequence(16.0) { scale -> ZoomSteps.zoomOut(scale).takeIf { it != scale } }.toList()
        assertEquals(ZoomSteps.presets.reversed(), down)
    }

    @Test
    fun `a scale between presets goes to the next one above or below`() {
        assertEquals(0.5, ZoomSteps.zoomIn(0.42))
        assertEquals(0.33, ZoomSteps.zoomOut(0.42))
        assertEquals(1.25, ZoomSteps.zoomIn(1.01))
        assertEquals(1.0, ZoomSteps.zoomOut(1.01))
        assertEquals(0.25, ZoomSteps.zoomIn(0.12))
    }

    @Test
    fun `a scale off a preset by float noise counts as that preset`() {
        assertEquals(0.75, ZoomSteps.zoomIn(0.6699999999))
        assertEquals(0.5, ZoomSteps.zoomOut(0.6700000001))
        assertEquals(1.25, ZoomSteps.zoomIn(1.0000000002))
        assertEquals(0.75, ZoomSteps.zoomOut(0.9999999998))
    }

    @Test
    fun `zooming stops at both ends`() {
        assertEquals(16.0, ZoomSteps.zoomIn(16.0))
        assertEquals(16.0, ZoomSteps.zoomIn(20.0))
        assertEquals(0.1, ZoomSteps.zoomOut(0.1))
    }

    @Test
    fun `scales are clamped to 10 to 1600 percent`() {
        assertEquals(listOf(0.1, 0.1, 2.5, 16.0, 16.0), listOf(0.0, 0.1, 2.5, 16.0, 40.0).map { ZoomSteps.clamp(it) })
        assertEquals(0.1, ZoomSteps.clamp(0.05, current = 0.5))
    }

    @Test
    fun `below 10 percent, a fit of huge content, zooming out never enlarges`() {
        assertEquals(0.06, ZoomSteps.zoomOut(0.06))
        assertEquals(0.06, ZoomSteps.clamp(0.06, current = 0.06))
        assertEquals(0.06, ZoomSteps.clamp(0.01, current = 0.06))
        assertEquals(0.1, ZoomSteps.zoomIn(0.06))
    }

    @Test
    fun `labels are whole percentages`() {
        assertEquals(
            listOf("10%", "67%", "100%", "123%", "1600%"),
            listOf(0.1, 0.67, 1.0, 1.2345, 16.0).map(ZoomSteps::label),
        )
    }
}
