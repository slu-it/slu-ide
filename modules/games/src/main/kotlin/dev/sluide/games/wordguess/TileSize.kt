package dev.sluide.games.wordguess

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal const val MIN_TILE = 24.0
internal const val MAX_TILE = 60.0

/** Gap between tiles and between keys, as a fraction of the tile side. */
internal const val GAP_FACTOR = 0.1

/** Gap between the grid and the keyboard. */
internal const val SECTION_GAP_FACTOR = 0.4
internal const val KEY_WIDTH_FACTOR = 0.75
internal const val KEY_HEIGHT_FACTOR = 0.9

/** Enter and Backspace are this many letter keys wide. */
internal const val WIDE_KEY_UNITS = 1.5

private const val MAX_KEYS_PER_ROW = 11
private const val KEY_ROWS = 3

// The widest keyboard row (German) decides the width in tile sides.
private const val WIDTH_UNITS = MAX_KEYS_PER_ROW * KEY_WIDTH_FACTOR + (MAX_KEYS_PER_ROW - 1) * GAP_FACTOR
private const val HEIGHT_UNITS =
    MAX_GUESSES + (MAX_GUESSES - 1) * GAP_FACTOR + SECTION_GAP_FACTOR +
        KEY_ROWS * KEY_HEIGHT_FACTOR + (KEY_ROWS - 1) * GAP_FACTOR

/**
 * The side of a square tile: the Fit size, which lets the grid and the keyboard fill the available space within the
 * limits, times [zoom] (ADR 0155); at least 1 px.
 */
internal fun tileSize(
    availableWidth: Double,
    availableHeight: Double,
    zoom: Double = 1.0,
): Double {
    val fit = floor(min(availableWidth / WIDTH_UNITS, availableHeight / HEIGHT_UNITS)).coerceIn(MIN_TILE, MAX_TILE)
    return max(1.0, floor(fit * zoom))
}
