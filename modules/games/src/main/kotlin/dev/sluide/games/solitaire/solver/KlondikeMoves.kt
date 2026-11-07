package dev.sluide.games.solitaire.solver

/** A successor position with its search priority (higher first). */
internal class Child(
    val state: KlondikeState,
    val score: Int,
)

/**
 * The successors of a state, settled and not seen before ([visited]), best first.
 *
 * Always applied, all sound: of several empty columns only the first is a target (they are interchangeable); a
 * whole column that starts with a King is never moved to an empty column (same position up to a permutation).
 * Only when not [sound] (first pass, a heuristic): a run is moved partway only when the card below it then fits its
 * foundation, and cards are never taken back from a foundation. With [sound] every legal move of a column run and
 * every foundation-to-column move is offered, so an exhausted search proves the position unsolvable.
 *
 * The stock is a macro: for each card the waste can show after some Draws and Recycles (unlimited), one move
 * "draw until it is on top, then play it", to its foundation or onto a column.
 */
internal class KlondikeMoves(
    private val draw3: Boolean,
    private val sound: Boolean,
    private val settler: KlondikeSettle,
    private val visited: LongSet,
) {
    private val drawCount = if (draw3) 3 else 1
    private val steps = IntArray(128)

    fun expand(s: KlondikeState): List<Child> {
        val out = ArrayList<Child>()
        val firstEmpty = (0 until s.cols.size).firstOrNull { s.len[it] == 0 } ?: -1
        columnsToFoundation(s, out)
        columnRuns(s, firstEmpty, out)
        stock(s, firstEmpty, out)
        if (sound) foundationToColumn(s, firstEmpty, out)
        out.sortByDescending { it.score }
        return out
    }

    private fun emit(
        out: MutableList<Child>,
        state: KlondikeState,
        score: Int,
    ) {
        settler.settle(state)
        if (visited.add(settler.hash(state))) out += Child(state, score)
    }

    private fun columnsToFoundation(
        s: KlondikeState,
        out: MutableList<Child>,
    ) {
        for (i in 0 until s.cols.size) {
            if (s.len[i] == 0 || !s.fitsFoundation(s.top(i))) continue
            val flips = s.down[i] > 0 && s.len[i] == s.down[i] + 1
            val n = s.copy()
            val card = n.pop(i)
            n.found[suitOf(card)]++
            n.record(Op(Op.COL_FOUND, a = i))
            emit(out, n, 90 + if (flips) 10 else 0)
        }
    }

    private fun columnRuns(
        s: KlondikeState,
        firstEmpty: Int,
        out: MutableList<Child>,
    ) {
        for (from in 0 until s.cols.size) {
            if (s.len[from] == 0) continue
            val up = s.up(from)
            val run = runLength(s, from)
            for (count in 1..run) {
                val whole = count == up
                if (!sound && !whole && !s.fitsFoundation(s.cols[from][s.len[from] - count - 1])) continue
                val head = s.cols[from][s.len[from] - count]
                val flipBonus = if (s.down[from] > 0) 20 else 0
                val score = if (whole) 60 + s.down[from] + flipBonus else 50
                for (to in 0 until s.cols.size) {
                    if (to == from) continue
                    val fits =
                        if (s.len[to] > 0) {
                            stacks(head, s.top(to))
                        } else {
                            to == firstEmpty && rankOf(head) == 13 && count != s.len[from]
                        }
                    if (fits) emit(out, moveRun(s, from, to, count), score)
                }
            }
        }
    }

    private fun moveRun(
        s: KlondikeState,
        from: Int,
        to: Int,
        count: Int,
    ): KlondikeState {
        val n = s.copy()
        val base = n.len[from] - count
        val moved = n.cols[from].copyOfRange(base, base + count)
        repeat(count) { n.pop(from) }
        moved.forEach { n.push(to, it) }
        n.record(Op(Op.COL_COL, a = from, b = to, count = count))
        return n
    }

    private fun runLength(
        s: KlondikeState,
        column: Int,
    ): Int {
        val up = s.up(column)
        val cards = s.cols[column]
        var run = 1
        while (run < up && stacks(cards[s.len[column] - run], cards[s.len[column] - run - 1])) run++
        return run
    }

    private fun stock(
        s: KlondikeState,
        firstEmpty: Int,
        out: MutableList<Child>,
    ) {
        if (s.size == 0) return
        val seen = HashSet<Int>()
        var cur = s.pos
        var used = 0
        seen += cur
        if (cur > 0) playWaste(s, cur, 0, firstEmpty, out)
        while (true) {
            if (cur < s.size) {
                cur = minOf(cur + drawCount, s.size)
                steps[used++] = Op.DRAW
            } else {
                cur = 0
                steps[used++] = Op.RECYCLE
            }
            if (!seen.add(cur) || used >= steps.size) break
            if (cur > 0) playWaste(s, cur, used, firstEmpty, out)
        }
    }

    /** The card below index [end] (the waste top after [used] steps) to its foundation or onto a column. */
    private fun playWaste(
        s: KlondikeState,
        end: Int,
        used: Int,
        firstEmpty: Int,
        out: MutableList<Child>,
    ) {
        val card = s.talon[end - 1]
        val bonus = 20 - minOf(used, 20)
        if (s.fitsFoundation(card)) {
            val n = s.copy()
            n.takeFromTalon(end - 1)
            n.found[suitOf(card)]++
            n.record(Op(Op.WASTE_FOUND, stock = steps.copyOf(used)))
            emit(out, n, 68 + bonus)
        }
        for (to in 0 until s.cols.size) {
            val fits =
                if (s.len[to] > 0) stacks(card, s.top(to)) else to == firstEmpty && rankOf(card) == 13
            if (!fits) continue
            val n = s.copy()
            n.takeFromTalon(end - 1)
            n.push(to, card)
            n.record(Op(Op.WASTE_COL, b = to, stock = steps.copyOf(used)))
            emit(out, n, 40 + bonus)
        }
    }

    private fun foundationToColumn(
        s: KlondikeState,
        firstEmpty: Int,
        out: MutableList<Child>,
    ) {
        for (suit in 0 until s.found.size) {
            if (s.found[suit] < 2) continue
            val card = (suit * 13 + s.found[suit] - 1).toByte()
            for (to in 0 until s.cols.size) {
                val fits =
                    if (s.len[to] > 0) stacks(card, s.top(to)) else to == firstEmpty && rankOf(card) == 13
                if (!fits) continue
                val n = s.copy()
                n.found[suit]--
                n.push(to, card)
                n.record(Op(Op.FOUND_COL, a = suit, b = to))
                emit(out, n, 10)
            }
        }
    }
}
