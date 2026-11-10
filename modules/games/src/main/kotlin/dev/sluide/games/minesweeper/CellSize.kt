package dev.sluide.games.minesweeper

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal const val MIN_CELL = 16.0
internal const val MAX_CELL = 40.0

/**
 * The side of a square cell: the Fit size, which lets a [columns]×[rows] board fill the available space within the cell
 * limits, times [zoom] (ADR 0155); at least 1 px.
 */
internal fun cellSize(
    availableWidth: Double,
    availableHeight: Double,
    columns: Int,
    rows: Int,
    zoom: Double = 1.0,
): Double {
    val fit = floor(min(availableWidth / columns, availableHeight / rows)).coerceIn(MIN_CELL, MAX_CELL)
    return max(1.0, floor(fit * zoom))
}
