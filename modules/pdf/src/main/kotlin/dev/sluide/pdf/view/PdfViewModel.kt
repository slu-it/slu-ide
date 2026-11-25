package dev.sluide.pdf.view

import dev.sluide.pdf.engine.OutlineItem
import dev.sluide.pdf.engine.PageSize

/** What the view knows of the open document, read on the render worker right after it opened. */
internal data class PdfInfo(
    val pageCount: Int,
    val pageSizes: List<PageSize>,
    val outline: List<OutlineItem>,
)

/** A point on page [page] (0-based), in points from its top-left corner as displayed. */
internal data class PagePoint(
    val page: Int,
    val x: Double,
    val y: Double,
)

/** The layers of highlights drawn above the page images, in this order; [styleClass] is the style class of a rect. */
internal enum class OverlayLayer(
    val styleClass: String,
) {
    SEARCH_HIT("pdf-search-hit"),
    SEARCH_CURRENT("pdf-search-current"),
    SELECTION("pdf-selection"),
}
