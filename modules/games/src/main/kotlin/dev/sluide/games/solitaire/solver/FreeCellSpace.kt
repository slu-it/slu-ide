package dev.sluide.games.solitaire.solver

/**
 * The FreeCell moves for [BestFirst]. Pruning, all sound:
 * - safe foundation moves are played at once (a card goes up when both foundations of the other colour hold its
 *   rank minus 1 or more: every card that could still be put on it is gone, and FreeCell never takes a card back
 *   from a foundation, so keeping it down helps nothing and moving it up only frees a cell or uncovers a card);
 * - interchangeable cells: a card goes to a cell once, not once per free cell (the state is a set of cards);
 * - interchangeable empty columns: only the first empty column is a target, and a whole column is never moved to an
 *   empty one (the result is the same position with the columns swapped).
 * A supermove is one move of [FcOp.COL_COL] with its count, within the (free cells + 1) * 2^(empty columns) capacity
 * of [dev.sluide.games.solitaire.FreeCellRules]. The order of the search is the priority of [priority].
 */
internal class FreeCellSpace : SearchSpace<FreeCellState> {
    override fun hash(state: FreeCellState): Long = state.hash()

    override fun isWon(state: FreeCellState): Boolean = state.isWon()

    override fun pack(state: FreeCellState): ByteArray = state.pack()

    override fun unpack(packed: ByteArray): FreeCellState = FreeCellState.unpack(packed)

    /** Plays every safe foundation move on [s] (in place, [s] must be a private copy), appending them to [ops]. */
    fun settle(
        s: FreeCellState,
        ops: MutableList<Int>,
    ) {
        var again = true
        while (again) {
            again = false
            for (i in s.cells.indices) {
                val card = s.cells[i]
                if (card >= 0 && safe(s, card)) {
                    s.cells[i] = -1
                    s.found[suitOf(card)]++
                    ops += FcOp.of(FcOp.CELL_FOUND, card.toInt())
                    again = true
                }
            }
            for (c in s.cols.indices) {
                val col = s.cols[c]
                if (col.isEmpty() || !safe(s, col.last())) continue
                s.found[suitOf(col.last())]++
                s.setColumn(c, col.copyOf(col.size - 1))
                ops += FcOp.of(FcOp.COL_FOUND, c)
                again = true
            }
        }
    }

    private fun safe(
        s: FreeCellState,
        card: Byte,
    ): Boolean {
        val rank = rankOf(card)
        if (s.found[suitOf(card)] != rank - 1) return false
        val others = if (redOf(card)) BLACK else RED
        return others.all { s.found[it] >= rank - 1 }
    }

    override fun expand(
        state: FreeCellState,
        depth: Int,
        emit: (FreeCellState, IntArray, Int) -> Unit,
    ) {
        val free = state.freeCells()
        val empties = state.emptyColumns()
        val firstEmpty = state.cols.indexOfFirst { it.isEmpty() }

        fun finish(
            child: FreeCellState,
            op: Int,
        ) {
            val ops = ArrayList<Int>(4)
            ops += op
            settle(child, ops)
            emit(child, ops.toIntArray(), priority(child, depth + 1))
        }

        for (i in state.cells.indices) {
            val card = state.cells[i]
            if (card < 0) continue
            if (state.found[suitOf(card)] == rankOf(card) - 1) {
                val child = state.copy()
                child.cells[i] = -1
                child.found[suitOf(card)]++
                finish(child, FcOp.of(FcOp.CELL_FOUND, card.toInt()))
            }
            for (c in state.cols.indices) {
                val col = state.cols[c]
                if (if (col.isEmpty()) c == firstEmpty else stacks(card, col.last())) {
                    val child = state.copy()
                    child.cells[i] = -1
                    child.setColumn(c, col + card)
                    finish(child, FcOp.of(FcOp.CELL_COL, card.toInt(), c))
                }
            }
        }
        for (from in state.cols.indices) {
            val col = state.cols[from]
            if (col.isEmpty()) continue
            val top = col.last()
            if (state.found[suitOf(top)] == rankOf(top) - 1) {
                val child = state.copy()
                child.found[suitOf(top)]++
                child.setColumn(from, col.copyOf(col.size - 1))
                finish(child, FcOp.of(FcOp.COL_FOUND, from))
            }
            if (free > 0) {
                val child = state.copy()
                val slot = child.cells.indexOfFirst { it < 0 }
                child.cells[slot] = top
                child.setColumn(from, col.copyOf(col.size - 1))
                finish(child, FcOp.of(FcOp.COL_CELL, from))
            }
            var run = 1
            while (run < col.size && stacks(col[col.size - run], col[col.size - run - 1])) run++
            for (count in 1..run) {
                val head = col[col.size - count]
                for (to in state.cols.indices) {
                    if (to == from) continue
                    val target = state.cols[to]
                    val capacity =
                        if (target.isEmpty()) {
                            if (to != firstEmpty || count == col.size) continue
                            (free + 1) shl (empties - 1)
                        } else {
                            if (!stacks(head, target.last())) continue
                            (free + 1) shl empties
                        }
                    if (count > capacity) continue
                    val child = state.copy()
                    child.setColumn(from, col.copyOf(col.size - count))
                    child.setColumn(to, target + col.copyOfRange(col.size - count, col.size))
                    finish(child, FcOp.of(FcOp.COL_COL, from, to, count))
                }
            }
        }
    }

    /**
     * Lower is better: cards not yet up, cards lying on a lower card of their column (they must move before it can
     * go up), the burial of the next card of each suit, used cells; empty columns count in favour; plus the depth.
     */
    private fun priority(
        s: FreeCellState,
        depth: Int,
    ): Int {
        var blocked = 0
        var buried = 0
        for (col in s.cols) {
            var lowest = 99
            for (k in col.indices) {
                val rank = rankOf(col[k])
                if (rank > lowest) blocked++
                if (rank < lowest) lowest = rank
                if (s.found[suitOf(col[k])] == rank - 1) buried += col.size - 1 - k
            }
        }
        val up = s.found.sum()
        val cellsUsed = s.cells.count { it >= 0 }
        return (
            FreeCellWeights.REMAINING * (52 - up) + FreeCellWeights.BLOCKED * blocked +
                FreeCellWeights.BURIED * buried + FreeCellWeights.CELLS * cellsUsed -
                FreeCellWeights.EMPTY * s.emptyColumns() + FreeCellWeights.DEPTH * depth
        )
    }

    private companion object {
        val BLACK = intArrayOf(0, 3)
        val RED = intArrayOf(1, 2)
    }
}

/** The weights of [FreeCellSpace]'s priority, tuned by measurement (ADR 0138). */
private object FreeCellWeights {
    const val REMAINING = 1
    const val BLOCKED = 2
    const val BURIED = 4
    const val CELLS = 3
    const val EMPTY = 4
    const val DEPTH = 1
}
