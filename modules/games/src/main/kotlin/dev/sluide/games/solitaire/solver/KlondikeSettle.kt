package dev.sluide.games.solitaire.solver

/**
 * What the search does to a state without branching, and how it names a state.
 *
 * Safe foundation moves ([settle]): a card that fits its foundation goes up at once when it is an Ace or a 2, or when
 * both foundations of the other colour hold at least rank-1 (no card is left that needs it as a base) and the other
 * suit of its own colour holds at least rank-2 (so no card of rank-2 is left that would need a rank-1 card of the
 * other colour back from a foundation, which in turn would need this card as its base). Sound.
 * The waste top is included only when [autoWaste]: with Draw 1 and unlimited recycling every card stays reachable
 * wherever the waste is cut, so it is sound; with Draw 3 removing a waste card shifts the groups of three of the
 * rest, so it is a heuristic of the first pass.
 */
internal class KlondikeSettle(
    private val draw3: Boolean,
    private val autoWaste: Boolean,
) {
    fun settle(s: KlondikeState) {
        if (decided(s)) {
            playOut(s)
            return
        }
        var changed = true
        while (changed) {
            changed = false
            for (i in 0 until s.cols.size) {
                if (s.len[i] == 0) continue
                val card = s.top(i)
                if (s.fitsFoundation(card) && safe(s, card)) {
                    s.pop(i)
                    s.found[suitOf(card)]++
                    s.record(Op(Op.COL_FOUND, a = i))
                    changed = true
                }
            }
            if (autoWaste && s.pos > 0) {
                val card = s.talon[s.pos - 1]
                if (s.fitsFoundation(card) && safe(s, card)) {
                    s.takeFromTalon(s.pos - 1)
                    s.found[suitOf(card)]++
                    s.record(Op(Op.WASTE_FOUND))
                    changed = true
                }
            }
        }
    }

    private fun safe(
        s: KlondikeState,
        card: Byte,
    ): Boolean {
        val rank = rankOf(card)
        if (rank <= 2) return true
        val others = if (redOf(card)) BLACK else RED
        val sameColour = if (redOf(card)) RED else BLACK
        val sibling = sameColour.first { it != suitOf(card) }
        return others.all { s.found[it] >= rank - 1 } && s.found[sibling] >= rank - 2
    }

    /** Stock and waste empty and every card face up: moving to the foundations alone wins. */
    private fun decided(s: KlondikeState): Boolean = s.size == 0 && s.down.all { it == 0 }

    private fun playOut(s: KlondikeState) {
        var changed = true
        while (changed && !s.isWon()) {
            changed = false
            for (i in 0 until s.cols.size) {
                while (s.len[i] > 0 && s.fitsFoundation(s.top(i))) {
                    val card = s.pop(i)
                    s.found[suitOf(card)]++
                    s.record(Op(Op.COL_FOUND, a = i))
                    changed = true
                }
            }
        }
    }

    /**
     * A 64-bit hash of the position. The columns are summed, so a permutation of them hashes alike (they are
     * interchangeable); with Draw 1 the talon is read from the waste end on, as the cut of the cycle does not matter.
     */
    fun hash(s: KlondikeState): Long {
        var h = 0L
        for (i in 0 until s.cols.size) {
            var c = mix(s.len[i].toLong() * 31 + s.down[i])
            for (k in 0 until s.len[i]) c = mix(c * MUL + s.cols[i][k] + 1)
            h += c
        }
        for (f in s.found) h = mix(h * MUL + f)
        h = mix(h * MUL + s.size)
        val start = if (draw3) 0 else s.pos
        for (k in 0 until s.size) h = mix(h * MUL + s.talon[(start + k) % s.size] + 1)
        if (draw3) h = mix(h * MUL + s.pos)
        return h
    }

    private fun mix(value: Long): Long {
        var z = value + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private companion object {
        const val MUL = 0x9E3779B1L
        val RED = intArrayOf(1, 2)
        val BLACK = intArrayOf(0, 3)
    }
}
