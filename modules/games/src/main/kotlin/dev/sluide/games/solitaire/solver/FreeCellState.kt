package dev.sluide.games.solitaire.solver

import dev.sluide.games.solitaire.FreeCellRules
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Table

/** Abstract FreeCell moves as ints: kind, then [a], [b] and [count] in one byte each. */
internal object FcOp {
    /** Column [a] to column [b], [count] cards. */
    const val COL_COL = 0

    /** The top of column [a] to a free cell. */
    const val COL_CELL = 1

    /** The card [a] from a cell to column [b]. */
    const val CELL_COL = 2

    /** The top of column [a] to its foundation. */
    const val COL_FOUND = 3

    /** The card [a] from a cell to its foundation. */
    const val CELL_FOUND = 4

    fun of(
        kind: Int,
        a: Int,
        b: Int = 0,
        count: Int = 1,
    ): Int = (kind shl 24) or (a shl 16) or (b shl 8) or count

    fun kind(op: Int): Int = op ushr 24

    fun a(op: Int): Int = (op shr 16) and 0xFF

    fun b(op: Int): Int = (op shr 8) and 0xFF

    fun count(op: Int): Int = op and 0xFF
}

/**
 * A FreeCell position for the search. Never changed once it is a search node ([FreeCellSpace] copies before it
 * changes). Columns keep the table's index, cells are an unordered set (-1 is empty), foundations are heights by suit.
 */
internal class FreeCellState(
    val cols: Array<ByteArray>,
    val colHash: LongArray,
    val cells: ByteArray,
    val found: IntArray,
) {
    fun copy(): FreeCellState = FreeCellState(cols.copyOf(), colHash.copyOf(), cells.copyOf(), found.copyOf())

    fun isWon(): Boolean = found.sum() == FreeCellRules.DECK_SIZE

    fun freeCells(): Int = cells.count { it < 0 }

    fun emptyColumns(): Int = cols.count { it.isEmpty() }

    fun setColumn(
        index: Int,
        column: ByteArray,
    ) {
        cols[index] = column
        colHash[index] = hashOf(column)
    }

    /** Equal for positions that differ only in the order of the columns and cells. */
    fun hash(): Long {
        var h = 0L
        for (c in colHash) h += mix(c)
        for (card in cells) if (card >= 0) h += mix(CELL_SALT + card)
        for (suit in found.indices) h += mix(FOUND_SALT + suit * 16 + found[suit])
        return h
    }

    /** Per column its size and its cards, then the cells and the foundation heights. */
    fun pack(): ByteArray {
        val bytes = ByteArray(cols.sumOf { it.size + 1 } + cells.size + found.size)
        var at = 0
        for (col in cols) {
            bytes[at++] = col.size.toByte()
            col.copyInto(bytes, at)
            at += col.size
        }
        cells.copyInto(bytes, at)
        at += cells.size
        for (height in found) bytes[at++] = height.toByte()
        return bytes
    }

    companion object {
        private const val CELL_SALT = 0x5bd1e995L shl 8
        private const val FOUND_SALT = 0x1b873593L shl 8

        fun hashOf(column: ByteArray): Long {
            var h = column.size.toLong()
            for (card in column) h = (h + card + 1) * -0x61c8864680b583ebL
            return h
        }

        fun mix(value: Long): Long {
            var z = value + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun unpack(packed: ByteArray): FreeCellState {
            var at = 0
            val cols =
                Array(FreeCellRules.COLUMN_COUNT) {
                    val size = packed[at++].toInt()
                    packed.copyOfRange(at, at + size).also { at += size }
                }
            val cells = packed.copyOfRange(at, at + FreeCellRules.CELLS.size)
            at += cells.size
            val found = IntArray(4) { packed[at + it].toInt() }
            return FreeCellState(cols, LongArray(cols.size) { hashOf(cols[it]) }, cells, found)
        }

        /** The state of [table], or null when it is not a full deck of 52 distinct cards (which the search assumes). */
        fun of(table: Table): FreeCellState? {
            val cols =
                Array(FreeCellRules.COLUMNS.size) { i ->
                    table
                        .pile(FreeCellRules.COLUMNS[i])
                        .cards
                        .map(::cardByte)
                        .toByteArray()
                }
            val cells = ByteArray(FreeCellRules.CELLS.size) { -1 }
            var next = 0
            for (id in FreeCellRules.CELLS) {
                table.pile(id).top?.let { cells[next++] = cardByte(it) }
            }
            val found = IntArray(4)
            for (id in table.ids(PileKind.FOUNDATION)) {
                val pile = table.pile(id)
                for ((k, card) in pile.cards.withIndex()) {
                    if (card.rank != k + 1 || found[card.suit.ordinal] != k) return null
                    found[card.suit.ordinal]++
                }
            }
            val all = cols.flatMap { it.toList() } + cells.filter { it >= 0 }
            val onTable = all.size + found.sum()
            if (onTable != FreeCellRules.DECK_SIZE || all.toSet().size != all.size) return null
            val seenInFoundation = (0 until 4).flatMap { s -> (0 until found[s]).map { (s * 13 + it).toByte() } }
            if (all.any { it in seenInFoundation }) return null
            return FreeCellState(cols, LongArray(cols.size) { hashOf(cols[it]) }, cells, found)
        }
    }
}
