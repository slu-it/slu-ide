package dev.sluide.games.solitaire

/** A suit of the French deck; [red] for diamonds and hearts. The declaration order fixes the deals (ADR 0138). */
enum class Suit(
    val red: Boolean,
) {
    CLUBS(false),
    DIAMONDS(true),
    HEARTS(true),
    SPADES(false),
}

/** A playing card: [rank] 1 (Ace) to 13 (King). */
data class Card(
    val rank: Int,
    val suit: Suit,
) {
    init {
        require(rank in 1..13) { "rank must be 1..13: $rank" }
    }

    val red: Boolean get() = suit.red
}

/** The three Solitaire games of the tab. */
enum class Variant(
    val label: String,
) {
    KLONDIKE("Klondike"),
    FREECELL("FreeCell"),
    SPIDER("Spider"),
}

/** A game with its options; each has its own high score, stored under [key]. */
enum class Mode(
    val variant: Variant,
    val key: String,
) {
    KLONDIKE_DRAW_1(Variant.KLONDIKE, "klondike-1"),
    KLONDIKE_DRAW_3(Variant.KLONDIKE, "klondike-3"),
    FREECELL(Variant.FREECELL, "freecell"),
    SPIDER_1(Variant.SPIDER, "spider-1"),
    SPIDER_2(Variant.SPIDER, "spider-2"),
    SPIDER_4(Variant.SPIDER, "spider-4"),
    ;

    companion object {
        val DEFAULT = KLONDIKE_DRAW_1

        fun ofKey(key: String?): Mode? = entries.firstOrNull { it.key == key }
    }
}

/** The kinds of piles on a table; not every game has every kind (only FreeCell has cells). */
enum class PileKind { STOCK, WASTE, FOUNDATION, TABLEAU, CELL }

/** One pile of a table: its [kind] and its [index] among the piles of that kind, from the left. */
data class PileId(
    val kind: PileKind,
    val index: Int = 0,
)
