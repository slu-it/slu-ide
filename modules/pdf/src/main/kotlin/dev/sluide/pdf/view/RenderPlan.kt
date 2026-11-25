package dev.sluide.pdf.view

import kotlin.math.abs

/**
 * The pages to render for [view] of [layout], in the order to render them: the visible pages nearest the viewport's
 * vertical centre first ([WorkPriority.VISIBLE]), then up to [near] pages before and after them, nearest first
 * ([WorkPriority.NEAR]).
 */
internal fun renderOrder(
    layout: PageLayout,
    view: Viewport,
    near: Int = NEAR_PAGES,
): List<Pair<Int, WorkPriority>> {
    val centre = view.top + view.height / 2

    fun distance(page: Int) = abs(layout.top(page) + layout.pageHeight(page) / 2 - centre)
    val visible = layout.visiblePages(view)
    val ahead =
        ((visible.first - near until visible.first) + (visible.last + 1..visible.last + near))
            .filter { it in 0 until layout.pageCount }
    return visible.sortedBy(::distance).map { it to WorkPriority.VISIBLE } +
        ahead.sortedBy(::distance).map { it to WorkPriority.NEAR }
}

/**
 * The pages whose images to drop so that [pixels] (page to its image's pixels) stay within [budget]: those farthest
 * from page [centre] first, never one of [keep]. Fewer, if the kept ones alone exceed it.
 */
internal fun imagesToDrop(
    pixels: Map<Int, Long>,
    keep: Set<Int>,
    centre: Int,
    budget: Long = PIXEL_BUDGET,
): List<Int> {
    var total = pixels.values.sum()
    val candidates =
        (pixels.keys - keep).sortedWith(compareByDescending<Int> { abs(it - centre) }.thenDescending(naturalOrder()))
    val dropped = mutableListOf<Int>()
    for (page in candidates) {
        if (total <= budget) break
        total -= pixels.getValue(page)
        dropped += page
    }
    return dropped
}

/** How many pages before and after the visible ones are rendered ahead. */
internal const val NEAR_PAGES = 2

/** The decoded pixels the page images of a view may take together: about 300 MB as ARGB. */
internal const val PIXEL_BUDGET = 75_000_000L
