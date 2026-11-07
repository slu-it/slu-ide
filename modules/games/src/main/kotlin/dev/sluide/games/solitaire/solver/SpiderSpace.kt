package dev.sluide.games.solitaire.solver

/**
 * The Spider moves for [BestFirst]. Pruning, all sound: only the first empty column is a target (empty columns are
 * interchangeable), and a column that is face up as a whole is never moved onto an empty column (the same position
 * with the columns swapped). Which moves are tried first is the priority of [priority], never a cut.
 */
internal class SpiderSpace(
    private val stock: ByteArray,
) : SearchSpace<SpiderState> {
    override fun hash(state: SpiderState): Long = state.hash()

    override fun isWon(state: SpiderState): Boolean = state.isWon()

    override fun pack(state: SpiderState): ByteArray = state.pack()

    override fun unpack(packed: ByteArray): SpiderState = SpiderState.unpack(packed, stock)

    override fun expand(
        state: SpiderState,
        depth: Int,
        emit: (SpiderState, IntArray, Int) -> Unit,
    ) {
        moves(state, emit)
        if (canDeal(state)) {
            val child = state.deal()
            emit(child, intArrayOf(SpOp.of(SpOp.DEAL)), priority(child))
        }
    }

    fun canDeal(state: SpiderState): Boolean = state.stockLeft > 0 && state.cols.none { it.isEmpty() }

    /** The moves between columns of [state]. */
    fun moves(
        state: SpiderState,
        emit: (SpiderState, IntArray, Int) -> Unit,
    ) {
        val firstEmpty = state.cols.indexOfFirst { it.isEmpty() }
        for (from in state.cols.indices) {
            val col = state.cols[from]
            if (col.isEmpty()) continue
            var run = 1
            while (run < state.up[from] && continues(col[col.size - run], col[col.size - run - 1])) run++
            for (count in 1..run) {
                val head = col[col.size - count]
                for (to in state.cols.indices) {
                    if (to == from) continue
                    val target = state.cols[to]
                    if (target.isEmpty()) {
                        if (to != firstEmpty || count == col.size) continue
                    } else if (rankOf(target.last()) != rankOf(head) + 1) {
                        continue
                    }
                    val child = state.move(from, to, count)
                    emit(child, intArrayOf(SpOp.of(SpOp.MOVE, from, to, count)), priority(child))
                }
            }
        }
    }

    private fun continues(
        card: Byte,
        below: Byte,
    ): Boolean = rankOf(below) == rankOf(card) + 1 && suitOf(below) == suitOf(card)

    /**
     * Lower is better: face-down cards, breaks in the face-up part of columns, rows still to deal; empty columns and
     * finished runs count in favour.
     */
    fun priority(s: SpiderState): Int {
        var cost = 0
        for (c in s.cols.indices) {
            val col = s.cols[c]
            cost += SpiderWeights.DOWN * (col.size - s.up[c])
            for (k in col.size - s.up[c] until col.size - 1) {
                val below = col[k]
                val above = col[k + 1]
                cost +=
                    when {
                        continues(above, below) -> 0
                        rankOf(below) == rankOf(above) + 1 -> SpiderWeights.OTHER_SUIT
                        else -> SpiderWeights.BREAK
                    }
            }
        }
        cost -= SpiderWeights.EMPTY * s.emptyColumns()
        cost += SpiderWeights.ROW * (s.stockLeft / s.cols.size)
        cost -= SpiderWeights.DONE * s.completed
        return cost
    }
}

/** The weights of [SpiderSpace.priority], tuned by measurement (ADR 0138). */
private object SpiderWeights {
    const val DOWN = 6
    const val OTHER_SUIT = 10
    const val BREAK = 10
    const val EMPTY = 10
    const val ROW = 60
    const val DONE = 30
}
