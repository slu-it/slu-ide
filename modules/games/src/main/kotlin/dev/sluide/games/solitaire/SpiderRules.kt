package dev.sluide.games.solitaire

import kotlin.math.min

/**
 * Spider with 1, 2 or 4 suits by [mode], 104 cards, two decks.
 *
 * Layout: [STOCK], [FOUNDATIONS] 0..7 and [COLUMNS] 0..9. Points: -1 per move (dealing a row too) and +100 per
 * completed run; the start score of 500 belongs to the game, not to the rules.
 */
internal class SpiderRules(
    override val mode: Mode,
) : Rules {
    init {
        require(mode.variant == Variant.SPIDER) { "not a Spider mode: $mode" }
    }

    /**
     * Deals the first 54 cards of [deck] row by row onto the columns (deck[0..9] onto columns 0..9, then the next row,
     * ...), so columns 0..3 get 6 cards and columns 4..9 get 5, only the top card of each face up. The remaining 50
     * are the stock, face down, its top being the deck's last card. The foundations are empty.
     */
    override fun deal(deck: List<Card>): Table {
        require(deck.groupingBy { it }.eachCount() == Deals.orderedDeck(mode).groupingBy { it }.eachCount()) {
            "$mode needs its 104 cards"
        }
        val piles =
            buildMap {
                put(STOCK, Pile(deck.subList(DEALT, deck.size).toList()))
                FOUNDATIONS.forEach { put(it, Pile.EMPTY) }
                COLUMNS.forEachIndexed { i, id ->
                    put(id, Pile(deck.subList(0, DEALT).filterIndexed { index, _ -> index % COLUMN_COUNT == i }, 1))
                }
            }
        return Table(mode, piles)
    }

    /**
     * In a stable order the solver and the click's "best target" may rely on: column to column moves, per source column 0..9 by count 1..run
     * length, per count onto columns 0..9 (every empty column is listed; a whole column onto an empty column is
     * legal but useless, callers filter it); then [Move.DealRow].
     */
    override fun legalMoves(table: Table): List<Move> {
        if (table.mode != mode) return emptyList()
        return buildList {
            for (from in COLUMNS) {
                val cards = table.pile(from).cards
                for (count in 1..runLength(table, from)) {
                    val head = cards[cards.size - count]
                    COLUMNS
                        .filter { it != from && fitsColumn(head, table.pile(it)) }
                        .forEach { add(Move.Transfer(from, it, count)) }
                }
            }
            if (canMove(table, Move.DealRow)) add(Move.DealRow)
        }
    }

    override fun canMove(
        table: Table,
        move: Move,
    ): Boolean {
        if (table.mode != mode) return false
        return when (move) {
            is Move.Transfer -> canTransfer(table, move)
            Move.DealRow -> table.pile(STOCK).cards.isNotEmpty() && COLUMNS.none { table.pile(it).cards.isEmpty() }
            Move.Draw, Move.Recycle -> false
        }
    }

    private fun canTransfer(
        table: Table,
        move: Move.Transfer,
    ): Boolean {
        if (move.from == move.to || move.from.kind != PileKind.TABLEAU || move.to.kind != PileKind.TABLEAU) return false
        if (move.from !in table.piles || move.to !in table.piles) return false
        if (move.count < 1 || move.count > runLength(table, move.from)) return false
        val cards = table.pile(move.from).cards
        return fitsColumn(cards[cards.size - move.count], table.pile(move.to))
    }

    /** A column: its face-up top cards that descend in one suit; 0 for everything else. */
    override fun runLength(
        table: Table,
        from: PileId,
    ): Int {
        if (from.kind != PileKind.TABLEAU) return 0
        val pile = table.pile(from)
        val cards = pile.cards
        var run = min(1, pile.faceUp)
        while (run < pile.faceUp && continues(cards[cards.size - run], cards[cards.size - run - 1])) run++
        return run
    }

    /**
     * The move applied, then every column that received cards gives its top K..A of one suit to the next empty
     * foundation, and a column whose top card was uncovered (by the move or a completed run) turns it face up.
     * [Move.DealRow] puts the stock's top card onto column 0, the next one onto column 1, and so on (fewer cards if
     * the stock has fewer than 10, which a game never reaches).
     */
    override fun apply(
        table: Table,
        move: Move,
    ): Outcome {
        require(canMove(table, move)) { "illegal move: $move" }
        val flipped = mutableListOf<PileId>()
        val completed = mutableListOf<PileId>()
        var next: Table
        val receivers: List<PileId>
        when (move) {
            is Move.Transfer -> {
                val from = table.pile(move.from)
                val keep = from.cards.size - move.count
                var rest = Pile(from.cards.subList(0, keep).toList(), from.faceUp - move.count)
                if (rest.cards.isNotEmpty() && rest.faceUp == 0) {
                    rest = rest.copy(faceUp = 1)
                    flipped += move.from
                }
                val to = table.pile(move.to)
                val target = Pile(to.cards + from.cards.subList(keep, from.cards.size), to.faceUp + move.count)
                next = table.with(move.from to rest, move.to to target)
                receivers = listOf(move.to)
            }

            Move.DealRow -> {
                val stock = table.pile(STOCK).cards
                val dealt = stock.takeLast(min(COLUMN_COUNT, stock.size)).reversed()
                next = table.with(STOCK to Pile(stock.dropLast(dealt.size)))
                dealt.forEachIndexed { i, card ->
                    val pile = next.pile(COLUMNS[i])
                    next = next.with(COLUMNS[i] to Pile(pile.cards + card, pile.faceUp + 1))
                }
                receivers = COLUMNS.take(dealt.size)
            }

            else -> {
                error("Spider has no $move")
            }
        }
        for (id in receivers) {
            val pile = next.pile(id)
            if (!completesRun(pile)) continue
            val foundation = FOUNDATIONS.first { next.pile(it).cards.isEmpty() }
            val keep = pile.cards.size - RUN_SIZE
            var rest = Pile(pile.cards.subList(0, keep).toList(), pile.faceUp - RUN_SIZE)
            if (rest.cards.isNotEmpty() && rest.faceUp == 0) {
                rest = rest.copy(faceUp = 1)
                flipped += id
            }
            next = next.with(id to rest, foundation to Pile.allFaceUp(pile.cards.subList(keep, pile.cards.size)))
            completed += id
        }
        return Outcome(next, flipped, completed, -1 + RUN_POINTS * completed.size)
    }

    /** The top 13 cards are face up and run K..A in one suit. */
    private fun completesRun(pile: Pile): Boolean {
        if (pile.faceUp < RUN_SIZE) return false
        val top = pile.cards.subList(pile.cards.size - RUN_SIZE, pile.cards.size)
        return top.first().rank == 13 && top.zipWithNext().all { (below, above) -> continues(above, below) }
    }

    override fun isWon(table: Table): Boolean = FOUNDATIONS.all { table.pile(it).cards.size == RUN_SIZE }

    /** Always false: Spider has no auto-finish, completed runs leave the table by themselves. */
    override fun isDecided(table: Table): Boolean = false

    private fun fitsColumn(
        card: Card,
        pile: Pile,
    ): Boolean = pile.top?.let { pile.faceUp > 0 && it.rank == card.rank + 1 } ?: true

    /** [card] continues a run lying on [below]: one rank lower, same suit. */
    private fun continues(
        card: Card,
        below: Card,
    ): Boolean = below.rank == card.rank + 1 && below.suit == card.suit

    internal companion object {
        const val COLUMN_COUNT = 10
        const val DEALT = 54
        const val RUN_SIZE = 13
        const val RUN_POINTS = 100
        val STOCK = PileId(PileKind.STOCK)
        val FOUNDATIONS = List(8) { PileId(PileKind.FOUNDATION, it) }
        val COLUMNS = List(COLUMN_COUNT) { PileId(PileKind.TABLEAU, it) }
    }
}
