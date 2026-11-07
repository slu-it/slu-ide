package dev.sluide.games.solitaire

/** Cards bottom→top; the top [faceUp] cards are face up (0..cards.size). */
internal data class Pile(
    val cards: List<Card> = emptyList(),
    val faceUp: Int = 0,
) {
    init {
        require(faceUp in 0..cards.size) { "faceUp must be 0..${cards.size}: $faceUp" }
    }

    val top: Card? get() = cards.lastOrNull()

    val faceDown: Int get() = cards.size - faceUp

    val faceUpCards: List<Card> get() = cards.subList(faceDown, cards.size)

    internal companion object {
        val EMPTY = Pile()

        /** A pile with every card face up (waste, foundation). */
        fun allFaceUp(cards: List<Card>): Pile = Pile(cards, cards.size)
    }
}

/**
 * An immutable table. [fan]: Klondike only, how many of the waste's top cards are shown spread (0..3; Draw 1:
 * min(1, waste size)).
 */
internal data class Table(
    val mode: Mode,
    val piles: Map<PileId, Pile>,
    val fan: Int = 0,
) {
    /** The pile of [id]; an empty pile for an id the table has not. */
    fun pile(id: PileId): Pile = piles[id] ?: Pile.EMPTY

    /** The ids of [kind] on this table, by index. */
    fun ids(kind: PileKind): List<PileId> = piles.keys.filter { it.kind == kind }.sortedBy { it.index }

    fun with(vararg changes: Pair<PileId, Pile>): Table = copy(piles = piles + changes)
}
