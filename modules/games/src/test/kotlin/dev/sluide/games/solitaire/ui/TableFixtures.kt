package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Card
import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Pile
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Suit
import dev.sluide.games.solitaire.Table
import dev.sluide.games.solitaire.Variant

/** [count] distinct-looking cards, descending from a King, for filling piles. */
internal fun filler(
    count: Int,
    suit: Suit = Suit.SPADES,
): List<Card> = List(count) { Card(13 - it % 13, suit) }

/** A pile of [count] cards of which the top [up] are face up. */
internal fun pileOf(
    count: Int,
    up: Int = count,
): Pile = Pile(filler(count), up)

private fun ids(
    kind: PileKind,
    count: Int,
) = List(count) { PileId(kind, it) }

/** A hand-built table of [mode]'s variant with the id set of its game; [overrides] replace piles, the rest is empty. */
internal fun emptyTable(
    mode: Mode,
    overrides: Map<PileId, Pile> = emptyMap(),
    fan: Int = 0,
): Table {
    val shape =
        when (mode.variant) {
            Variant.KLONDIKE -> {
                listOf(PileId(PileKind.STOCK), PileId(PileKind.WASTE)) +
                    ids(PileKind.FOUNDATION, 4) + ids(PileKind.TABLEAU, 7)
            }

            Variant.FREECELL -> {
                ids(PileKind.CELL, 4) + ids(PileKind.FOUNDATION, 4) + ids(PileKind.TABLEAU, 8)
            }

            Variant.SPIDER -> {
                listOf(PileId(PileKind.STOCK)) + ids(PileKind.FOUNDATION, 8) + ids(PileKind.TABLEAU, 10)
            }
        }
    return Table(mode, shape.associateWith { Pile.EMPTY } + overrides, fan)
}

internal fun tableau(index: Int) = PileId(PileKind.TABLEAU, index)
