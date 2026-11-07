package dev.sluide.games.solitaire

import kotlin.math.min

/**
 * Klondike with unlimited recycling of the waste, Draw 1 or Draw 3 by [mode].
 *
 * Layout: [STOCK], [WASTE], [FOUNDATIONS] 0..3 and [COLUMNS] 0..6. Points are the classic ones per move (time points,
 * undo, hint and the floor at 0 belong to the game, not to the rules).
 */
internal class KlondikeRules(
    override val mode: Mode,
) : Rules {
    init {
        require(mode.variant == Variant.KLONDIKE) { "not a Klondike mode: $mode" }
    }

    private val drawCount = if (mode == Mode.KLONDIKE_DRAW_1) 1 else 3

    /**
     * Deals row by row from the start of [deck]: deck[0..6] go to columns 0..6, deck[7..12] to columns 1..6, and so
     * on, so column i gets i+1 cards with only its top card face up. The remaining 24 cards are the stock, face down,
     * its top being the deck's last card. Waste and foundations are empty, fan 0.
     */
    override fun deal(deck: List<Card>): Table {
        require(deck.size == DECK_SIZE && deck.toSet().size == DECK_SIZE) { "Klondike needs one full deck" }
        val columns = List(COLUMN_COUNT) { mutableListOf<Card>() }
        var next = 0
        for (row in 0 until COLUMN_COUNT) {
            for (column in row until COLUMN_COUNT) columns[column] += deck[next++]
        }
        val piles =
            buildMap {
                put(STOCK, Pile(deck.subList(next, deck.size).toList()))
                put(WASTE, Pile.EMPTY)
                FOUNDATIONS.forEach { put(it, Pile.EMPTY) }
                COLUMNS.forEachIndexed { i, id -> put(id, Pile(columns[i], 1)) }
            }
        return Table(mode, piles)
    }

    /**
     * In a stable order the solver and the click's "best target" may rely on:
     * 1. to a foundation: the waste's top card, then the top cards of columns 0..6, each onto foundations 0..3;
     * 2. to a column: from the waste, columns 0..6, then foundations 0..3; per source by count 1..run length, per count
     *    onto columns 0..6 (a King run lists every empty column);
     * 3. [Move.Draw] or [Move.Recycle].
     *
     * Moving a whole column headed by a King onto another empty column is legal but useless; it is listed, and
     * callers such as the solver and the hint filter it out.
     */
    override fun legalMoves(table: Table): List<Move> {
        if (table.mode != mode) return emptyList()
        val sources = listOf(WASTE) + COLUMNS
        return buildList {
            for (from in sources) {
                val card = table.pile(from).top ?: continue
                if (runLength(table, from) == 0) continue
                FOUNDATIONS.filter { fitsFoundation(card, table.pile(it)) }.forEach { add(Move.Transfer(from, it)) }
            }
            for (from in sources + FOUNDATIONS) {
                val cards = table.pile(from).cards
                for (count in 1..runLength(table, from)) {
                    val head = cards[cards.size - count]
                    COLUMNS
                        .filter { it != from && fitsColumn(head, table.pile(it)) }
                        .forEach { add(Move.Transfer(from, it, count)) }
                }
            }
            if (canMove(table, Move.Draw)) add(Move.Draw)
            if (canMove(table, Move.Recycle)) add(Move.Recycle)
        }
    }

    override fun canMove(
        table: Table,
        move: Move,
    ): Boolean {
        if (table.mode != mode) return false
        return when (move) {
            is Move.Transfer -> canTransfer(table, move)
            Move.Draw -> table.pile(STOCK).cards.isNotEmpty()
            Move.Recycle -> table.pile(STOCK).cards.isEmpty() && table.pile(WASTE).cards.isNotEmpty()
            Move.DealRow -> false
        }
    }

    private fun canTransfer(
        table: Table,
        move: Move.Transfer,
    ): Boolean {
        if (move.from == move.to || move.from !in table.piles || move.to !in table.piles) return false
        if (move.count < 1 || move.count > runLength(table, move.from)) return false
        val cards = table.pile(move.from).cards
        val head = cards[cards.size - move.count]
        return when (move.to.kind) {
            PileKind.FOUNDATION -> {
                move.count == 1 && move.from.kind != PileKind.FOUNDATION && fitsFoundation(head, table.pile(move.to))
            }

            PileKind.TABLEAU -> {
                fitsColumn(head, table.pile(move.to))
            }

            else -> {
                false
            }
        }
    }

    /** Waste and foundation: their top card; a column: its face-up top cards that descend in alternating colours. */
    override fun runLength(
        table: Table,
        from: PileId,
    ): Int {
        val pile = table.pile(from)
        return when (from.kind) {
            PileKind.WASTE, PileKind.FOUNDATION -> {
                min(1, pile.faceUp)
            }

            PileKind.TABLEAU -> {
                val cards = pile.cards
                var run = min(1, pile.faceUp)
                while (run < pile.faceUp && stacks(cards[cards.size - run], cards[cards.size - run - 1])) run++
                run
            }

            else -> {
                0
            }
        }
    }

    override fun apply(
        table: Table,
        move: Move,
    ): Outcome {
        require(canMove(table, move)) { "illegal move: $move" }
        return when (move) {
            is Move.Transfer -> transfer(table, move)
            Move.Draw -> draw(table)
            Move.Recycle -> recycle(table)
            Move.DealRow -> error("Klondike has no $move")
        }
    }

    private fun transfer(
        table: Table,
        move: Move.Transfer,
    ): Outcome {
        val from = table.pile(move.from)
        val to = table.pile(move.to)
        val keep = from.cards.size - move.count
        var rest = Pile(from.cards.subList(0, keep).toList(), from.faceUp - move.count)
        val flipped = move.from.kind == PileKind.TABLEAU && rest.cards.isNotEmpty() && rest.faceUp == 0
        if (flipped) rest = rest.copy(faceUp = 1)
        val target = Pile(to.cards + from.cards.subList(keep, from.cards.size), to.faceUp + move.count)
        val fan = if (move.from == WASTE) wasteFan(rest.cards.size, table.fan - 1) else table.fan
        return Outcome(
            table = table.with(move.from to rest, move.to to target).copy(fan = fan),
            flipped = if (flipped) listOf(move.from) else emptyList(),
            completedRuns = emptyList(),
            points = transferPoints(move.from.kind, move.to.kind) + if (flipped) FLIP_POINTS else 0,
        )
    }

    private fun draw(table: Table): Outcome {
        val stock = table.pile(STOCK).cards
        val turned = min(drawCount, stock.size)
        val waste = table.pile(WASTE).cards + stock.takeLast(turned).reversed()
        val next = table.with(STOCK to Pile(stock.dropLast(turned)), WASTE to Pile.allFaceUp(waste))
        return Outcome(next.copy(fan = turned), emptyList(), emptyList(), 0)
    }

    private fun recycle(table: Table): Outcome {
        val next = table.with(STOCK to Pile(table.pile(WASTE).cards.reversed()), WASTE to Pile.EMPTY)
        return Outcome(next.copy(fan = 0), emptyList(), emptyList(), if (drawCount == 1) -100 else -20)
    }

    /** The fan after the waste lost its top card: [wanted], but at least 1 while cards are left, 0 when none. */
    private fun wasteFan(
        size: Int,
        wanted: Int,
    ): Int = if (size == 0) 0 else wanted.coerceIn(1, min(drawCount, size))

    override fun isWon(table: Table): Boolean = FOUNDATIONS.sumOf { table.pile(it).cards.size } == DECK_SIZE

    /**
     * Stock and waste empty and every card face up. In play the face-up cards of a column always descend in
     * alternating colours, so the lowest card left is a column's top card and fits its foundation: playing to the
     * foundations only always wins.
     */
    override fun isDecided(table: Table): Boolean =
        table.pile(STOCK).cards.isEmpty() &&
            table.pile(WASTE).cards.isEmpty() &&
            COLUMNS.all { table.pile(it).faceDown == 0 }

    private fun fitsFoundation(
        card: Card,
        pile: Pile,
    ): Boolean = pile.top?.let { it.suit == card.suit && card.rank == it.rank + 1 } ?: (card.rank == 1)

    private fun fitsColumn(
        card: Card,
        pile: Pile,
    ): Boolean = pile.top?.let { pile.faceUp > 0 && stacks(card, it) } ?: (card.rank == 13)

    /** [card] may lie on [below] in a column: one rank lower, the other colour. */
    private fun stacks(
        card: Card,
        below: Card,
    ): Boolean = below.rank == card.rank + 1 && below.red != card.red

    private fun transferPoints(
        from: PileKind,
        to: PileKind,
    ): Int =
        when {
            from == PileKind.WASTE && to == PileKind.TABLEAU -> 5
            to == PileKind.FOUNDATION -> 10
            from == PileKind.FOUNDATION && to == PileKind.TABLEAU -> -15
            else -> 0
        }

    internal companion object {
        const val DECK_SIZE = 52
        const val COLUMN_COUNT = 7
        const val FLIP_POINTS = 5
        val STOCK = PileId(PileKind.STOCK)
        val WASTE = PileId(PileKind.WASTE)
        val FOUNDATIONS = List(4) { PileId(PileKind.FOUNDATION, it) }
        val COLUMNS = List(COLUMN_COUNT) { PileId(PileKind.TABLEAU, it) }
    }
}
