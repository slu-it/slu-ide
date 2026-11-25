package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PageSize
import dev.sluide.pdf.view.WorkPriority.NEAR
import dev.sluide.pdf.view.WorkPriority.VISIBLE
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderPlanTest {
    // Ten pages 100 high at scale 1: page i from 12 + 108 i, its centre 50 lower; the content is 1096 high.
    private val layout = PageLayout(List(10) { PageSize(100.0, 100.0) }, 1.0)

    private fun view(
        top: Double,
        height: Double,
    ) = Viewport(0.0, top, 500.0, height)

    @Test
    fun `renders the visible pages nearest the viewport's centre first, then two pages either side`() {
        // The centre is at 275: page 2 (centre 278), page 1 (170), page 3 (386); then 0 (62), 4 (494), 5 (602).
        assertEquals(
            listOf(2 to VISIBLE, 1 to VISIBLE, 3 to VISIBLE, 0 to NEAR, 4 to NEAR, 5 to NEAR),
            renderOrder(layout, view(200.0, 150.0)),
        )
    }

    @Test
    fun `the pages ahead stop at the document's ends`() {
        assertEquals(listOf(9 to VISIBLE, 8 to NEAR, 7 to NEAR), renderOrder(layout, view(1000.0, 200.0)))
    }

    @Test
    fun `a viewport on a gap renders the pages around it ahead`() {
        assertEquals(listOf(0 to NEAR, 1 to NEAR, 2 to NEAR), renderOrder(layout, view(113.0, 5.0)))
    }

    @Test
    fun `no pages ahead when none are asked for`() {
        assertEquals(
            listOf(2 to VISIBLE, 1 to VISIBLE, 3 to VISIBLE),
            renderOrder(layout, view(200.0, 150.0), near = 0),
        )
    }

    @Test
    fun `over the budget the images farthest from the centre go first, never those to keep`() {
        val pixels = mapOf(0 to 30L, 1 to 30L, 5 to 30L, 9 to 30L)
        assertEquals(listOf(0, 9), imagesToDrop(pixels, keep = setOf(5), centre = 5, budget = 70))
        assertEquals(emptyList(), imagesToDrop(pixels, keep = setOf(5), centre = 5, budget = 120))
        assertEquals(emptyList(), imagesToDrop(pixels, keep = setOf(0, 1, 5, 9), centre = 5, budget = 10))
        // Pages 1 and 9 are as far from 5: the later one goes first.
        assertEquals(listOf(9), imagesToDrop(mapOf(1 to 30L, 5 to 30L, 9 to 30L), setOf(5), centre = 5, budget = 60))
    }
}
