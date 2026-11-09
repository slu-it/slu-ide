package dev.sluide.games.minigolf

import dev.sluide.games.minigolf.geometry.Segment
import dev.sluide.games.minigolf.geometry.Vec

/**
 * A moving obstacle. Its position depends only on the step count of the hole (0 when the hole starts), so a hole plays
 * the same every time. The ball collides with each of [segmentsAt] as with a capsule of radius ball radius +
 * [thickness] / 2. Between two steps no point may move by more than the ball radius, or a mover could jump over a
 * resting ball (the course test checks this).
 */
internal sealed interface Mover {
    val thickness: Double

    fun segmentsAt(step: Long): List<Segment>
}

/**
 * A bar of [length] centred on [pivot] that turns once per [periodSteps] steps, clockwise on screen (a negative period
 * turns it the other way). [phase] is its angle at step 0 in radians, 0 = horizontal.
 */
internal data class RotatingBar(
    val pivot: Vec,
    val length: Double,
    val periodSteps: Int,
    val phase: Double = 0.0,
    override val thickness: Double = 4.0,
) : Mover {
    init {
        require(periodSteps != 0) { "a rotating bar needs a period" }
    }

    fun angleAt(step: Long): Double = phase + 2 * Math.PI * (step % periodSteps).toDouble() / periodSteps

    override fun segmentsAt(step: Long): List<Segment> {
        val half = Vec.ofAngle(angleAt(step), length / 2)
        return listOf(Segment(pivot - half, pivot + half))
    }
}

/**
 * A [width] × [height] rectangle whose centre moves from [from] to [to] and back once per [periodSteps] steps, easing
 * in and out like a cosine; it is at [from] at step 0. Its four edges are the walls ([thickness] 0).
 */
internal data class SlidingBlock(
    val from: Vec,
    val to: Vec,
    val width: Double,
    val height: Double,
    val periodSteps: Int,
) : Mover {
    init {
        require(periodSteps > 0) { "a sliding block needs a positive period" }
    }

    override val thickness: Double get() = 0.0

    fun centerAt(step: Long): Vec {
        val angle = 2 * Math.PI * (step % periodSteps).toDouble() / periodSteps
        return from + (to - from) * ((1 - StrictMath.cos(angle)) / 2)
    }

    override fun segmentsAt(step: Long): List<Segment> {
        val center = centerAt(step)
        val left = center.x - width / 2
        val right = center.x + width / 2
        val top = center.y - height / 2
        val bottom = center.y + height / 2
        val corners = listOf(Vec(left, top), Vec(right, top), Vec(right, bottom), Vec(left, bottom))
        return corners.indices.map { Segment(corners[it], corners[(it + 1) % 4]) }
    }
}
