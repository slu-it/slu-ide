package dev.sluide.codereview.window

import javafx.geometry.Rectangle2D

/**
 * Where review window number [index] (0 for the first one open) starts for an IDE window at [owner] on a screen
 * showing [area]: [OWNER_SHARE] of the IDE window, at least [MIN_WIDTH] × [MIN_HEIGHT] and at most the area, centred
 * on the IDE window but inside the area, then [CASCADE_STEP] px right and down for each further window. When the
 * cascade would leave the area it starts again at the centre; a window already at the area's right or bottom edge has
 * no room to cascade, so further windows open on top of it.
 */
internal fun reviewWindowBounds(
    area: Rectangle2D,
    owner: Rectangle2D,
    index: Int,
): Rectangle2D {
    val width = (owner.width * OWNER_SHARE).coerceAtLeast(MIN_WIDTH).coerceAtMost(area.width)
    val height = (owner.height * OWNER_SHARE).coerceAtLeast(MIN_HEIGHT).coerceAtMost(area.height)
    // Not coerceIn: with a fractional screen origin, maxX - width can come out a hair below minX and throw.
    val x = (owner.minX + (owner.width - width) / 2).coerceAtMost(area.maxX - width).coerceAtLeast(area.minX)
    val y = (owner.minY + (owner.height - height) / 2).coerceAtMost(area.maxY - height).coerceAtLeast(area.minY)
    val steps = minOf(area.maxX - width - x, area.maxY - height - y).coerceAtLeast(0.0) / CASCADE_STEP
    val offset = CASCADE_STEP * (index % (steps.toInt() + 1))
    return Rectangle2D(x + offset, y + offset, width, height)
}

private const val OWNER_SHARE = 0.9
private const val MIN_WIDTH = 900.0
private const val MIN_HEIGHT = 600.0
internal const val CASCADE_STEP = 30.0
