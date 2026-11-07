package dev.sluide.games.orbitguard

import kotlin.random.Random

/**
 * An erodible shield with its own pixel bitmap. [isSolid] takes local coordinates; the search and clearing functions
 * take field coordinates.
 */
internal class Shield(
    val x: Int,
    val y: Int,
    template: Sprite = Sprites.SHIELD,
) {
    val width: Int = template.width
    val height: Int = template.height
    private val bits = Array(height) { py -> BooleanArray(width) { px -> template.isSet(px, py) } }

    fun isSolid(
        px: Int,
        py: Int,
    ): Boolean = py in 0 until height && px in 0 until width && bits[py][px]

    fun solidCount(): Int = bits.sumOf { row -> row.count { it } }

    private fun solidAt(
        fx: Int,
        fy: Int,
    ) = isSolid(fx - x, fy - y)

    /** The first field row from [fromY] to [toY] (inclusive, either direction) with a solid pixel in column [fx]. */
    fun firstSolid(
        fx: Int,
        fromY: Int,
        toY: Int,
    ): Int? {
        val step = if (toY >= fromY) 1 else -1
        var fy = fromY
        while (true) {
            if (solidAt(fx, fy)) return fy
            if (fy == toY) return null
            fy += step
        }
    }

    /**
     * A crumbly chunk around the hit pixel: distance <= 2 always, each solid pixel at distance <= 3 with chance 0.5.
     */
    fun clearChunk(
        fx: Int,
        fy: Int,
        random: Random,
    ) {
        for (dy in -CHUNK_REACH..CHUNK_REACH) {
            for (dx in -CHUNK_REACH..CHUNK_REACH) {
                val d2 = dx * dx + dy * dy
                if (d2 > CHUNK_REACH * CHUNK_REACH || !solidAt(fx + dx, fy + dy)) continue
                if (d2 <= SURE_RADIUS * SURE_RADIUS || random.nextDouble() < 0.5) clear(fx + dx, fy + dy)
            }
        }
    }

    /** Clears every solid pixel under a set pixel of [sprite] placed with its top-left at field pixel ([sx], [sy]). */
    fun clearOverlap(
        sprite: Sprite,
        sx: Int,
        sy: Int,
    ) {
        for (j in 0 until sprite.height) {
            for (i in 0 until sprite.width) {
                if (sprite.isSet(i, j)) clear(sx + i, sy + j)
            }
        }
    }

    private fun clear(
        fx: Int,
        fy: Int,
    ) {
        if (solidAt(fx, fy)) bits[fy - y][fx - x] = false
    }

    private companion object {
        const val SURE_RADIUS = 2
        const val CHUNK_REACH = 3
    }
}
