package dev.sluide.editorarea

/**
 * Which tabs fit into a viewport of [viewportWidth] when the tab strip is scrolled so that the tab at index
 * `first` is the leftmost one. [widths] are the tab header widths in tab order.
 */
internal class TabOverflow(
    private val widths: List<Double>,
    private val viewportWidth: Double,
) {
    val overflows: Boolean = widths.sum() > viewportWidth

    /** The largest useful scroll position: beyond it, empty space would appear right of the last tab. */
    val maxFirst: Int =
        run {
            var first = widths.size
            var used = 0.0
            while (first > 0 && used + widths[first - 1] <= viewportWidth) {
                used += widths[--first]
            }
            first.coerceAtMost(widths.lastIndex).coerceAtLeast(0)
        }

    fun clamp(first: Int): Int = first.coerceIn(0, maxFirst)

    /** Indices of the tabs that are completely visible at scroll position [first]. */
    fun visible(first: Int): IntRange {
        var end = first
        var used = 0.0
        while (end < widths.size && used + widths[end] <= viewportWidth) {
            used += widths[end++]
        }
        return first until end
    }

    /** Tabs that are cut off or scrolled out of view at scroll position [first]. */
    fun hidden(first: Int): List<Int> = visible(first).let { visible -> widths.indices.filterNot { it in visible } }

    /** The scroll position closest to [first] that shows the tab at [index] completely (or at least leftmost). */
    fun reveal(
        index: Int,
        first: Int,
    ): Int {
        if (index !in widths.indices || index < first) return clamp(index)
        if (index in visible(first)) return first
        var newFirst = index
        var used = widths[index]
        while (newFirst > 0 && used + widths[newFirst - 1] <= viewportWidth) {
            used += widths[--newFirst]
        }
        return clamp(newFirst)
    }

    /** Distance from the left edge of the tab at index 0 to the left edge of the tab at [first]. */
    fun offset(first: Int): Double = widths.take(first).sum()

    /**
     * The index the tab at [dragged] belongs at while its header is dragged with its left edge at [left] (measured like
     * [offset]): it passes a neighbour on the right once its right edge is beyond that neighbour's centre, and one on
     * the left once its left edge is before that neighbour's centre. That is the number of other tabs whose centre,
     * in a row without the dragged tab, lies left of [left].
     */
    fun dropIndex(
        dragged: Int,
        left: Double,
    ): Int {
        var start = 0.0
        return widths.withIndex().count { (index, width) ->
            index != dragged && (start + width / 2 < left).also { start += width }
        }
    }
}
