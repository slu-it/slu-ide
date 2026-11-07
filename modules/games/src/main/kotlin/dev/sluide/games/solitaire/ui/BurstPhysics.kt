package dev.sluide.games.solitaire.ui

import kotlin.random.Random

/** One flying card of the win burst: [x], [y] the card's top-left corner, velocities in px/s, [age] in s. */
internal data class Particle(
    val x: Double,
    val y: Double,
    val vx: Double,
    val vy: Double,
    val age: Double = 0.0,
    val alpha: Double = 1.0,
)

/** The physics of the win burst, free of JavaFX: launch, gravity, bounces on the floor and the fade-out. */
internal object BurstPhysics {
    /** Downward acceleration in px/s^2. */
    const val GRAVITY = 1500.0

    /** The share of the vertical speed kept after a bounce on the floor. */
    const val DAMPING = 0.7

    /** Seconds from launch until a card is gone. */
    const val LIFETIME = 1.8

    /** The card fades out over the last seconds of its [LIFETIME]. */
    const val FADE = 0.6

    /** The sideways speed of a launch is in -MAX_VX..MAX_VX. */
    const val MAX_VX = 520.0

    /** The upward speed of a launch is in MIN_KICK..MAX_KICK. */
    const val MIN_KICK = 450.0
    const val MAX_KICK = 850.0

    /** A card leaving [origin] (its top-left corner) with a velocity drawn from [random]. */
    fun launch(
        origin: Pair<Double, Double>,
        random: Random,
    ): Particle =
        Particle(
            origin.first,
            origin.second,
            random.nextDouble(-MAX_VX, MAX_VX),
            -random.nextDouble(MIN_KICK, MAX_KICK),
        )

    /** [p] after [dt] seconds; a card whose top would pass [floorY] bounces and keeps [DAMPING] of its speed. */
    fun step(
        p: Particle,
        dt: Double,
        floorY: Double,
    ): Particle {
        val vy = p.vy + GRAVITY * dt
        var y = p.y + vy * dt
        var outVy = vy
        if (y > floorY) {
            y = floorY
            outVy = -vy * DAMPING
        }
        val age = p.age + dt
        return Particle(p.x + p.vx * dt, y, p.vx, outVy, age, ((LIFETIME - age) / FADE).coerceIn(0.0, 1.0))
    }

    fun isGone(p: Particle): Boolean = p.age >= LIFETIME
}
