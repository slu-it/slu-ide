package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PageRect
import kotlin.test.Test
import kotlin.test.assertEquals

class PageOverlaysTest {
    private val changed = mutableListOf<Int>()
    private val overlays = PageOverlays { changed += it }
    private val a = PageRect(1.0, 2.0, 3.0, 4.0)
    private val b = PageRect(5.0, 6.0, 7.0, 8.0)
    private val c = PageRect(9.0, 10.0, 11.0, 12.0)

    @Test
    fun `gives the rects of a page in the order of their layers and says which page changed`() {
        overlays.set(OverlayLayer.SELECTION, 2, listOf(c))
        overlays.set(OverlayLayer.SEARCH_CURRENT, 2, listOf(b))
        overlays.set(OverlayLayer.SEARCH_HIT, 2, listOf(a, b))
        overlays.set(OverlayLayer.SEARCH_HIT, 3, listOf(c))
        assertEquals(
            listOf(
                OverlayLayer.SEARCH_HIT to a,
                OverlayLayer.SEARCH_HIT to b,
                OverlayLayer.SEARCH_CURRENT to b,
                OverlayLayer.SELECTION to c,
            ),
            overlays.on(2),
        )
        assertEquals(listOf(OverlayLayer.SEARCH_HIT to c), overlays.on(3))
        assertEquals(emptyList(), overlays.on(4))
        assertEquals(listOf(2, 2, 2, 3), changed)
    }

    @Test
    fun `setting a layer's rects of a page replaces them, and none removes them`() {
        overlays.set(OverlayLayer.SELECTION, 0, listOf(a, b))
        overlays.set(OverlayLayer.SELECTION, 0, listOf(c))
        assertEquals(listOf(OverlayLayer.SELECTION to c), overlays.on(0))
        overlays.set(OverlayLayer.SELECTION, 0, emptyList())
        assertEquals(emptyList(), overlays.on(0))
        assertEquals(listOf(0, 0, 0), changed)
    }

    @Test
    fun `clearing a layer removes it from every page, tells those pages and keeps the other layers`() {
        overlays.set(OverlayLayer.SEARCH_HIT, 0, listOf(a))
        overlays.set(OverlayLayer.SEARCH_HIT, 4, listOf(b))
        overlays.set(OverlayLayer.SELECTION, 4, listOf(c))
        changed.clear()
        overlays.clear(OverlayLayer.SEARCH_HIT)
        assertEquals(emptyList(), overlays.on(0))
        assertEquals(listOf(OverlayLayer.SELECTION to c), overlays.on(4))
        assertEquals(listOf(0, 4), changed.sorted())
    }

    @Test
    fun `clearing a layer without rects changes no page`() {
        overlays.set(OverlayLayer.SELECTION, 2, listOf(a))
        overlays.set(OverlayLayer.SELECTION, 2, emptyList())
        changed.clear()
        overlays.clear(OverlayLayer.SELECTION)
        overlays.clear(OverlayLayer.SEARCH_HIT)
        assertEquals(emptyList(), changed)
    }
}
