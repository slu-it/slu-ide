package dev.sluide.games.minigolf.geometry

import kotlin.math.sqrt

/**
 * A point or a vector on the logical field (x right, y down). Angles are radians, 0 = +x, growing clockwise on screen.
 * The trigonometry uses [StrictMath], so a sequence of steps gives bit-identical results on every platform (the course's
 * reference solutions are replayed by the tests on x64 and arm64).
 *
 * Two vectors equal only when their components are identical, and -0.0 differs from 0.0: test for a zero vector with
 * [isZero], not by comparing with [ZERO].
 */
internal data class Vec(
    val x: Double,
    val y: Double,
) {
    operator fun plus(other: Vec) = Vec(x + other.x, y + other.y)

    operator fun minus(other: Vec) = Vec(x - other.x, y - other.y)

    operator fun times(factor: Double) = Vec(x * factor, y * factor)

    operator fun div(divisor: Double) = Vec(x / divisor, y / divisor)

    operator fun unaryMinus() = Vec(-x, -y)

    infix fun dot(other: Vec) = x * other.x + y * other.y

    /** The z component of the cross product: positive when [other] lies clockwise of this on screen. */
    infix fun cross(other: Vec) = x * other.y - y * other.x

    val length: Double get() = sqrt(x * x + y * y)

    val isZero: Boolean get() = x == 0.0 && y == 0.0

    fun distanceTo(other: Vec) = (this - other).length

    /** This vector with length 1, or [ZERO] for a zero vector. */
    fun normalized(): Vec {
        val length = length
        return if (length == 0.0) ZERO else this / length
    }

    /** Turned by [angle] radians (clockwise on screen for a positive angle). */
    fun rotated(angle: Double): Vec {
        val cos = StrictMath.cos(angle)
        val sin = StrictMath.sin(angle)
        return Vec(x * cos - y * sin, x * sin + y * cos)
    }

    /** Turned by a quarter, clockwise on screen. */
    fun perpendicular() = Vec(-y, x)

    /** The direction in radians, 0 = +x. */
    fun angle(): Double = StrictMath.atan2(y, x)

    companion object {
        val ZERO = Vec(0.0, 0.0)

        /** A vector of [length] in the direction [angle]. */
        fun ofAngle(
            angle: Double,
            length: Double = 1.0,
        ) = Vec(StrictMath.cos(angle) * length, StrictMath.sin(angle) * length)
    }
}
