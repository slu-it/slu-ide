package dev.sluide.games.solitaire

import kotlin.math.min

/**
 * FreeCell with 4 cells and supermoves.
 *
 * Layout: [CELLS] 0..3, [FOUNDATIONS] 0..3 and [COLUMNS] 0..7; no stock, no waste, every card face up. Points: +10 for
 * a card to a foundation, -1 for every move (a foundation move scores +9); the time bonus belongs to the game.
 */
internal class FreeCellRules : Rules {
    override val mode: Mode = Mode.FREECELL

    /**
     * Deals the 52 cards of [deck] row by row onto the columns: deck[0..7] onto columns 0..7, deck[8..15] on top of
     * them, and so on, so columns 0..3 get 7 cards and columns 4..7 get 6. Everything is face up; cells and
     * foundations are empty.
     */
    override fun deal(deck: List<Card>): Table {
        require(deck.size == DECK_SIZE && deck.toSet().size == DECK_SIZE) { "FreeCell needs one full deck" }
        val piles =
            buildMap {
                CELLS.forEach { put(it, Pile.EMPTY) }
                FOUNDATIONS.forEach { put(it, Pile.EMPTY) }
                COLUMNS.forEachIndexed { i, id ->
                    put(id, Pile.allFaceUp(deck.filterIndexed { index, _ -> index % COLUMN_COUNT == i }))
                }
            }
        return Table(mode, piles)
    }

    /**
     * In a stable order the solver and the click's "best target" may rely on:
     * 1. to a foundation: the cards of cells 0..3, then the top cards of columns 0..7, each onto foundations 0..3;
     * 2. to a column: from cells 0..3, then columns 0..7; per source by count 1..run length, per count onto columns
     *    0..7 (only where the supermove capacity allows the count; every empty column is listed);
     * 3. a column's top card to a cell, only into the first empty cell: listing every empty cell would only repeat
     *    the same move. [canMove] still allows any empty cell, so this list is the legal moves minus those repeats.
     *
     * Moving a whole column onto an empty column is legal but useless; it is listed and callers filter it out. A card
     * from one cell to another is refused by [canMove] and not listed (useless).
     */
    override fun legalMoves(table: Table): List<Move> {
        if (table.mode != mode) return emptyList()
        val sources = CELLS + COLUMNS
        return buildList {
            for (from in sources) {
                val card = table.pile(from).top ?: continue
                FOUNDATIONS.filter { fitsFoundation(card, table.pile(it)) }.forEach { add(Move.Transfer(from, it)) }
            }
            for (from in sources) {
                val cards = table.pile(from).cards
                for (count in 1..runLength(table, from)) {
                    val head = cards[cards.size - count]
                    COLUMNS
                        .filter { it != from && fitsColumn(head, table.pile(it)) && count <= capacity(table, it) }
                        .forEach { add(Move.Transfer(from, it, count)) }
                }
            }
            val cell = CELLS.firstOrNull { table.pile(it).cards.isEmpty() }
            if (cell != null) {
                COLUMNS.filter { table.pile(it).cards.isNotEmpty() }.forEach { add(Move.Transfer(it, cell)) }
            }
        }
    }

    override fun canMove(
        table: Table,
        move: Move,
    ): Boolean {
        if (table.mode != mode || move !is Move.Transfer) return false
        if (move.from == move.to || move.from !in table.piles || move.to !in table.piles) return false
        if (move.count < 1 || move.count > runLength(table, move.from)) return false
        val cards = table.pile(move.from).cards
        val head = cards[cards.size - move.count]
        val target = table.pile(move.to)
        return when (move.to.kind) {
            PileKind.FOUNDATION -> move.count == 1 && fitsFoundation(head, target)
            PileKind.TABLEAU -> fitsColumn(head, target) && move.count <= capacity(table, move.to)
            PileKind.CELL -> move.count == 1 && move.from.kind == PileKind.TABLEAU && target.cards.isEmpty()
            else -> false
        }
    }

    /** A column: the top cards that descend in alternating colours; a cell: 1 if it holds a card; else 0. */
    override fun runLength(
        table: Table,
        from: PileId,
    ): Int {
        val cards = table.pile(from).cards
        return when (from.kind) {
            PileKind.CELL -> {
                min(1, cards.size)
            }

            PileKind.TABLEAU -> {
                var run = min(1, cards.size)
                while (run < cards.size && stacks(cards[cards.size - run], cards[cards.size - run - 1])) run++
                run
            }

            else -> {
                0
            }
        }
    }

    /**
     * How many cards a move onto the column [target] can carry: (free cells + 1) × 2^(empty columns), the target
     * itself not counted as an empty column.
     */
    private fun capacity(
        table: Table,
        target: PileId,
    ): Int {
        val freeCells = CELLS.count { table.pile(it).cards.isEmpty() }
        val emptyColumns = COLUMNS.count { it != target && table.pile(it).cards.isEmpty() }
        return (freeCells + 1) shl emptyColumns
    }

    override fun apply(
        table: Table,
        move: Move,
    ): Outcome {
        require(canMove(table, move)) { "illegal move: $move" }
        move as Move.Transfer
        val from = table.pile(move.from).cards
        val keep = from.size - move.count
        val next =
            table.with(
                move.from to Pile.allFaceUp(from.subList(0, keep).toList()),
                move.to to Pile.allFaceUp(table.pile(move.to).cards + from.subList(keep, from.size)),
            )
        val points = if (move.to.kind == PileKind.FOUNDATION) FOUNDATION_POINTS - 1 else -1
        return Outcome(next, emptyList(), emptyList(), points)
    }

    override fun isWon(table: Table): Boolean = FOUNDATIONS.sumOf { table.pile(it).cards.size } == DECK_SIZE

    /**
     * Every column descends from bottom to top (each card lower than the one beneath, any suit). Then moving to the
     * foundations only always wins: the lowest card left, by rank, is on a column top or in a cell, because a column's
     * lowest card is its top; all lower cards of its suit are on the foundations, so it fits its foundation (or an
     * empty one if it is an Ace). Taking it keeps every column descending.
     */
    override fun isDecided(table: Table): Boolean =
        COLUMNS.all { id ->
            table
                .pile(id)
                .cards
                .zipWithNext()
                .all { (below, above) -> above.rank < below.rank }
        }

    private fun fitsFoundation(
        card: Card,
        pile: Pile,
    ): Boolean = pile.top?.let { it.suit == card.suit && card.rank == it.rank + 1 } ?: (card.rank == 1)

    private fun fitsColumn(
        card: Card,
        pile: Pile,
    ): Boolean = pile.top?.let { stacks(card, it) } ?: true

    /** [card] may lie on [below] in a column: one rank lower, the other colour. */
    private fun stacks(
        card: Card,
        below: Card,
    ): Boolean = below.rank == card.rank + 1 && below.red != card.red

    internal companion object {
        const val DECK_SIZE = 52
        const val COLUMN_COUNT = 8
        const val FOUNDATION_POINTS = 10
        val CELLS = List(4) { PileId(PileKind.CELL, it) }
        val FOUNDATIONS = List(4) { PileId(PileKind.FOUNDATION, it) }
        val COLUMNS = List(COLUMN_COUNT) { PileId(PileKind.TABLEAU, it) }
    }
}
