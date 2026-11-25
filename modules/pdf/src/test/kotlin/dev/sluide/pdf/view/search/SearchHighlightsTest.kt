package dev.sluide.pdf.view.search

import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.view.OverlayLayer.SEARCH_CURRENT
import dev.sluide.pdf.view.OverlayLayer.SEARCH_HIT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchHighlightsTest {
    private fun hit(
        page: Int,
        start: Int,
    ) = SearchHit(page, start, start + 3, "", "abc", "")

    private val first = hit(0, 5)
    private val second = hit(2, 1)
    private val third = hit(2, 9)
    private val last = hit(7, 0)
    private val highlights = SearchHighlights(listOf(first, second, third, last))

    @Test
    fun `the pages to draw are the shown pages with hits, in order`() {
        assertEquals(listOf(0, 2), highlights.pagesWithHits(0..6))
        assertEquals(listOf(2, 7), highlights.pagesWithHits(1..9))
        assertEquals(emptyList(), highlights.pagesWithHits(3..6))
        assertEquals(emptyList(), highlights.pagesWithHits(IntRange.EMPTY))
    }

    @Test
    fun `before a choice every hit of a page is a hit and none is current`() {
        assertNull(highlights.chosen)
        assertEquals(
            listOf(LayerHits(SEARCH_HIT, listOf(second, third)), LayerHits(SEARCH_CURRENT, emptyList())),
            highlights.layers(2),
        )
        assertEquals(
            listOf(LayerHits(SEARCH_HIT, emptyList()), LayerHits(SEARCH_CURRENT, emptyList())),
            highlights.layers(4),
        )
    }

    @Test
    fun `the chosen hit is current and not a hit as well, on its page only`() {
        assertEquals(listOf(2), highlights.choose(third))
        assertEquals(third, highlights.chosen)
        assertEquals(
            listOf(LayerHits(SEARCH_HIT, listOf(second)), LayerHits(SEARCH_CURRENT, listOf(third))),
            highlights.layers(2),
        )
        assertEquals(
            listOf(LayerHits(SEARCH_HIT, listOf(first)), LayerHits(SEARCH_CURRENT, emptyList())),
            highlights.layers(0),
        )
    }

    @Test
    fun `choosing another hit draws the page of the one before again, once if it is the same page`() {
        highlights.choose(third)
        assertEquals(listOf(2), highlights.choose(second))
        assertEquals(listOf(2, 7), highlights.choose(last))
        assertEquals(
            listOf(LayerHits(SEARCH_HIT, listOf(second, third)), LayerHits(SEARCH_CURRENT, emptyList())),
            highlights.layers(2),
        )
    }

    @Test
    fun `bounds hold all rects, and there are none of no rects`() {
        val rects =
            listOf(PageRect(10.0, 20.0, 5.0, 4.0), PageRect(2.0, 30.0, 6.0, 10.0), PageRect(12.0, 25.0, 30.0, 1.0))
        assertEquals(PageRect(2.0, 20.0, 40.0, 20.0), bounds(rects))
        assertEquals(rects[0], bounds(rects.take(1)))
        assertEquals(
            PageRect(1.0, 0.0, 50.0, 25.0),
            bounds(listOf(PageRect(1.0, 0.0, 50.0, 10.0), PageRect(10.0, 20.0, 5.0, 5.0))),
        )
        assertNull(bounds(emptyList()))
    }
}
