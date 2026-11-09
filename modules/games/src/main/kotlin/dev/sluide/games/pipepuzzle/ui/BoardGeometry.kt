package dev.sluide.games.pipepuzzle.ui

import dev.sluide.games.pipepuzzle.Pos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Where the square board lies in a [width] × [height] area: cells of [cellSize] px, the board centred. */
internal data class BoardGeometry(
    val width: Double,
    val height: Double,
    val side: Int,
) {
    val cellSize: Double = floor(min(width, height) / side).coerceIn(MIN_CELL, MAX_CELL)
    val boardSize: Double = side * cellSize
    val originX: Double = floor(max(0.0, (width - boardSize) / 2))
    val originY: Double = floor(max(0.0, (height - boardSize) / 2))

    /** The cell under a point, or null outside the board. */
    fun posAt(
        x: Double,
        y: Double,
    ): Pos? {
        val col = floor((x - originX) / cellSize).toInt()
        val row = floor((y - originY) / cellSize).toInt()
        return if (row in 0 until side && col in 0 until side) Pos(row, col) else null
    }

    fun cellX(pos: Pos): Double = originX + pos.col * cellSize

    fun cellY(pos: Pos): Double = originY + pos.row * cellSize

    companion object {
        const val MIN_CELL = 28.0
        const val MAX_CELL = 72.0
    }
}
