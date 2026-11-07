package dev.sluide.games.solitaire.solver

import dev.sluide.games.solitaire.Card
import dev.sluide.games.solitaire.KlondikeRules
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Suit
import dev.sluide.games.solitaire.Table

/** A card as a byte: suit ordinal * 13 + rank - 1. */
internal fun cardByte(card: Card): Byte = (card.suit.ordinal * 13 + card.rank - 1).toByte()

internal fun suitOf(card: Byte): Int = card / 13

internal fun rankOf(card: Byte): Int = card % 13 + 1

/** Diamonds and hearts. */
internal fun redOf(card: Byte): Boolean = suitOf(card).let { it == Suit.DIAMONDS.ordinal || it == Suit.HEARTS.ordinal }

/** [card] may lie on [below] in a column: one rank lower, the other colour. */
internal fun stacks(
    card: Byte,
    below: Byte,
): Boolean = rankOf(below) == rankOf(card) + 1 && redOf(below) != redOf(card)

/** A step of the line found, expanded into real moves afterwards; linked backwards so states share their history. */
internal class Path(
    val op: Op,
    val parent: Path?,
)

/**
 * An abstract move. [kind] decides which of [a], [b], [count] and [stock] count; foundations are never named (they
 * are found by suit when the line is expanded).
 */
internal class Op(
    val kind: Int,
    val a: Int = 0,
    val b: Int = 0,
    val count: Int = 1,
    /** Waste moves: the stock steps before the card is played, [DRAW] or [RECYCLE]. */
    val stock: IntArray = EMPTY,
) {
    companion object {
        /** Column [a] to column [b], [count] cards. */
        const val COL_COL = 0

        /** Top of column [a] to its foundation. */
        const val COL_FOUND = 1

        /** After the [stock] steps, the waste top to column [b]. */
        const val WASTE_COL = 2

        /** After the [stock] steps, the waste top to its foundation. */
        const val WASTE_FOUND = 3

        /** The top of the foundation of suit [a] to column [b]. */
        const val FOUND_COL = 4
        const val DRAW = 0
        const val RECYCLE = 1
        val EMPTY = IntArray(0)
    }
}

/**
 * A Klondike position for the search, mutable and copied per node. The stock and the waste are one cycle [talon] in
 * draw order: the cards before [pos] are the waste (the last one its top), the rest the stock. Columns keep their
 * index (the table's), foundations are counted by suit.
 */
internal class KlondikeState(
    val cols: Array<ByteArray>,
    val len: IntArray,
    val down: IntArray,
    val found: IntArray,
    val talon: ByteArray,
    var size: Int,
    var pos: Int,
    var path: Path?,
) {
    fun copy(): KlondikeState =
        KlondikeState(
            Array(cols.size) { cols[it].copyOf() },
            len.copyOf(),
            down.copyOf(),
            found.copyOf(),
            talon.copyOf(),
            size,
            pos,
            path,
        )

    fun isWon(): Boolean = found.sum() == KlondikeRules.DECK_SIZE

    fun top(column: Int): Byte = cols[column][len[column] - 1]

    fun up(column: Int): Int = len[column] - down[column]

    /** Whether [card] is the next of its suit for the foundation. */
    fun fitsFoundation(card: Byte): Boolean = found[suitOf(card)] == rankOf(card) - 1

    fun push(
        column: Int,
        card: Byte,
    ) {
        cols[column][len[column]++] = card
    }

    /** Removes the top of [column], turning the card below face up when it was the last one face up. */
    fun pop(column: Int): Byte {
        val card = cols[column][--len[column]]
        if (len[column] > 0 && down[column] == len[column]) down[column]--
        return card
    }

    /** Removes the card at [index] of the talon; the waste keeps the cards before it. */
    fun takeFromTalon(index: Int): Byte {
        val card = talon[index]
        System.arraycopy(talon, index + 1, talon, index, size - index - 1)
        size--
        pos = index
        return card
    }

    fun record(op: Op) {
        path = Path(op, path)
    }

    companion object {
        /** Capacity of a column: 6 face down and a King-to-Ace run is 19; some slack for odd hand-built tables. */
        const val COLUMN_CAPACITY = 26

        /** The state of [table] (a Klondike table), or null when it does not fit the model. */
        fun of(table: Table): KlondikeState? {
            val ids = KlondikeRules.COLUMNS
            if (ids.any { table.pile(it).cards.size > COLUMN_CAPACITY }) return null
            val cols = Array(ids.size) { ByteArray(COLUMN_CAPACITY) }
            val len = IntArray(ids.size)
            val down = IntArray(ids.size)
            ids.forEachIndexed { i, id ->
                val pile = table.pile(id)
                pile.cards.forEachIndexed { k, card -> cols[i][k] = cardByte(card) }
                len[i] = pile.cards.size
                down[i] = pile.faceDown
            }
            val found = IntArray(Suit.entries.size)
            for (id in table.ids(PileKind.FOUNDATION)) {
                val pile = table.pile(id)
                pile.top?.let { found[it.suit.ordinal] = pile.cards.size }
            }
            val waste = table.pile(KlondikeRules.WASTE).cards
            val stock = table.pile(KlondikeRules.STOCK).cards
            val talonCards = waste + stock.reversed()
            val talon = ByteArray(talonCards.size)
            talonCards.forEachIndexed { i, card -> talon[i] = cardByte(card) }
            return KlondikeState(cols, len, down, found, talon, talon.size, waste.size, null)
        }
    }
}
