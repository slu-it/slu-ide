package dev.sluide.pdf.engine

import java.net.URI

/*
 * The values the engine hands out (ADR 0192). Every position and size is in page points (1/72 inch) with the origin at
 * the top-left corner of the page as displayed: crop box applied, page rotation applied. The view multiplies by its
 * scale; nothing here knows pixels except a RenderedPage.
 */

/** The size of a page as displayed: its crop box, width and height swapped on pages rotated by 90 or 270 degrees. */
data class PageSize(
    val width: Double,
    val height: Double,
)

/** A rectangle on a page as displayed, in points from its top-left corner. */
data class PageRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/** A place in the document: the page index (0-based) and, if the destination names one, the top of the view. */
data class PageTarget(
    val page: Int,
    val y: Double? = null,
)

/** An entry of the document outline; [target] is `null` when the PDF names no destination that can be found. */
data class OutlineItem(
    val title: String,
    val target: PageTarget?,
    val open: Boolean,
    val children: List<OutlineItem>,
)

/** Where a link leads: a place in the document or a web page (`http`, `https` or `mailto` only). */
sealed interface LinkTarget {
    data class Page(
        val target: PageTarget,
    ) : LinkTarget

    data class Web(
        val uri: URI,
    ) : LinkTarget
}

/** A link area of a page and where it leads. */
data class PageLink(
    val rect: PageRect,
    val target: LinkTarget,
)

/**
 * The text of a page and the box of each of its chars: `boxes.size == text.length`. A `null` box marks a char the
 * extraction inserted (line ends, word gaps) rather than one drawn on the page.
 */
class PageText(
    val text: String,
    val boxes: List<PageRect?>,
)

/**
 * A rendered page: [pixels] are opaque ARGB, row by row, [width] times [height]; [scale] is the scale used, which is
 * lower than the one asked for when the image would have been larger than [PdfDocument.MAX_PIXELS].
 */
class RenderedPage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val scale: Double,
)
