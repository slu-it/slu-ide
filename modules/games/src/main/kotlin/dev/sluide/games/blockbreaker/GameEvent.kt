package dev.sluide.games.blockbreaker

/** What happened during one step, for the model (score display, best score, sounds later). */
internal sealed interface GameEvent {
    /** A brick was hit without being destroyed (multi-hit brick or steel). */
    data class BrickHit(
        val column: Int,
        val row: Int,
    ) : GameEvent

    data class BrickDestroyed(
        val column: Int,
        val row: Int,
        val points: Int,
    ) : GameEvent

    data class PowerUpCaught(
        val kind: PowerUp,
    ) : GameEvent

    /** Each ball that leaves the field. */
    data object BallLost : GameEvent

    /** The last ball left the field. */
    data object LifeLost : GameEvent

    data object ExtraLife : GameEvent

    data class LevelCleared(
        val level: Int,
    ) : GameEvent

    data object GameOver : GameEvent
}
