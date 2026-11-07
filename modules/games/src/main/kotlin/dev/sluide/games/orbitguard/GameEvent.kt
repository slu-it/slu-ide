package dev.sluide.games.orbitguard

/** The player's controls for one step. */
internal data class Input(
    val left: Boolean = false,
    val right: Boolean = false,
    val fire: Boolean = false,
)

internal enum class EnemyKind(
    val points: Int,
) {
    SCOUT(30),
    RAIDER(20),
    BRUTE(10),
}

/** What happened during one step, for the model (score display, best score, sounds later). */
internal sealed interface GameEvent {
    data class EnemyDestroyed(
        val kind: EnemyKind,
        val points: Int,
    ) : GameEvent

    data class BonusDestroyed(
        val points: Int,
    ) : GameEvent

    data object ShipDestroyed : GameEvent

    data object ExtraLife : GameEvent

    data class WaveCleared(
        val wave: Int,
    ) : GameEvent

    data object GameOver : GameEvent
}
