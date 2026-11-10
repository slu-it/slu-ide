package dev.sluide.games.sudoku

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal const val MIN_CELL = 28.0
internal const val MAX_CELL = 64.0

/** The gap between the board and the pad, in cells. */
internal const val PAD_GAP_FACTOR = 0.4

/** The pad is this many cells wide: the digits are in three columns. */
internal const val PAD_COLUMNS = 3

/** Digit rows plus one row for Erase and Notes. */
internal fun padRows(size: SudokuSize): Int = (size.side + PAD_COLUMNS - 1) / PAD_COLUMNS + 1

/** The side of a cell and where the pad goes: [padBeside] false puts it below the board. */
internal data class BoardLayout(
    val cell: Double,
    val padBeside: Boolean,
)

/** The cell at [zoom] (ADR 0155) of the Fit cell [fit]; at least 1 px. */
internal fun zoomedCell(
    fit: Double,
    zoom: Double,
): Double = max(1.0, floor(fit * zoom))

/**
 * The arrangement of board and pad in a [width]×[height] area that gives the larger cell (beside on a tie), with the
 * Fit cell within [MIN_CELL]..[MAX_CELL]; the cell returned is that times [zoom] (ADR 0155), at least 1 px. The
 * arrangement is that of the Fit cell, whatever the zoom. Above Fit and below [MIN_CELL] the content scrolls.
 */
internal fun boardLayout(
    width: Double,
    height: Double,
    size: SudokuSize,
    zoom: Double = 1.0,
): BoardLayout {
    val side = size.side
    val beside = min(width / (side + PAD_COLUMNS + PAD_GAP_FACTOR), height / side)
    val below = min(width / side, height / (side + padRows(size) + PAD_GAP_FACTOR))
    val besideWins = beside >= below
    val fit = floor(if (besideWins) beside else below).coerceIn(MIN_CELL, MAX_CELL)
    return BoardLayout(zoomedCell(fit, zoom), besideWins)
}
