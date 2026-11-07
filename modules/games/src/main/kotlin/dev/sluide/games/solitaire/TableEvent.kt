package dev.sluide.games.solitaire

/** What happened on the table, for the view to animate; sent after the model's `onChange` for the same step. */
internal sealed interface TableEvent {
    /** A new game (deal animation). */
    data class Dealt(
        val game: SolitaireGame,
    ) : TableEvent

    /** A move by the player, or of auto-finish ([auto]). */
    data class Moved(
        val step: Step,
        val auto: Boolean,
    ) : TableEvent

    data class Undone(
        val step: Step,
    ) : TableEvent

    data class Redone(
        val step: Step,
    ) : TableEvent

    data object Restarted : TableEvent

    data class Won(
        val score: Int,
        val newHigh: Boolean,
    ) : TableEvent

    /** The next move of a winning line. */
    data class Hint(
        val move: Move,
    ) : TableEvent
}
