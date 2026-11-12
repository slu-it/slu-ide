package dev.sluide.codereview.window

import javafx.geometry.Rectangle2D

/**
 * Where review window number [index] (0 for the first one open) starts on a screen showing [area]: 80 % of the area,
 * centred, then [CASCADE_STEP] px right and down for each further window. When the cascade would leave the area it
 * starts again at the centre.
 */
internal fun reviewWindowBounds(
    area: Rectangle2D,
    index: Int,
): Rectangle2D {
    val width = area.width * SCREEN_SHARE
    val height = area.height * SCREEN_SHARE
    val steps = minOf((area.width - width) / 2, (area.height - height) / 2) / CASCADE_STEP
    val offset = CASCADE_STEP * (index % (steps.toInt() + 1))
    return Rectangle2D(
        area.minX + (area.width - width) / 2 + offset,
        area.minY + (area.height - height) / 2 + offset,
        width,
        height,
    )
}

private const val SCREEN_SHARE = 0.8
internal const val CASCADE_STEP = 30.0
