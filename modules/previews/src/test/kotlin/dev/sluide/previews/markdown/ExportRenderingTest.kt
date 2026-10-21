package dev.sluide.previews.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ExportRenderingTest {
    private val first = DrawnDiagram("<svg/>", "<svg/>", null)

    @Test
    fun `what came in is kept and what is missing failed`() {
        val rendering = exportRendering(2, 3, listOf("<b>x</b>", null), listOf(first), "Timed out")
        assertEquals(listOf("<b>x</b>", null), rendering.math)
        assertSame(first, rendering.diagrams[0])
        assertFalse(rendering.diagrams[0].failed)
        for (missing in rendering.diagrams.drop(1)) {
            assertEquals("Timed out", missing.error)
            assertTrue(missing.failed)
        }
    }

    @Test
    fun `formulas that did not come in are not typeset`() {
        assertEquals(listOf(null, null), exportRendering(2, 0, null, emptyList(), "Timed out").math)
    }

    @Test
    fun `everything came in`() {
        val rendering = exportRendering(1, 1, listOf("<b>x</b>"), listOf(first), null)
        assertEquals(listOf("<b>x</b>"), rendering.math)
        assertEquals(listOf(first), rendering.diagrams)
    }
}
