package dev.sluide.games.sudoku

import kotlin.random.Random

object Solver {
    /** Number of solutions, counting stops at [limit]; 0 for a conflicting or unsolvable grid. */
    fun countSolutions(
        grid: Grid,
        limit: Int = 2,
    ): Int = if (limit <= 0) 0 else Search(grid, null, limit).run().found

    fun solve(grid: Grid): Grid? = Search(grid, null, 1).run().solution

    internal fun randomFull(
        size: SudokuSize,
        random: Random,
    ): Grid = checkNotNull(Search(Grid.empty(size), random, 1).run().solution)

    /** Bitmask backtracking, always continuing with the most constrained cell. */
    private class Search(
        grid: Grid,
        private val random: Random?,
        private val limit: Int,
    ) {
        private val size = grid.size
        private val digits = grid.digitsCopy()
        private val used = IntArray(3 * size.side)
        private var conflict = false
        var found = 0
            private set
        var solution: Grid? = null
            private set

        init {
            for (i in digits.indices) {
                val d = digits[i]
                if (d == 0) continue
                for (u in size.cellUnits[i]) {
                    if (used[u] and (1 shl d) != 0) conflict = true
                    used[u] = used[u] or (1 shl d)
                }
            }
        }

        fun run(): Search {
            if (!conflict) search()
            return this
        }

        private fun search() {
            var best = -1
            var bestMask = 0
            var bestCount = Int.MAX_VALUE
            for (i in digits.indices) {
                if (digits[i] != 0) continue
                val u = size.cellUnits[i]
                val mask = size.fullMask and (used[u[0]] or used[u[1]] or used[u[2]]).inv()
                val count = Integer.bitCount(mask)
                if (count == 0) return
                if (count < bestCount) {
                    best = i
                    bestMask = mask
                    bestCount = count
                    if (count == 1) break
                }
            }
            if (best < 0) {
                found++
                if (solution == null) solution = Grid.ofDigits(size, digits.copyOf())
                return
            }
            val options = (1..size.side).filter { bestMask and (1 shl it) != 0 }
            val ordered = if (random != null) options.shuffled(random) else options
            val u = size.cellUnits[best]
            for (d in ordered) {
                val bit = 1 shl d
                digits[best] = d
                for (x in u) used[x] = used[x] or bit
                search()
                for (x in u) used[x] = used[x] and bit.inv()
                digits[best] = 0
                if (found >= limit) return
            }
        }
    }
}
