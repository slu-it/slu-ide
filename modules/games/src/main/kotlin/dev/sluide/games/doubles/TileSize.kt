package dev.sluide.games.doubles

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal const val MIN_TILE = 40.0
internal const val MAX_TILE = 110.0

/** Gap between tiles and between tiles and the board border, as a fraction of the tile side. */
internal const val GAP_FACTOR = 0.12

/** Height of the score row in tile sides. */
internal const val SCORE_ROW_FACTOR = 0.7

/** Gap between the score row and the board, in tile sides. */
internal const val SECTION_GAP_FACTOR = 0.25

/**
 * The side of a square tile: the Fit size, which lets the score row and a board of [side] by [side] fill the space
 * within the limits, times [zoom] (ADR 0155); at least 1 px.
 */
internal fun tileSize(
    availableWidth: Double,
    availableHeight: Double,
    side: Int,
    zoom: Double = 1.0,
): Double {
    val boardUnits = side + (side + 1) * GAP_FACTOR
    val heightUnits = SCORE_ROW_FACTOR + SECTION_GAP_FACTOR + boardUnits
    val fit = floor(min(availableWidth / boardUnits, availableHeight / heightUnits)).coerceIn(MIN_TILE, MAX_TILE)
    return max(1.0, floor(fit * zoom))
}

/** The font size for a tile of side [tile] showing [value]: smaller for longer numbers. */
internal fun fontSize(
    tile: Double,
    value: Int,
): Double {
    val factor =
        when (value.toString().length) {
            1, 2 -> 0.45
            3 -> 0.38
            4 -> 0.30
            else -> 0.24
        }
    return tile * factor
}
