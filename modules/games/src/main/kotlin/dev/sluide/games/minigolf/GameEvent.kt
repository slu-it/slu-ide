package dev.sluide.games.minigolf

/** What happened during one step of [MiniGolfGame], for the model (HUD, best per hole, burst). */
internal sealed interface GameEvent {
    /** A shot was played; it is reported by the step after [MiniGolfGame.shoot]. */
    data class Shot(
        val stroke: Int,
    ) : GameEvent

    /**
     * The ball went into the water and is back at rest: after a shot where the shot was played from, with one penalty
     * stroke; after a push where it rested before the push, without one.
     */
    data object Splash : GameEvent

    data class Holed(
        val strokes: Int,
        val holeInOne: Boolean,
    ) : GameEvent

    /** The stroke cap was reached without holing; the hole scores [Rules.PICKED_UP_SCORE]. */
    data object PickedUp : GameEvent

    /** The hole's score is final; follows [Holed] or [PickedUp] in the same step. */
    data class HoleFinished(
        val holeIndex: Int,
        val score: Int,
    ) : GameEvent

    /** The last hole's card was left. */
    data class RoundFinished(
        val total: Int,
    ) : GameEvent
}
