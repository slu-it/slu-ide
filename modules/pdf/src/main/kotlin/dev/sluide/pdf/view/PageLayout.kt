package dev.sluide.pdf.view

import dev.sluide.fileviewers.zoom.ZoomSteps
import dev.sluide.pdf.engine.PageSize
import dev.sluide.pdf.engine.PageTarget
import kotlin.math.max
import kotlin.math.min

/** The part of the page column that is shown, in layout pixels of the column. */
internal data class Viewport(
    val left: Double,
    val top: Double,
    val width: Double,
    val height: Double,
)

/**
 * Where the pages of [sizes] (points, as displayed) lie in the page column at [scale]: stacked from the top with [GAP]
 * between them and [MARGIN] around them, each centred horizontally. Positions are layout pixels of the column, which
 * is as wide as the viewport or, if wider, as [width]. A document has one page at least. Pure; the page column is the
 * only user of pixels (ADR 0192).
 */
internal class PageLayout(
    private val sizes: List<PageSize>,
    val scale: Double,
) {
    private val tops = DoubleArray(sizes.size)

    val pageCount: Int get() = sizes.size

    /** The width of the content: the widest page and the margins. */
    val width: Double = sizes.maxOf { it.width } * scale + 2 * MARGIN

    /** The height of the content: every page, the gaps and the margins. */
    val height: Double

    init {
        var y = MARGIN
        sizes.forEachIndexed { page, size ->
            tops[page] = y
            y += size.height * scale + GAP
        }
        height = y - GAP + MARGIN
    }

    fun top(page: Int): Double = tops[page]

    fun pageWidth(page: Int): Double = sizes[page].width * scale

    fun pageHeight(page: Int): Double = sizes[page].height * scale

    /** The left edge of [page] in a column [columnWidth] wide, or as wide as the content if that is wider. */
    fun left(
        page: Int,
        columnWidth: Double,
    ): Double = (max(columnWidth, width) - pageWidth(page)) / 2

    /** The last page whose top is at or above [y] (a gap belongs to the page above it); the first page above all. */
    fun pageNear(y: Double): Int = lastStartingBefore(y, inclusive = true).coerceAtLeast(0)

    /** The page at [y], or `null` in a gap or a margin. */
    fun pageAt(y: Double): Int? = pageNear(y).takeIf { y >= tops[it] && y < tops[it] + pageHeight(it) }

    /** The page under the vertical middle of [view]. */
    fun currentPage(view: Viewport): Int = pageNear(view.top + view.height / 2)

    /** The pages with a part in [view]; empty when it shows only a gap. */
    fun visiblePages(view: Viewport): IntRange {
        val near = pageNear(view.top)
        val first = if (view.top >= tops[near] + pageHeight(near)) near + 1 else near
        return first..lastStartingBefore(view.top + view.height, inclusive = false)
    }

    /** The content height of [target]: its page's top, moved down by its height within the page, if it has one. */
    fun topOf(target: PageTarget): Double {
        val page = target.page.coerceIn(0, pageCount - 1)
        return tops[page] + (target.y ?: 0.0).coerceIn(0.0, sizes[page].height) * scale
    }

    /**
     * The viewport after a change from [old] to this layout that keeps the point at [anchorX], [anchorY] (relative to
     * [view], the viewport before) where it was: the same point of the same page, as far as the content allows.
     */
    fun keepAnchor(
        old: PageLayout,
        view: Viewport,
        anchorX: Double,
        anchorY: Double,
    ): Viewport {
        val y = view.top + anchorY
        val page = old.pageNear(y)
        val points = (y - old.top(page)) / old.scale
        val top = tops[page] + points * scale - anchorY
        val oldColumn = max(old.width, view.width)
        val fromCentre = (view.left + anchorX - oldColumn / 2) / old.scale
        val column = max(width, view.width)
        val left = column / 2 + fromCentre * scale - anchorX
        return view.copy(
            left = left.coerceIn(0.0, column - view.width),
            top = top.coerceIn(0.0, max(0.0, height - view.height)),
        )
    }

    /** The index of the last page whose top is before [y] (or at it, if [inclusive]); -1 if none. */
    private fun lastStartingBefore(
        y: Double,
        inclusive: Boolean,
    ): Int {
        var low = 0
        var high = tops.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (tops[middle] < y || (inclusive && tops[middle] == y)) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return found
    }

    companion object {
        /** The space between two pages, in layout pixels at every scale. */
        const val GAP = 8.0

        /** The space around the pages, in layout pixels at every scale. */
        const val MARGIN = 12.0

        /** The smallest scale a fit gives (a page of thousands of points in a small window). */
        const val MIN_FIT = 0.01

        /** The scale that shows all of [page] in a viewport that size, within the margins and beside the scroll bar. */
        fun fitPage(
            page: PageSize,
            viewWidth: Double,
            viewHeight: Double,
            scrollBar: Double,
        ): Double =
            fitted(
                min(
                    (viewWidth - 2 * MARGIN - scrollBar) / page.width,
                    (viewHeight - 2 * MARGIN) / page.height,
                ),
            )

        /** The scale that shows the width of [page] in a viewport that wide, within the margins and the scroll bar. */
        fun fitWidth(
            page: PageSize,
            viewWidth: Double,
            scrollBar: Double,
        ): Double = fitted((viewWidth - 2 * MARGIN - scrollBar) / page.width)

        private fun fitted(scale: Double) = scale.coerceIn(MIN_FIT, ZoomSteps.MAX)
    }
}

/**
 * Where a viewport at [viewStart], [viewSize] long, moves to show the range at [start], [size] long (one axis): it
 * stays if the range is in view, moves the least to show it otherwise (its start, if it is longer than the viewport),
 * and centres it instead when it was entirely out of view and [centre] says so.
 */
internal fun revealed(
    start: Double,
    size: Double,
    viewStart: Double,
    viewSize: Double,
    centre: Boolean,
): Double {
    val end = start + size
    val viewEnd = viewStart + viewSize
    return when {
        start >= viewStart && end <= viewEnd -> viewStart
        size >= viewSize -> start
        centre && (end <= viewStart || start >= viewEnd) -> start + size / 2 - viewSize / 2
        start < viewStart -> start
        else -> end - viewSize
    }
}
