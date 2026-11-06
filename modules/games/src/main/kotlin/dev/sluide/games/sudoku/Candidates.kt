package dev.sluide.games.sudoku

/**
 * Candidate state of the human-technique grader. [digits] holds a placed digit per cell, 0 for an empty cell and -1
 * for a cell that is filled with an unknown digit (hand-built test states); [masks] holds per empty cell the bitmask of
 * its candidates (bit d = digit d) and 0 for filled cells.
 */
internal class Candidates(
    val size: SudokuSize,
    val digits: IntArray,
    val masks: IntArray,
) {
    fun hasCandidate(
        index: Int,
        digit: Int,
    ): Boolean = digits[index] == 0 && masks[index] and (1 shl digit) != 0

    fun candidateSet(cell: Cell): Set<Int> {
        val m = masks[cell.row * size.side + cell.col]
        return (1..size.side).filter { m and (1 shl it) != 0 }.toSet()
    }

    fun place(
        index: Int,
        digit: Int,
    ) {
        digits[index] = digit
        masks[index] = 0
        val bit = (1 shl digit).inv()
        for (p in size.peerIdx[index]) masks[p] = masks[p] and bit
    }

    /** Removes the digits of [mask] from an empty cell; true when something was removed. */
    fun eliminate(
        index: Int,
        mask: Int,
    ): Boolean {
        if (digits[index] != 0 || masks[index] and mask == 0) return false
        masks[index] = masks[index] and mask.inv()
        return true
    }

    /** Bits of the digits already placed in [unit]. */
    fun placedMask(unit: IntArray): Int {
        var m = 0
        for (i in unit) if (digits[i] > 0) m = m or (1 shl digits[i])
        return m
    }

    /** Bit p set when the cell at position p of [unit] is empty and has [digit] as candidate. */
    fun posMask(
        unit: IntArray,
        digit: Int,
    ): Int {
        var m = 0
        for (p in unit.indices) if (hasCandidate(unit[p], digit)) m = m or (1 shl p)
        return m
    }

    val isSolved: Boolean get() = digits.none { it == 0 }

    /** An empty cell without candidates, or a unit where a digit has no place left. */
    fun isContradiction(): Boolean {
        for (i in digits.indices) if (digits[i] == 0 && masks[i] == 0) return true
        for (unit in size.unitIdx) {
            val placed = placedMask(unit)
            for (d in 1..size.side) if (placed and (1 shl d) == 0 && posMask(unit, d) == 0) return true
        }
        return false
    }

    companion object {
        /** Candidates by peer elimination; null when the grid has a conflict. */
        fun of(grid: Grid): Candidates? {
            if (grid.hasConflict()) return null
            val size = grid.size
            val digits = grid.digitsCopy()
            val masks = IntArray(digits.size)
            for (i in digits.indices) {
                if (digits[i] != 0) continue
                var used = 0
                for (p in size.peerIdx[i]) if (digits[p] > 0) used = used or (1 shl digits[p])
                masks[i] = size.fullMask and used.inv()
            }
            return Candidates(size, digits, masks)
        }

        /** Hand-built state: [candidates] are the empty cells, [filled] placed digits, every other cell is filled. */
        fun of(
            size: SudokuSize,
            candidates: Map<Cell, Set<Int>>,
            filled: Map<Cell, Int> = emptyMap(),
        ): Candidates {
            val digits = IntArray(size.side * size.side) { -1 }
            val masks = IntArray(digits.size)
            filled.forEach { (c, d) -> digits[c.row * size.side + c.col] = d }
            candidates.forEach { (c, set) ->
                val i = c.row * size.side + c.col
                digits[i] = 0
                masks[i] = set.fold(0) { m, d -> m or (1 shl d) }
            }
            return Candidates(size, digits, masks)
        }
    }
}
