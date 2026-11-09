package dev.sluide.games.minigolf.ui

import dev.sluide.games.minigolf.Hole
import dev.sluide.games.minigolf.geometry.Polygon
import dev.sluide.games.minigolf.geometry.Vec
import kotlin.random.Random

/** One water ripple: where it starts and a phase offset 0..1, so that ripples do not pulse together. */
internal data class Ripple(
    val center: Vec,
    val phase: Double,
)

/** One arrow of a slope, from [from] to [to]. */
internal data class SlopeArrow(
    val from: Vec,
    val to: Vec,
)

/**
 * What [HolePainter] would otherwise compute at every frame: the sand grain, the ripples and the slope arrows of one
 * [hole], all lying fully inside their areas so that they need no clip when drawn. Built once per hole.
 */
internal class TerrainCache(
    val hole: Hole,
) {
    /** Per sand area: grain points of the dark colour and of the light colour. */
    val sandDark: List<List<Vec>>
    val sandLight: List<List<Vec>>
    val ripples: List<List<Ripple>> = hole.water.mapIndexed { i, area -> ripplesOf(area, i) }
    val arrows: List<List<SlopeArrow>> = hole.slopes.map { arrowsOf(it.area, it.acceleration) }

    init {
        val grains = hole.sand.mapIndexed { i, area -> grainOf(area, i) }
        sandDark = grains.map { it.first }
        sandLight = grains.map { it.second }
    }

    private fun grainOf(
        area: Polygon,
        index: Int,
    ): Pair<List<Vec>, List<Vec>> {
        val (min, max) = bounds(area)
        val random = Random(index * 7919L + (min.x * 31 + min.y).toLong())
        val dots = ((max.x - min.x) * (max.y - min.y) / 5).toInt().coerceIn(20, 400)
        val points =
            List(dots) {
                Vec(
                    min.x + random.nextDouble() * (max.x - min.x),
                    min.y + random.nextDouble() * (max.y - min.y),
                )
            }.filter { area.contains(it) && edgeDistance(area, it) > 0.6 }
        return points.filterIndexed { i, _ -> i % 2 == 0 } to points.filterIndexed { i, _ -> i % 2 == 1 }
    }

    private fun ripplesOf(
        area: Polygon,
        index: Int,
    ): List<Ripple> {
        val (min, max) = bounds(area)
        val random = Random(index * 104729L + (min.x * 17 + min.y).toLong())
        val count = ((max.x - min.x) * (max.y - min.y) / 220).toInt().coerceIn(2, 16)
        return List(count * 3) {
            Ripple(
                Vec(min.x + random.nextDouble() * (max.x - min.x), min.y + random.nextDouble() * (max.y - min.y)),
                random.nextDouble(),
            )
        }.filter { area.contains(it.center) && edgeDistance(area, it.center) >= RIPPLE_REACH }.take(count)
    }

    private fun arrowsOf(
        area: Polygon,
        acceleration: Vec,
    ): List<SlopeArrow> {
        val direction = acceleration.normalized()
        val half = acceleration.length * ARROW_PER_ACCELERATION / 2
        val (min, max) = bounds(area)
        val result = mutableListOf<SlopeArrow>()
        var y = min.y + ARROW_SPACING / 2
        while (y < max.y) {
            var x = min.x + ARROW_SPACING / 2
            while (x < max.x) {
                val middle = Vec(x, y)
                val arrow = SlopeArrow(middle - direction * half, middle + direction * half)
                if (listOf(arrow.from, arrow.to, middle).all { area.contains(it) }) result += arrow
                x += ARROW_SPACING
            }
            y += ARROW_SPACING
        }
        return result
    }

    private fun bounds(area: Polygon): Pair<Vec, Vec> {
        val xs = area.points.map { it.x }
        val ys = area.points.map { it.y }
        return Vec(xs.min(), ys.min()) to Vec(xs.max(), ys.max())
    }

    private fun edgeDistance(
        area: Polygon,
        p: Vec,
    ) = area.edges().minOf { it.distanceTo(p) }

    companion object {
        const val ARROW_SPACING = 16.0

        /** An acceleration of 50 u/s² gives an arrow of 10 units. */
        const val ARROW_PER_ACCELERATION = 0.2

        /** The widest ripple (radius [HolePainter]'s maximum) must not reach the edge. */
        const val RIPPLE_REACH = 4.5
    }
}
