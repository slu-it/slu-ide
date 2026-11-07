package dev.sluide.games.solitaire

/**
 * Picks the target of a click on a card. Uses [Rules.canMove] only: FreeCell's `legalMoves` lists just the first
 * empty cell, but any empty cell is a fine target here.
 */
internal object MoveChooser {
    /**
     * The best move of the top [count] cards of [from]: onto a foundation (only for one card), else onto a non-empty
     * tableau column (leftmost first), else onto an empty column, else (one card) into a free cell; null if none.
     * Moving a whole tableau column onto an empty column is a no-op and never chosen.
     */
    fun best(
        rules: Rules,
        table: Table,
        from: PileId,
        count: Int,
    ): Move? {
        if (count == 1) toFoundation(rules, table, from)?.let { return it }
        val columns = table.ids(PileKind.TABLEAU)
        val wholeColumn = from.kind == PileKind.TABLEAU && table.pile(from).cards.size == count
        val candidates =
            columns.filter { table.pile(it).cards.isNotEmpty() } +
                (if (wholeColumn) emptyList() else columns.filter { table.pile(it).cards.isEmpty() }) +
                (if (count == 1) table.ids(PileKind.CELL) else emptyList())
        return candidates
            .map { Move.Transfer(from, it, count) }
            .firstOrNull { rules.canMove(table, it) }
    }

    /** The move of the top card of [from] onto the first foundation that takes it; null if none does. */
    fun toFoundation(
        rules: Rules,
        table: Table,
        from: PileId,
    ): Move? =
        table
            .ids(PileKind.FOUNDATION)
            .map { Move.Transfer(from, it, 1) }
            .firstOrNull { rules.canMove(table, it) }
}
