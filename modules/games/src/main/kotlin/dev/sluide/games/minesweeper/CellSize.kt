package dev.sluide.games.minesweeper

import kotlin.math.floor
import kotlin.math.min

internal const val MIN_CELL = 16.0
internal const val MAX_CELL = 40.0

/** The side of a square cell that lets a [columns]×[rows] board fill the available space, within the cell limits. */
internal fun cellSize(
    availableWidth: Double,
    availableHeight: Double,
    columns: Int,
    rows: Int,
): Double = floor(min(availableWidth / columns, availableHeight / rows)).coerceIn(MIN_CELL, MAX_CELL)
