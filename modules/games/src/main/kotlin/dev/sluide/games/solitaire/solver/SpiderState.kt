package dev.sluide.games.solitaire.solver

import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.SpiderRules
import dev.sluide.games.solitaire.Table
import dev.sluide.games.solitaire.Variant

/** Abstract Spider moves as ints: kind, then [a], [b] and [count] in one byte each. */
internal object SpOp {
    /** [count] cards from column [a] to column [b]. */
    const val MOVE = 0

    /** One stock card onto each column. */
    const val DEAL = 1

    fun of(
        kind: Int,
        a: Int = 0,
        b: Int = 0,
        count: Int = 0,
    ): Int = (kind shl 24) or (a shl 16) or (b shl 8) or count

    fun kind(op: Int): Int = op ushr 24

    fun a(op: Int): Int = (op shr 16) and 0xFF

    fun b(op: Int): Int = (op shr 8) and 0xFF

    fun count(op: Int): Int = op and 0xFF
}

/**
 * A Spider position for the search; never changed once it is a search node. Columns keep the table's index, [up] is
 * the number of face-up cards of each column, [stock] is the table's stock (shared by every state; the first
 * [stockLeft] cards are still in it, its top is the last of them) and [completed] counts the runs taken off.
 */
internal class SpiderState(
    val cols: Array<ByteArray>,
    val up: IntArray,
    val colHash: LongArray,
    val stock: ByteArray,
    val stockLeft: Int,
    val completed: Int,
) {
    fun isWon(): Boolean = completed == RUNS

    fun emptyColumns(): Int = cols.count { it.isEmpty() }

    /** Equal for positions that differ only in the order of the columns. */
    fun hash(): Long {
        var h = FreeCellState.mix(stockLeft * 16L + completed)
        for (c in colHash) h += FreeCellState.mix(c)
        return h
    }

    /** A copy with [column] replaced, [faceUp] of its cards face up (the card below is turned when none is). */
    private fun with(
        cols: Array<ByteArray>,
        up: IntArray,
        hashes: LongArray,
        index: Int,
        column: ByteArray,
        faceUp: Int,
    ) {
        cols[index] = column
        up[index] = if (column.isNotEmpty() && faceUp == 0) 1 else faceUp
        hashes[index] = hashOf(column, up[index])
    }

    /** The position after moving the top [count] cards of [from] onto [to] (a legal move). */
    fun move(
        from: Int,
        to: Int,
        count: Int,
    ): SpiderState {
        val c = cols.copyOf()
        val u = up.copyOf()
        val h = colHash.copyOf()
        val source = cols[from]
        with(c, u, h, from, source.copyOf(source.size - count), up[from] - count)
        with(c, u, h, to, cols[to] + source.copyOfRange(source.size - count, source.size), up[to] + count)
        return SpiderState(c, u, h, stock, stockLeft, completed).complete(to)
    }

    /** The position after dealing a row (a legal move: no empty column, 10 cards in the stock). */
    fun deal(): SpiderState {
        val c = cols.copyOf()
        val u = up.copyOf()
        val h = colHash.copyOf()
        for (i in cols.indices) with(c, u, h, i, cols[i] + stock[stockLeft - 1 - i], up[i] + 1)
        var next = SpiderState(c, u, h, stock, stockLeft - cols.size, completed)
        for (i in cols.indices) next = next.complete(i)
        return next
    }

    /** Takes a finished K..A run off the top of [column] if there is one (the rules do it after every move). */
    private fun complete(column: Int): SpiderState {
        val col = cols[column]
        if (up[column] < RUN || col.size < RUN || rankOf(col[col.size - RUN]) != RUN) return this
        for (k in col.size - RUN until col.size - 1) {
            if (suitOf(col[k]) != suitOf(col[k + 1]) || rankOf(col[k]) != rankOf(col[k + 1]) + 1) return this
        }
        val c = cols.copyOf()
        val u = up.copyOf()
        val h = colHash.copyOf()
        with(c, u, h, column, col.copyOf(col.size - RUN), up[column] - RUN)
        return SpiderState(c, u, h, stock, stockLeft, completed + 1)
    }

    /** Per column its size, its face-up count and its cards, then the cards left in the stock and the runs done. */
    fun pack(): ByteArray {
        val bytes = ByteArray(cols.sumOf { it.size + 2 } + 2)
        var at = 0
        for (c in cols.indices) {
            bytes[at++] = cols[c].size.toByte()
            bytes[at++] = up[c].toByte()
            cols[c].copyInto(bytes, at)
            at += cols[c].size
        }
        bytes[at++] = stockLeft.toByte()
        bytes[at] = completed.toByte()
        return bytes
    }

    companion object {
        const val RUN = 13
        const val RUNS = 8

        fun hashOf(
            column: ByteArray,
            faceUp: Int,
        ): Long {
            var h = column.size * 64L + faceUp
            for (card in column) h = (h + card + 1) * -0x61c8864680b583ebL
            return h
        }

        /** The state [pack]ed, whose game has [stock] as its stock. */
        fun unpack(
            packed: ByteArray,
            stock: ByteArray,
        ): SpiderState {
            var at = 0
            val up = IntArray(SpiderRules.COLUMN_COUNT)
            val cols =
                Array(SpiderRules.COLUMN_COUNT) { c ->
                    val size = packed[at++].toInt()
                    up[c] = packed[at++].toInt()
                    packed.copyOfRange(at, at + size).also { at += size }
                }
            val hashes = LongArray(cols.size) { hashOf(cols[it], up[it]) }
            return SpiderState(cols, up, hashes, stock, packed[at].toInt(), packed[at + 1].toInt())
        }

        /**
         * The state of [table], or null when its stock is not a whole number of rows (the search assumes rows), a
         * column has no face-up card or there is no card at all.
         */
        fun of(table: Table): SpiderState? {
            if (table.mode.variant != Variant.SPIDER) return null
            val ids = SpiderRules.COLUMNS
            val cols =
                Array(ids.size) {
                    table
                        .pile(ids[it])
                        .cards
                        .map(::cardByte)
                        .toByteArray()
                }
            val up = IntArray(ids.size) { table.pile(ids[it]).faceUp }
            val stock =
                table
                    .pile(SpiderRules.STOCK)
                    .cards
                    .map(::cardByte)
                    .toByteArray()
            if (stock.size % ids.size != 0) return null
            val done = table.ids(PileKind.FOUNDATION).count { table.pile(it).cards.size == RUN }
            if (cols.indices.any { cols[it].isNotEmpty() && up[it] == 0 }) return null
            if (done == 0 && stock.isEmpty() && cols.all { it.isEmpty() }) return null
            val hashes = LongArray(ids.size) { hashOf(cols[it], up[it]) }
            return SpiderState(cols, up, hashes, stock, stock.size, done)
        }
    }
}
