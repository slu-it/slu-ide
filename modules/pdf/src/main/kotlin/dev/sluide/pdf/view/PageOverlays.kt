package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PageRect
import java.util.EnumMap

/**
 * The highlights drawn above the page images (search hits, the current hit, the selection), per [OverlayLayer] and
 * page, in points; the page column lays them out at its scale. [changed] is called with each page whose rects
 * changed, so that the column draws them again. FX thread only.
 */
internal class PageOverlays(
    private val changed: (page: Int) -> Unit,
) {
    private val layers = EnumMap<OverlayLayer, MutableMap<Int, List<PageRect>>>(OverlayLayer::class.java)

    /** Shows [rects] on [page] in [layer] in place of those it had there; none removes them. */
    fun set(
        layer: OverlayLayer,
        page: Int,
        rects: List<PageRect>,
    ) {
        val pages = layers.getOrPut(layer, ::HashMap)
        if (rects.isEmpty()) pages.remove(page) else pages[page] = rects.toList()
        changed(page)
    }

    /** Removes [layer] from every page. */
    fun clear(layer: OverlayLayer) {
        val pages = layers.remove(layer) ?: return
        pages.keys.forEach(changed)
    }

    /** The rects of [page], layer by layer in the order they are drawn. */
    fun on(page: Int): List<Pair<OverlayLayer, PageRect>> =
        layers.flatMap { (layer, pages) -> pages[page].orEmpty().map { layer to it } }
}
