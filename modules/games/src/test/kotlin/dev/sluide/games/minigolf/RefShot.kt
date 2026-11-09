package dev.sluide.games.minigolf

/**
 * One shot of a reference solution: wait [waitSteps] steps in AIMING (for moving obstacles), then shoot at [angle]
 * (radians, y down, 0 = +x) with [power] (0..1). [ShotSearch] finds them; CourseTest replays them.
 */
internal data class RefShot(
    val waitSteps: Int,
    val angle: Double,
    val power: Double,
)

/** The reference solutions of every hole, by hole number. */
internal val SOLUTIONS: Map<Int, List<RefShot>> = OPENING_SOLUTIONS + FRONT_SOLUTIONS + BACK_SOLUTIONS

/** A game after [playShots], with every event it reported. */
internal class Played(
    val game: MiniGolfGame,
    val events: List<GameEvent>,
) {
    val holed: Boolean get() = events.any { it is GameEvent.Holed }
}

/**
 * Plays [shots] on the hole at [index] of [course] from its start: each shot waits its steps, then is played, then the
 * game steps until the ball no longer rolls. Stops early when the hole is over.
 */
internal fun playShots(
    course: List<Hole>,
    index: Int,
    shots: List<RefShot>,
): Played {
    val game = MiniGolfGame(course).also { it.startAt(index) }
    val events = mutableListOf<GameEvent>()

    fun settle() {
        while (game.phase == Phase.ROLLING) events += game.step()
    }
    for (shot in shots) {
        repeat(shot.waitSteps) { if (game.phase == Phase.AIMING) events += game.step() }
        settle()
        if (!game.shoot(shot.angle, shot.power)) break
        settle()
    }
    return Played(game, events)
}
