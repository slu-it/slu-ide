package dev.sluide.games.solitaire

private val RANKS = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

/** A card from its code: rank A, 2..10, J, Q, K and suit C, D, H, S, e.g. `10H`, `QS`. */
internal fun card(code: String): Card {
    val rank = RANKS.indexOf(code.dropLast(1)) + 1
    val suit = Suit.entries.single { it.name.startsWith(code.last()) }
    return Card(rank, suit)
}

/** Cards from space-separated codes, bottom to top. */
internal fun cards(codes: String): List<Card> = codes.split(' ').filter { it.isNotEmpty() }.map(::card)

internal val Card.code: String get() = RANKS[rank - 1] + suit.name.first()

internal fun codes(cards: List<Card>): String = cards.joinToString(" ") { it.code }
