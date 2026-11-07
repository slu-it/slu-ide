package dev.sluide.games.solitaire

/**
 * The rules of one [mode]: the deal, which moves are legal, what they do and when the game is over. A table of another
 * mode allows nothing: [canMove] is false, [legalMoves] empty and [apply] throws [IllegalArgumentException].
 */
internal interface Rules {
    val mode: Mode

    /** The opening table from [deck] (as [Deals.deck] gives it). */
    fun deal(deck: List<Card>): Table

    /** Every legal move, no duplicates; [Move.Transfer]s of every legal count. */
    fun legalMoves(table: Table): List<Move>

    /** Whether [move] is legal on [table]; false for a table of another mode. */
    fun canMove(
        table: Table,
        move: Move,
    ): Boolean

    /**
     * How many top cards of [from] could be picked up together as an ordered run (ignoring targets and capacity);
     * 0 if none (empty pile, stock).
     */
    fun runLength(
        table: Table,
        from: PileId,
    ): Int

    /** The move applied; [IllegalArgumentException] when ![canMove]. */
    fun apply(
        table: Table,
        move: Move,
    ): Outcome

    fun isWon(table: Table): Boolean

    /** The game can be finished by moving cards to the foundations only (auto-finish). */
    fun isDecided(table: Table): Boolean

    companion object {
        fun of(mode: Mode): Rules =
            when (mode.variant) {
                Variant.KLONDIKE -> KlondikeRules(mode)
                Variant.FREECELL, Variant.SPIDER -> throw NotImplementedError("$mode rules come in a later package")
            }
    }
}
