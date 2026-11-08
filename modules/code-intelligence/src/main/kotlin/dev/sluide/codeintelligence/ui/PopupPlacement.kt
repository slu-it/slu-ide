package dev.sluide.codeintelligence.ui

import javafx.geometry.Bounds
import javafx.geometry.Point2D
import javafx.geometry.Rectangle2D

/**
 * Where a popup of [width] × [height] goes for text at [anchor] (screen coordinates) on [screen] (its visual bounds):
 * below the anchor, starting at its left edge; above it when it does not fit below but fits above; if it fits on
 * neither side, on the side with more room. With [preferAbove] (signature help, which leaves the lines below to the
 * completion) the other way round: above, and below only when it fits there but not above. Moved left to stay on the
 * screen, but never past its left edge.
 */
internal fun popupPosition(
    anchor: Bounds,
    width: Double,
    height: Double,
    screen: Rectangle2D,
    preferAbove: Boolean = false,
): Point2D {
    val roomBelow = screen.maxY - anchor.maxY
    val roomAbove = anchor.minY - screen.minY
    val above =
        if (preferAbove) {
            height <= roomAbove || (height > roomBelow && roomAbove >= roomBelow)
        } else {
            height > roomBelow && (height <= roomAbove || roomAbove > roomBelow)
        }
    val y = if (above) anchor.minY - height else anchor.maxY
    val x = anchor.minX.coerceAtMost(screen.maxX - width).coerceAtLeast(screen.minX)
    return Point2D(x, y)
}

/**
 * Where a search popup of [width] goes for [area] (the screen bounds of an editor or a window's content) on [screen]:
 * centred at the area's top, [inset] below its top edge, moved to stay on the screen horizontally, never past its left
 * edge.
 */
internal fun searchPopupPosition(
    area: Bounds,
    width: Double,
    screen: Rectangle2D,
    inset: Double = 8.0,
): Point2D {
    val centred = area.minX + (area.width - width) / 2
    val x = centred.coerceAtMost(screen.maxX - width).coerceAtLeast(screen.minX)
    val y = (area.minY + inset).coerceAtLeast(screen.minY)
    return Point2D(x, y)
}

/**
 * Whether the screen point ([x], [y]) keeps a hover popup shown: on its [anchor] (with a margin of [slack] on every
 * side, for the gap the mouse crosses towards the popup) or on the [popup] itself.
 */
internal fun keepsHover(
    x: Double,
    y: Double,
    anchor: Bounds?,
    popup: Bounds?,
    slack: Double = 4.0,
): Boolean {
    if (popup != null && popup.contains(x, y)) return true
    if (anchor == null) return false
    return x >= anchor.minX - slack && x <= anchor.maxX + slack && y >= anchor.minY - slack && y <= anchor.maxY + slack
}

/** What a popup does when the editor's shown lines change. */
internal enum class ShownLinesReaction {
    /** The editor scrolled: the popup goes. */
    HIDE,

    /** A shown line was edited or the editor resized: the popup goes to its anchor again (or goes if that is gone). */
    PLACE_AGAIN,
}

/**
 * Whether the shown lines of an editor whose popup opened while [firstWhenShown] was the first shown line, now
 * starting at [firstNow], scrolled ([ShownLinesReaction.HIDE]: another first line, or none shown) or were edited or
 * resized ([ShownLinesReaction.PLACE_AGAIN]: the same first line).
 */
internal fun shownLinesReaction(
    firstWhenShown: Int?,
    firstNow: Int?,
): ShownLinesReaction =
    if (firstNow == null || firstNow != firstWhenShown) ShownLinesReaction.HIDE else ShownLinesReaction.PLACE_AGAIN
