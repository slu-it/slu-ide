package dev.sluide.coloring.ui.gradient

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor

/**
 * The pixels (ARGB, row by row) of a conic gradient in a [width] x [height] box: the color at each pixel is the entry
 * of [lookup] at its angle around the center, counted clockwise from [fromDeg] (0deg = up), as CSS does.
 * [lookup] holds the colors from 0 to 360deg.
 */
internal fun conicPixels(
    width: Int,
    height: Int,
    fromDeg: Double,
    lookup: IntArray,
): IntArray {
    val out = IntArray(width * height)
    val cx = width / 2.0
    val cy = height / 2.0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val turns = (atan2(x + 0.5 - cx, -(y + 0.5 - cy)) * 180 / PI - fromDeg) / 360
            val t = turns - floor(turns)
            out[y * width + x] = lookup[(t * lookup.size).toInt().coerceIn(0, lookup.size - 1)]
        }
    }
    return out
}
