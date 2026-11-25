package dev.sluide.pdf.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class PageRenderingTest {
    @Test
    fun `a page within the pixel cap keeps its scale`() {
        assertEquals(2.0, PageRendering.cappedScale(PageSize(612.0, 792.0), 2.0))
    }

    @Test
    fun `a tall thin page is capped by its height alone`() {
        assertEquals(16.0, PageRendering.cappedScale(PageSize(0.01, 1e6), 100.0))
    }
}
