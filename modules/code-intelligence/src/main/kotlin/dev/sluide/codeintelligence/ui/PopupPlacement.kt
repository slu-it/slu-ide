package dev.sluide.codeintelligence.ui

import javafx.geometry.Bounds
import javafx.geometry.Point2D
import javafx.geometry.Rectangle2D

/**
 * Where a popup of [width] × [height] goes for text at [anchor] (screen coordinates) on [screen] (its visual bounds):
 * below the anchor, starting at its left edge; above it when it does not fit below but fits above; if it fits on
 * neither side, on the side with more room. Moved left to stay on the screen, but never past its left edge.
 */
internal fun popupPosition(
    anchor: Bounds,
    width: Double,
    height: Double,
    screen: Rectangle2D,
): Point2D {
    val roomBelow = screen.maxY - anchor.maxY
    val roomAbove = anchor.minY - screen.minY
    val above = height > roomBelow && (height <= roomAbove || roomAbove > roomBelow)
    val y = if (above) anchor.minY - height else anchor.maxY
    val x = anchor.minX.coerceAtMost(screen.maxX - width).coerceAtLeast(screen.minX)
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
