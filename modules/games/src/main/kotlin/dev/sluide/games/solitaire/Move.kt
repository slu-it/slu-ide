package dev.sluide.games.solitaire

/** A move of a Solitaire game; which ones a table allows is decided by its [Rules]. */
internal sealed interface Move {
    /** The top [count] cards of [from] onto [to]. */
    data class Transfer(
        val from: PileId,
        val to: PileId,
        val count: Int = 1,
    ) : Move

    /** Klondike: turn 1 or 3 (Draw 3: fewer if fewer are left) from the stock to the waste, face up. */
    data object Draw : Move

    /** Klondike: the whole waste back to the stock face down, order restored (stock empty, waste not). */
    data object Recycle : Move

    /** Spider: one stock card onto each column. */
    data object DealRow : Move
}

/**
 * Result of a move: the new table, the piles whose new top card was turned face up, the tableau columns whose
 * completed run went to a foundation (Spider), and the move's points by the mode's rules.
 */
internal data class Outcome(
    val table: Table,
    val flipped: List<PileId>,
    val completedRuns: List<PileId>,
    val points: Int,
)
