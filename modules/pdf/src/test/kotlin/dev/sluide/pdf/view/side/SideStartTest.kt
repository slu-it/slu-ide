package dev.sluide.pdf.view.side

import dev.sluide.pdf.engine.OutlineItem
import dev.sluide.pdf.engine.PageTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class SideStartTest {
    @Test
    fun `a PDF with an outline opens the panel on Outline`() {
        val outline = listOf(OutlineItem("One", PageTarget(0), open = false, children = emptyList()))
        assertEquals(SideStart(SideTab.OUTLINE, open = true), sideStart(outline))
    }

    @Test
    fun `a PDF without an outline leaves the panel closed, with Thumbnails selected`() {
        assertEquals(SideStart(SideTab.THUMBNAILS, open = false), sideStart(emptyList()))
    }
}
