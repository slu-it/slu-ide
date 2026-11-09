package dev.sluide.games.pipepuzzle

import kotlin.random.Random

/**
 * Makes Path puzzles: a self-avoiding route from the source to the drain of at least 1.5 × side cells (a random
 * staircase between them, lengthened by detours), a few short spurs off the route that end in end-caps, and random
 * decoy tiles in every other cell. The generated route is sealed, so it solves the puzzle; any other sealed route does
 * too.
 */
internal class PathGenerator(
    private val attempts: Int = 1000,
) {
    fun generate(
        side: Int,
        random: Random,
    ): Puzzle {
        repeat(attempts) {
            val puzzle = attempt(side, random)
            if (puzzle != null) return puzzle
        }
        error("no path puzzle after $attempts attempts")
    }

    private fun attempt(
        side: Int,
        random: Random,
    ): Puzzle? {
        val cells = side * side
        val source = posOf(random.nextInt(cells), side)
        val drains = (0 until cells).map { posOf(it, side) }.filter { it.distanceTo(source) >= side }
        if (drains.isEmpty()) return null
        val drain = drains[random.nextInt(drains.size)]
        val minLength = (3 * side + 1) / 2
        val route = route(side, source, drain, minLength + random.nextInt(side / 2 + 1), random) ?: return null
        val board = Board(side)
        for (k in 0 until route.size - 1) board.link(route[k], route[k + 1])
        val spurs = minSpurs(side) + random.nextInt(2)
        if (!placeSpurs(board, route, spurs, random)) return null
        if (decoysBesideRoute(board, route) < (side + 1) / 2) return null
        for (i in 0 until cells) {
            if (board.bits[i] == 0) board.bits[i] = decoy(random)
        }
        val solution = board.bits.map { Openings(it) }
        check(Flow(side, solution, source).isSolved(Mode.PATH, drain)) { "the generated route is not sealed" }
        val start = Scramble.start(Mode.PATH, side, source, drain, solution, random)
        return Puzzle(Mode.PATH, side, source, drain, solution, start)
    }

    /**
     * A random monotone staircase from [from] to [to], lengthened by detours (a step u → v becomes u → u' → v' → v
     * with u' and v' beside it) until it has at least [length] cells; null when the detours run out of room.
     */
    private fun route(
        side: Int,
        from: Pos,
        to: Pos,
        length: Int,
        random: Random,
    ): List<Pos>? {
        val vertical = if (to.row > from.row) Dir.S else Dir.N
        val horizontal = if (to.col > from.col) Dir.E else Dir.W
        val steps =
            (List(Math.abs(to.row - from.row)) { vertical } + List(Math.abs(to.col - from.col)) { horizontal })
                .shuffled(random)
        val route = steps.runningFold(from) { at, dir -> at.step(dir) }.toMutableList()
        val used = route.toHashSet()
        var tries = 0
        while (route.size < length) {
            if (tries++ > DETOUR_TRIES) return null
            val k = random.nextInt(route.size - 1)
            val u = route[k]
            val v = route[k + 1]
            val sideways = if (u.row == v.row) listOf(Dir.N, Dir.S) else listOf(Dir.E, Dir.W)
            val dir = sideways[random.nextInt(2)]
            val u2 = u.step(dir)
            val v2 = v.step(dir)
            if (!u2.inBoard(side) || !v2.inBoard(side) || u2 in used || v2 in used) continue
            route.add(k + 1, u2)
            route.add(k + 2, v2)
            used += u2
            used += v2
        }
        return route
    }

    /** Adds [count] spurs of 1 to 3 cells off inner route cells, at most one per route cell; false without room. */
    private fun placeSpurs(
        board: Board,
        route: List<Pos>,
        count: Int,
        random: Random,
    ): Boolean {
        val side = board.side
        val branched = mutableSetOf<Pos>()
        var placed = 0
        var tries = 0
        while (placed < count) {
            if (tries++ > SPUR_TRIES) return false
            val root = route[1 + random.nextInt(route.size - 2)]
            if (root in branched) continue
            val length = 1 + random.nextInt(MAX_SPUR)
            val spur = mutableListOf(root)
            while (spur.size <= length) {
                val free =
                    Dir.entries
                        .map { spur.last().step(it) }
                        .filter { it.inBoard(side) && board.bits[it.index(side)] == 0 && it !in spur }
                if (free.isEmpty()) break
                spur += free[random.nextInt(free.size)]
            }
            if (spur.size < 2) continue
            for (k in 0 until spur.size - 1) board.link(spur[k], spur[k + 1])
            branched += root
            placed++
        }
        return true
    }

    /** The cells left for decoys (outside the route and its spurs) that lie beside a route cell. */
    private fun decoysBesideRoute(
        board: Board,
        route: List<Pos>,
    ): Int {
        val side = board.side
        return (0 until side * side).count { i ->
            board.bits[i] == 0 && Dir.entries.any { posOf(i, side).step(it) in route }
        }
    }

    /** A random tile: mostly corners, straights, ends and T pieces, rarely a cross, in a random turn. */
    private fun decoy(random: Random): Int {
        val roll = random.nextInt(100)
        val shape =
            when {
                roll < 22 -> Openings.of(Dir.N)
                roll < 46 -> Openings.of(Dir.N, Dir.S)
                roll < 76 -> Openings.of(Dir.N, Dir.E)
                roll < 98 -> Openings.of(Dir.N, Dir.E, Dir.S)
                else -> Openings.CROSS
            }
        return (0 until random.nextInt(4)).fold(shape) { t, _ -> t.rotatedCw() }.bits
    }

    private class Board(
        val side: Int,
    ) {
        val bits = IntArray(side * side)

        fun link(
            a: Pos,
            b: Pos,
        ) {
            val dir = Dir.entries.first { a.step(it) == b }
            bits[a.index(side)] = bits[a.index(side)] or dir.bit
            bits[b.index(side)] = bits[b.index(side)] or dir.opposite.bit
        }
    }

    companion object {
        /** The fewest spurs a route of a board with this side gets. */
        fun minSpurs(side: Int): Int = maxOf(1, side / 3)

        private const val DETOUR_TRIES = 2000
        private const val SPUR_TRIES = 200
        private const val MAX_SPUR = 3
    }
}
