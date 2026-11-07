package dev.sluide.games.orbitguard

import kotlin.random.Random

internal class BonusShip(
    var x: Double,
    val direction: Int,
    val points: Int,
)

/** The bonus ship's schedule: a random countdown to the next crossing, which needs enough enemies alive. */
internal class BonusTracker(
    private val random: Random,
) {
    var ship: BonusShip? = null
        private set
    var countdown = nextCountdown()

    private fun nextCountdown() = Rules.BONUS_MIN_COUNTDOWN + random.nextInt(Rules.BONUS_COUNTDOWN_SPREAD)

    /** The ship is gone (shot, left the field or the wave ended): restart the countdown. */
    fun remove() {
        ship = null
        countdown = nextCountdown()
    }

    /** One playing step: count down and spawn, or fly on. */
    fun tick(enemiesAlive: Int) {
        val flying = ship
        if (flying == null) {
            if (--countdown > 0) return
            if (enemiesAlive < Rules.BONUS_MIN_ENEMIES) {
                countdown = nextCountdown()
                return
            }
            val direction = if (random.nextInt(2) == 0) 1 else -1
            val x = if (direction > 0) -Sprites.BONUS.width.toDouble() else OrbitGuardGame.WIDTH.toDouble()
            ship = BonusShip(x, direction, Rules.BONUS_POINTS[random.nextInt(Rules.BONUS_POINTS.size)])
            return
        }
        flying.x += flying.direction
        if (flying.x >= OrbitGuardGame.WIDTH || flying.x + Sprites.BONUS.width <= 0) remove()
    }
}
