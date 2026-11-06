package dev.sluide.games.sudoku

internal class Placement(val index: Int, val digit: Int)

private fun bit(d: Int) = 1 shl d

/** Calls [visit] with every [k]-element combination of the indexes 0 until [n]. */
private fun combinations(
    n: Int,
    k: Int,
    visit: (IntArray) -> Unit,
) {
    val pick = IntArray(k)

    fun rec(
        start: Int,
        depth: Int,
    ) {
        if (depth == k) {
            visit(pick)
            return
        }
        for (x in start until n) {
            pick[depth] = x
            rec(x + 1, depth + 1)
        }
    }
    if (k <= n) rec(0, 0)
}

internal fun Candidates.nakedSingle(): Placement? {
    for (i in masks.indices) {
        if (digits[i] == 0 && Integer.bitCount(masks[i]) == 1) {
            return Placement(i, Integer.numberOfTrailingZeros(masks[i]))
        }
    }
    return null
}

internal fun Candidates.hiddenSingle(): Placement? {
    for (unit in size.unitIdx) {
        val placed = placedMask(unit)
        for (d in 1..size.side) {
            if (placed and bit(d) != 0) continue
            val pos = posMask(unit, d)
            if (Integer.bitCount(pos) == 1) return Placement(unit[Integer.numberOfTrailingZeros(pos)], d)
        }
    }
    return null
}

/** A digit of a box confined to one row or column leaves the rest of that line. */
internal fun Candidates.pointing(): Boolean {
    val n = size.side
    var changed = false
    for (b in 0 until n) {
        val unit = size.unitIdx[2 * n + b]
        for (d in 1..n) {
            var count = 0
            var row = -1
            var col = -1
            for (i in unit) {
                if (!hasCandidate(i, d)) continue
                count++
                if (count == 1) {
                    row = i / n
                    col = i % n
                } else {
                    if (i / n != row) row = -1
                    if (i % n != col) col = -1
                }
            }
            if (count < 2) continue
            if (row >= 0) {
                for (c in 0 until n) {
                    val j = row * n + c
                    if (size.boxIndex(j) != b) changed = eliminate(j, bit(d)) || changed
                }
            }
            if (col >= 0) {
                for (r in 0 until n) {
                    val j = r * n + col
                    if (size.boxIndex(j) != b) changed = eliminate(j, bit(d)) || changed
                }
            }
        }
    }
    return changed
}

/** A digit of a row or column confined to one box leaves the rest of that box. */
internal fun Candidates.claiming(): Boolean {
    val n = size.side
    var changed = false
    for (u in 0 until 2 * n) {
        val kind = if (u < n) 0 else 1
        for (d in 1..n) {
            var count = 0
            var box = -1
            for (i in size.unitIdx[u]) {
                if (!hasCandidate(i, d)) continue
                count++
                val b = size.boxIndex(i)
                box = if (count == 1 || b == box) b else -2
            }
            if (count < 2 || box < 0) continue
            for (j in size.unitIdx[2 * n + box]) {
                if (size.cellUnits[j][kind] != u) changed = eliminate(j, bit(d)) || changed
            }
        }
    }
    return changed
}

/** [k] cells of a unit whose candidates together are only [k] digits: those leave the other cells. */
internal fun Candidates.nakedSubset(k: Int): Boolean {
    var changed = false
    for (unit in size.unitIdx) {
        val cells = unit.filter { digits[it] == 0 && Integer.bitCount(masks[it]) in 2..k }
        combinations(cells.size, k) { pick ->
            val chosen = IntArray(k) { cells[pick[it]] }
            val union = chosen.fold(0) { m, i -> m or masks[i] }
            if (Integer.bitCount(union) == k) {
                for (j in unit) if (j !in chosen) changed = eliminate(j, union) || changed
            }
        }
    }
    return changed
}

/** [k] digits of a unit that fit only into the same [k] cells: other digits leave those cells. */
internal fun Candidates.hiddenSubset(k: Int): Boolean {
    var changed = false
    for (unit in size.unitIdx) {
        val placed = placedMask(unit)
        val pos = IntArray(size.side + 1)
        val digitList = ArrayList<Int>()
        for (d in 1..size.side) {
            if (placed and bit(d) != 0) continue
            pos[d] = posMask(unit, d)
            if (Integer.bitCount(pos[d]) in 2..k) digitList.add(d)
        }
        combinations(digitList.size, k) { pick ->
            val chosen = IntArray(k) { digitList[pick[it]] }
            val union = chosen.fold(0) { m, d -> m or pos[d] }
            if (Integer.bitCount(union) == k) {
                val keep = chosen.fold(0) { m, d -> m or bit(d) }
                for (p in unit.indices) {
                    if (union and (1 shl p) != 0) changed = eliminate(unit[p], keep.inv()) || changed
                }
            }
        }
    }
    return changed
}

/** X-Wing (k = 2) and Swordfish (k = 3), with rows and with columns as the base lines. */
internal fun Candidates.fish(k: Int): Boolean {
    val n = size.side
    var changed = false
    for (rowsBase in listOf(true, false)) {
        for (d in 1..n) {
            val pos = IntArray(n) { l -> posMask(size.unitIdx[(if (rowsBase) 0 else n) + l], d) }
            val lines = (0 until n).filter { Integer.bitCount(pos[it]) in 2..k }
            combinations(lines.size, k) { pick ->
                val chosen = IntArray(k) { lines[pick[it]] }
                val union = chosen.fold(0) { m, l -> m or pos[l] }
                if (Integer.bitCount(union) == k) {
                    for (p in 0 until n) {
                        if (union and (1 shl p) == 0) continue
                        for (q in 0 until n) {
                            if (q in chosen) continue
                            val j = if (rowsBase) q * n + p else p * n + q
                            changed = eliminate(j, bit(d)) || changed
                        }
                    }
                }
            }
        }
    }
    return changed
}

/** Pivot {x,y} with wings {x,z} and {y,z} it sees: z leaves every cell that sees both wings. */
internal fun Candidates.xyWing(): Boolean {
    var changed = false
    for (pivot in masks.indices) {
        if (digits[pivot] != 0 || Integer.bitCount(masks[pivot]) != 2) continue
        val pm = masks[pivot]
        val wings = size.peerIdx[pivot].filter { digits[it] == 0 && Integer.bitCount(masks[it]) == 2 }
        for (w1 in wings) {
            val shared = masks[w1] and pm
            if (Integer.bitCount(shared) != 1) continue
            val z = masks[w1] and pm.inv()
            val want = (pm and shared.inv()) or z
            for (w2 in wings) {
                if (w2 == w1 || masks[w2] != want) continue
                for (j in size.peerIdx[w1]) {
                    if (j != w2 && j != pivot && size.sees(j, w2)) changed = eliminate(j, z) || changed
                }
            }
        }
    }
    return changed
}
