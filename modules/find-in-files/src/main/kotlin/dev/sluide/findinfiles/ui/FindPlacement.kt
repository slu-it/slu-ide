package dev.sluide.findinfiles.ui

import javafx.geometry.Bounds
import javafx.geometry.Point2D
import javafx.geometry.Rectangle2D

private const val WIDTH_SHARE = 0.7
private const val MIN_WIDTH = 640.0
private const val MAX_WIDTH = 1000.0

/** The popup's width for an [areaWidth]: 70 % of it, between 640 and 1000 px. */
internal fun findPopupWidth(areaWidth: Double): Double = (areaWidth * WIDTH_SHARE).coerceIn(MIN_WIDTH, MAX_WIDTH)

/**
 * Where a popup of [width] goes for [area] (screen bounds of a window's content) on [screen]: centred, [inset] below the
 * area's top, kept on the screen, never past its left or top edge. Copy of code-intelligence's `searchPopupPosition`.
 */
internal fun findPopupPosition(
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
