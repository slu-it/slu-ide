package dev.sluide.pdf.view.search

import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.view.OverlayLayer
import kotlin.math.max
import kotlin.math.min

/** The hits to show in [layer] on a page; none removes the layer's rects there. */
internal data class LayerHits(
    val layer: OverlayLayer,
    val hits: List<SearchHit>,
)

/**
 * The highlights of one search's [hits]: every hit in [OverlayLayer.SEARCH_HIT], the [chosen] one in
 * [OverlayLayer.SEARCH_CURRENT] instead. Says which pages to draw and what to set on each; the caller turns the hits
 * into rects once the page's text with boxes is there. FX-free.
 */
internal class SearchHighlights(
    hits: List<SearchHit>,
) {
    private val byPage = hits.groupBy { it.page }

    /** The hit the user picked last; `null` until then. */
    var chosen: SearchHit? = null
        private set

    /** The pages of [shown] that have hits, in order. */
    fun pagesWithHits(shown: IntRange): List<Int> = shown.filter { it in byPage }

    /** Chooses [hit]; returns the pages to draw again: that of the hit chosen before, if any, and that of [hit]. */
    fun choose(hit: SearchHit): List<Int> {
        val before = chosen
        chosen = hit
        return listOfNotNull(before?.page, hit.page).distinct()
    }

    /** What to set on [page]: its hits but the chosen one as hits, and the chosen one, if it is on [page], as current. */
    fun layers(page: Int): List<LayerHits> {
        val current = chosen?.takeIf { it.page == page }
        return listOf(
            LayerHits(OverlayLayer.SEARCH_HIT, byPage[page].orEmpty().filter { it != current }),
            LayerHits(OverlayLayer.SEARCH_CURRENT, listOfNotNull(current)),
        )
    }
}

/** The smallest rect that holds all of [rects]; `null` for none. */
internal fun bounds(rects: List<PageRect>): PageRect? =
    rects.reduceOrNull { a, b ->
        val left = min(a.x, b.x)
        val top = min(a.y, b.y)
        PageRect(left, top, max(a.x + a.width, b.x + b.width) - left, max(a.y + a.height, b.y + b.height) - top)
    }
