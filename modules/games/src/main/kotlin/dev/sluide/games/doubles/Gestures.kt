package dev.sluide.games.doubles

import kotlin.math.abs

/**
 * The direction of a drag or swipe by ([dx], [dy]) in screen coordinates (positive dy is down): the dominant axis, or
 * null when both are equally long or the dominant one is shorter than [threshold].
 */
internal fun dragDirection(
    dx: Double,
    dy: Double,
    threshold: Double,
): Direction? {
    val ax = abs(dx)
    val ay = abs(dy)
    if (ax == ay || maxOf(ax, ay) < threshold) return null
    return if (ax > ay) {
        if (dx > 0) Direction.RIGHT else Direction.LEFT
    } else {
        if (dy > 0) Direction.DOWN else Direction.UP
    }
}
