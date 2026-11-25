package dev.sluide.pdf.view

import javafx.scene.input.KeyCode

/** How far an arrow key scrolls the pages, in layout pixels. */
internal const val LINE_SCROLL = 40.0

/** How much of the viewport's height Page Up/Down and Space scroll. */
internal const val PAGE_SCROLL_SHARE = 0.9

/**
 * How far a scroll key of the page column scrolls (x, y), in a viewport [viewHeight] high: arrows a line, Page Up/Down
 * and Space (Shift+Space up) most of a viewport, Home and End to the ends (infinite, the caller clamps). `null` for
 * other keys, and for those keys with Shift, Space excepted.
 */
internal fun scrollKeyDelta(
    code: KeyCode,
    shift: Boolean,
    viewHeight: Double,
): Pair<Double, Double>? {
    val page = viewHeight * PAGE_SCROLL_SHARE
    if (shift && code != KeyCode.SPACE) return null
    return when (code) {
        KeyCode.UP -> 0.0 to -LINE_SCROLL
        KeyCode.DOWN -> 0.0 to LINE_SCROLL
        KeyCode.LEFT -> -LINE_SCROLL to 0.0
        KeyCode.RIGHT -> LINE_SCROLL to 0.0
        KeyCode.PAGE_UP -> 0.0 to -page
        KeyCode.PAGE_DOWN -> 0.0 to page
        KeyCode.SPACE -> 0.0 to if (shift) -page else page
        KeyCode.HOME -> 0.0 to Double.NEGATIVE_INFINITY
        KeyCode.END -> 0.0 to Double.POSITIVE_INFINITY
        else -> null
    }
}
