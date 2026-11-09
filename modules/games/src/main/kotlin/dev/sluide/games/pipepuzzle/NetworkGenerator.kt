package dev.sluide.games.pipepuzzle

import kotlin.random.Random

/**
 * Makes Network puzzles with exactly one solution that [NetworkSolver] reaches by deduction. A random spanning tree
 * over all cells (Wilson's loop-erased random walks, so the shapes vary and long straight corridors stay rare) gets
 * two far-apart leaves as source and drain. While the solver gets stuck, the tree is changed near an ambiguous cell:
 * an edge there is added and another edge of the loop it closes is removed. After [perturbations] changes the tree is
 * dropped and a new one is drawn, at most [trees] times.
 */
internal class NetworkGenerator(
    private val perturbations: Int = 60,
    private val trees: Int = 500,
) {
    fun generate(
        side: Int,
        random: Random,
    ): Puzzle {
        repeat(trees) nextTree@{
            val tree = Tree(side)
            tree.grow(random)
            if (!tree.fits()) return@nextTree
            val (source, drain) = tree.farLeaves(random) ?: return@nextTree
            repeat(perturbations + 1) {
                when (val result = NetworkSolver.solve(side, tree.tiles(), setOf(source, drain))) {
                    is NetworkSolver.Result.Solved -> {
                        val solution = tree.tiles()
                        check(result.tiles == solution) { "the solver found another solution" }
                        val start = Scramble.start(Mode.NETWORK, side, source, drain, solution, random)
                        return Puzzle(Mode.NETWORK, side, source, drain, solution, start)
                    }

                    is NetworkSolver.Result.Stuck -> {
                        if (!tree.perturb(result.ambiguous, setOf(source, drain), random)) return@nextTree
                    }

                    NetworkSolver.Result.Contradiction -> {
                        error("the solver refused a valid tree")
                    }
                }
            }
        }
        error("no uniquely solvable network after $trees trees")
    }

    /** A spanning tree as the openings of every cell; an edge is open on both of its cells. */
    private class Tree(
        private val side: Int,
    ) {
        private val cells = side * side
        private val bits = IntArray(cells)
        private val maxCrosses = cells * MAX_CROSS_PERCENT / 100
        private val maxEnds = cells * MAX_END_PERCENT / 100

        fun tiles(): List<Openings> = bits.map { Openings(it) }

        private fun degree(i: Int) = Integer.bitCount(bits[i])

        private fun neighbour(
            i: Int,
            dir: Dir,
        ): Int {
            val to = posOf(i, side).step(dir)
            return if (to.inBoard(side)) to.index(side) else -1
        }

        private fun link(
            i: Int,
            dir: Dir,
        ) {
            bits[i] = bits[i] or dir.bit
            val n = neighbour(i, dir)
            bits[n] = bits[n] or dir.opposite.bit
        }

        private fun unlink(
            i: Int,
            dir: Dir,
        ) {
            bits[i] = bits[i] and dir.bit.inv()
            val n = neighbour(i, dir)
            bits[n] = bits[n] and dir.opposite.bit.inv()
        }

        private fun crosses() = (0 until cells).count { degree(it) == 4 }

        fun fits(): Boolean = crosses() <= maxCrosses && (0 until cells).count { degree(it) == 1 } <= maxEnds

        /**
         * Wilson's algorithm: from each cell outside the tree a random walk runs until it meets the tree; its
         * loop-erased path joins the tree. A step onto a tree cell that would become a cross beyond [maxCrosses] is
         * refused unless the walk is boxed in.
         */
        fun grow(random: Random) {
            val inTree = BooleanArray(cells)
            val next = arrayOfNulls<Dir>(cells)
            inTree[random.nextInt(cells)] = true
            var crosses = 0
            for (start in (0 until cells).shuffled(random)) {
                if (inTree[start]) continue
                var at = start
                var refused = 0
                while (!inTree[at]) {
                    val dir = Dir.entries[random.nextInt(4)]
                    val to = neighbour(at, dir)
                    if (to < 0) continue
                    if (inTree[to] && degree(to) == 3 && crosses >= maxCrosses && refused++ < BOXED_IN) continue
                    refused = 0
                    next[at] = dir
                    at = to
                }
                if (degree(at) == 3) crosses++
                at = start
                while (!inTree[at]) {
                    val dir = checkNotNull(next[at])
                    inTree[at] = true
                    link(at, dir)
                    at = neighbour(at, dir)
                }
            }
        }

        /** A random pair of leaves at least a side apart (Manhattan distance), or null when there is none. */
        fun farLeaves(random: Random): Pair<Pos, Pos>? {
            val leaves = (0 until cells).filter { degree(it) == 1 }.map { posOf(it, side) }
            val pairs = leaves.flatMap { a -> leaves.filter { b -> a.distanceTo(b) >= side && a != b }.map { a to it } }
            return if (pairs.isEmpty()) null else pairs[random.nextInt(pairs.size)]
        }

        /**
         * Adds a tree edge at a random [ambiguous] cell and removes another edge of the loop that closes, keeping
         * [ends] as leaves and the tile mix within limits; false when no such change is found.
         */
        fun perturb(
            ambiguous: List<Pos>,
            ends: Set<Pos>,
            random: Random,
        ): Boolean {
            repeat(PERTURB_TRIES) {
                val a = ambiguous[random.nextInt(ambiguous.size)].index(side)
                val dir = Dir.entries[random.nextInt(4)]
                val b = neighbour(a, dir)
                if (b < 0 || bits[a] and dir.bit != 0 || posOf(b, side) in ends) return@repeat
                val loop = pathBetween(a, b)
                val (cut, cutDir) = loop[random.nextInt(loop.size)]
                unlink(cut, cutDir)
                link(a, dir)
                if (fits() && ends.all { degree(it.index(side)) == 1 }) return true
                unlink(a, dir)
                link(cut, cutDir)
            }
            return false
        }

        /** The tree edges on the path from [from] to [to], each as a cell and the direction it opens. */
        private fun pathBetween(
            from: Int,
            to: Int,
        ): List<Pair<Int, Dir>> {
            val cameBy = arrayOfNulls<Dir>(cells)
            val seen = BooleanArray(cells)
            val queue = ArrayDeque<Int>()
            queue += from
            seen[from] = true
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                if (i == to) break
                for (dir in Dir.entries) {
                    if (bits[i] and dir.bit == 0) continue
                    val n = neighbour(i, dir)
                    if (seen[n]) continue
                    seen[n] = true
                    cameBy[n] = dir
                    queue += n
                }
            }
            val path = mutableListOf<Pair<Int, Dir>>()
            var at = to
            while (at != from) {
                val dir = checkNotNull(cameBy[at])
                val back = neighbour(at, dir.opposite)
                path += back to dir
                at = back
            }
            return path
        }
    }

    private companion object {
        const val MAX_CROSS_PERCENT = 5
        const val MAX_END_PERCENT = 35
        const val BOXED_IN = 16
        const val PERTURB_TRIES = 20
    }
}
