package dev.sluide.games.solitaire

/** A direction of the keyboard cursor. */
internal enum class Direction { UP, DOWN, LEFT, RIGHT }

/** The top [count] cards of [pile], picked up by the keyboard to be dropped elsewhere. */
internal data class Pick(
    val pile: PileId,
    val count: Int,
)

/**
 * The keyboard cursor: on [pile] (null before the first key), selecting its top [depth] cards (0 on an empty pile),
 * and the cards [picked] up. The piles form two rows: the top row ([topRow]) and the tableau columns. Pure; the view
 * draws it.
 */
internal data class Cursor(
    val pile: PileId? = null,
    val depth: Int = 0,
    val picked: Pick? = null,
) {
    /**
     * The cursor moved one step: Left/Right within the row (stopping at the ends); Down from the top row to the column
     * under the pile ([columnUnder]); in a column Up selects one card more while the run has more, then goes to the
     * top-row pile above (or the nearest one to its left), and Down selects one card less. Without a pile it goes to
     * the first column.
     */
    fun moved(
        direction: Direction,
        rules: Rules,
        table: Table,
    ): Cursor {
        val at = pile ?: return copy(pile = PileId(PileKind.TABLEAU, 0), depth = 1).clamped(rules, table)
        val inColumn = at.kind == PileKind.TABLEAU
        val row = if (inColumn) table.ids(PileKind.TABLEAU) else topRow(table)
        val next =
            when (direction) {
                Direction.LEFT, Direction.RIGHT -> {
                    val step = if (direction == Direction.LEFT) -1 else 1
                    row.getOrNull(row.indexOf(at) + step)?.let { copy(pile = it, depth = 1) } ?: this
                }

                Direction.UP -> {
                    when {
                        !inColumn -> this
                        depth < rules.runLength(table, at) -> copy(depth = depth + 1)
                        else -> copy(pile = above(at.index, table), depth = 1)
                    }
                }

                Direction.DOWN -> {
                    if (inColumn) {
                        copy(depth = depth - 1)
                    } else {
                        copy(pile = PileId(PileKind.TABLEAU, columnUnder(at, table)), depth = 1)
                    }
                }
            }
        return next.clamped(rules, table)
    }

    /** The depth kept within the pile: at least 1 (0 on an empty pile), at most its run length (or 1). */
    fun clamped(
        rules: Rules,
        table: Table,
    ): Cursor {
        val at = pile ?: return this
        val clamped =
            if (table.pile(at).cards.isEmpty()) 0 else depth.coerceIn(1, maxOf(1, rules.runLength(table, at)))
        return if (clamped == depth) this else copy(depth = clamped)
    }

    /** The cursor with its selected cards picked up; null when they cannot move together or it is on the stock. */
    fun pickedUp(
        rules: Rules,
        table: Table,
    ): Cursor? {
        val at = pile ?: return null
        if (at.kind == PileKind.STOCK || depth < 1 || depth > rules.runLength(table, at)) return null
        return copy(picked = Pick(at, depth))
    }
}

/**
 * The piles above the columns, left to right: Klondike stock, waste, foundations; FreeCell cells, foundations; Spider
 * stock, foundations.
 */
internal fun topRow(table: Table): List<PileId> =
    table.ids(PileKind.STOCK) + table.ids(PileKind.WASTE) + table.ids(PileKind.CELL) + table.ids(PileKind.FOUNDATION)

/**
 * The column under a top-row pile: the foundations stand over the last columns, the other piles over the first ones
 * (Klondike stock 0, waste 1, foundation i 3 + i; FreeCell cell i, foundation 4 + i; Spider stock 0, foundation 2 + i).
 */
internal fun columnUnder(
    pile: PileId,
    table: Table,
): Int {
    val columns = table.ids(PileKind.TABLEAU).size
    val column =
        if (pile.kind == PileKind.FOUNDATION) {
            columns - table.ids(PileKind.FOUNDATION).size + pile.index
        } else {
            topRow(table).indexOf(pile)
        }
    return column.coerceIn(0, maxOf(0, columns - 1))
}

/** The top-row pile over [column], or the nearest one to its left. */
private fun above(
    column: Int,
    table: Table,
): PileId {
    val row = topRow(table)
    return row.lastOrNull { columnUnder(it, table) <= column } ?: row.first()
}
