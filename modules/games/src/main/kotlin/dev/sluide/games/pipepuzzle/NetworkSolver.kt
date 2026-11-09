package dev.sluide.games.pipepuzzle

/**
 * Solves a Network board by deduction only, without guessing: it keeps, per cell, the orientations still possible and
 * removes those that cannot be part of any solution, until nothing changes. A Network solution connects every cell into
 * one tree (the tiles of a spanning tree have 2 × (cells − 1) openings, so a connected board without leaks has no
 * loop). The deductions:
 * - **edges**: an edge is open when every orientation left on one side opens it, closed when none does; the
 *   neighbour must agree. Openings toward the board edge are closed.
 * - **no loop**: an orientation that opens an edge between two cells already joined by open edges is removed.
 * - **no closed-off part**: an orientation that would join a group of cells whose openings are then all matched, but
 *   which is not the whole board, is removed.
 *
 * When every cell keeps exactly one orientation, the board has exactly one solution. The current turn of each tile does
 * not matter; only its type (the turns of its openings) does.
 */
internal object NetworkSolver {
    sealed interface Result {
        /** Exactly one orientation per cell is left, and together they are the only solution. */
        data class Solved(
            val tiles: List<Openings>,
        ) : Result

        /** Deduction ends with more than one orientation left at [ambiguous]. */
        data class Stuck(
            val ambiguous: List<Pos>,
        ) : Result

        /** No solution: some cell has no orientation left, or the known edges form a loop or a closed-off part. */
        data object Contradiction : Result
    }

    /**
     * Solves [tiles] (row-major, any turn); the tiles at [fixed] keep their given orientation. [noLoops] and
     * [noClosedParts] switch those deductions off, for tests that show a board needs them. The tiles must have
     * 2 × (cells − 1) openings in total, as the tiles of a spanning tree (every generated board) have; with more, every
     * connected board without leaks has a loop, which the solver reports as [Result.Contradiction].
     */
    fun solve(
        side: Int,
        tiles: List<Openings>,
        fixed: Set<Pos>,
        noLoops: Boolean = true,
        noClosedParts: Boolean = true,
    ): Result {
        require(tiles.size == side * side) { "${tiles.size} tiles for side $side" }
        val options =
            IntArray(side * side) { i ->
                if (posOf(i, side) in fixed) bit(tiles[i]) else tiles[i].rotations.fold(0) { m, o -> m or bit(o) }
            }
        return State(side, options, noLoops, noClosedParts).run()
    }

    private fun bit(openings: Openings): Int = 1 shl openings.bits

    /** For a set of orientations (bit i = `Openings(i)`), the openings all of them have. */
    private val ALWAYS_OPEN = IntArray(1 shl 16) { mask -> combine(mask, 15) { a, b -> a and b } }

    /** For a set of orientations, the openings any of them has. */
    private val MAYBE_OPEN = IntArray(1 shl 16) { mask -> combine(mask, 0) { a, b -> a or b } }

    /** Per direction, the set of orientations that open toward it. */
    private val HAVING =
        IntArray(4) { d -> (0 until 16).filter { it and (1 shl d) != 0 }.fold(0) { m, o -> m or (1 shl o) } }

    private inline fun combine(
        mask: Int,
        empty: Int,
        op: (Int, Int) -> Int,
    ): Int {
        if (mask == 0) return empty
        var result = -1
        for (o in 0 until 16) {
            if (mask and (1 shl o) == 0) continue
            result = if (result < 0) o else op(result, o)
        }
        return result
    }

    private class State(
        private val side: Int,
        private val options: IntArray,
        private val noLoops: Boolean,
        private val noClosedParts: Boolean,
    ) {
        private val cells = side * side
        private val neighbour = Array(cells) { i -> IntArray(4) { d -> neighbourOf(i, Dir.entries[d]) } }

        private fun neighbourOf(
            i: Int,
            dir: Dir,
        ): Int {
            val to = posOf(i, side).step(dir)
            return if (to.inBoard(side)) to.index(side) else -1
        }

        fun run(): Result {
            var dirty = BooleanArray(cells) { true }
            while (true) {
                if (!propagateEdges(dirty)) return Result.Contradiction
                val changed = BooleanArray(cells)
                when (pruneLoopsAndClosedParts(changed)) {
                    null -> return Result.Contradiction
                    false -> break
                    true -> dirty = changed
                }
            }
            val ambiguous = (0 until cells).filter { Integer.bitCount(options[it]) > 1 }
            if (ambiguous.isNotEmpty()) return Result.Stuck(ambiguous.map { posOf(it, side) })
            val tiles = options.map { Openings(Integer.numberOfTrailingZeros(it)) }
            val flow = Flow(side, tiles, Pos(0, 0))
            return if (flow.leaks.isEmpty() && flow.filled.size == cells) Result.Solved(tiles) else Result.Contradiction
        }

        /** Edge agreement and closed borders until nothing changes; false on a cell without orientations. */
        private fun propagateEdges(dirty: BooleanArray): Boolean {
            val queue = ArrayDeque<Int>()
            for (i in 0 until cells) if (dirty[i]) queue += i
            val queued = dirty.copyOf()
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                queued[i] = false
                for (d in 0 until 4) {
                    if (neighbour[i][d] < 0) options[i] = options[i] and HAVING[d].inv()
                }
                val mask = options[i]
                if (mask == 0) return false
                val always = ALWAYS_OPEN[mask]
                val maybe = MAYBE_OPEN[mask]
                for (d in 0 until 4) {
                    val n = neighbour[i][d]
                    if (n < 0) continue
                    val opposite = (d + 2) % 4
                    val before = options[n]
                    val after =
                        when {
                            always and (1 shl d) != 0 -> before and HAVING[opposite]
                            maybe and (1 shl d) == 0 -> before and HAVING[opposite].inv()
                            else -> before
                        }
                    if (after == before) continue
                    if (after == 0) return false
                    options[n] = after
                    if (!queued[n]) {
                        queued[n] = true
                        queue += n
                    }
                }
            }
            return true
        }

        /**
         * Joins the cells along the edges known to be open and removes the orientations that would close a loop or a
         * closed-off part. True when something was removed (marked in [changed]), false when nothing was, null on a
         * contradiction.
         */
        private fun pruneLoopsAndClosedParts(changed: BooleanArray): Boolean? {
            val parent = IntArray(cells) { it }
            val size = IntArray(cells) { 1 }
            val openings = IntArray(cells) { tileOpenings(it) }

            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                var c = x
                while (parent[c] != r) {
                    val next = parent[c]
                    parent[c] = r
                    c = next
                }
                return r
            }
            for (i in 0 until cells) {
                val always = ALWAYS_OPEN[options[i]]
                for (d in intArrayOf(Dir.E.ordinal, Dir.S.ordinal)) {
                    if (always and (1 shl d) == 0) continue
                    val a = find(i)
                    val b = find(neighbour[i][d])
                    if (a == b) return null
                    parent[b] = a
                    size[a] += size[b]
                    openings[a] += openings[b]
                }
            }

            // free ends of a group: the openings of its cells minus both ends of each of its (size - 1) open edges
            fun free(root: Int): Int = openings[root] - 2 * (size[root] - 1)
            for (i in 0 until cells) {
                if (find(i) == i && free(i) <= 0 && size[i] < cells) return null
            }
            var removed = false
            val roots = IntArray(4)
            for (i in 0 until cells) {
                val mask = options[i]
                if (Integer.bitCount(mask) < 2) continue
                val always = ALWAYS_OPEN[mask]
                var kept = mask
                for (o in 0 until 16) {
                    if (mask and (1 shl o) == 0) continue
                    val root = find(i)
                    var groups = 0
                    var joinedSize = size[root]
                    var joinedFree = free(root)
                    var loop = false
                    val newEdges = o and always.inv()
                    for (d in 0 until 4) {
                        if (newEdges and (1 shl d) == 0) continue
                        val r = find(neighbour[i][d])
                        if (r == root || (0 until groups).any { roots[it] == r }) {
                            loop = true
                            break
                        }
                        roots[groups++] = r
                        joinedSize += size[r]
                        joinedFree += free(r) - 2
                    }
                    val closedPart = !loop && joinedFree == 0 && joinedSize < cells
                    if ((noLoops && loop) || (noClosedParts && closedPart)) kept = kept and (1 shl o).inv()
                }
                if (kept == 0) return null
                if (kept != mask) {
                    options[i] = kept
                    changed[i] = true
                    for (n in neighbour[i]) if (n >= 0) changed[n] = true
                    removed = true
                }
            }
            return removed
        }

        /** The number of openings of the tile at [i], the same in every orientation. */
        private fun tileOpenings(i: Int): Int = Integer.bitCount(Integer.numberOfTrailingZeros(options[i]))
    }
}
