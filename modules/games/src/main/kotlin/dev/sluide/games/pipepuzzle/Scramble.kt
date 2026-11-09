package dev.sluide.games.pipepuzzle

import kotlin.random.Random

/** Turns the tiles of a solution into a start position. */
internal object Scramble {
    /** At least this share of the turnable tiles that are not crosses starts with openings other than the solution's. */
    const val MIN_WRONG_SHARE = 0.6

    private const val ATTEMPTS = 1000

    /**
     * Gives every turnable tile other than a cross a random turn, then turns random right tiles wrong until
     * [MIN_WRONG_SHARE] is reached; a start that is already solved is rolled again, up to [ATTEMPTS] times.
     *
     * @throws IllegalStateException when every roll is solved, e.g. when no turnable tile can break the route
     */
    fun start(
        mode: Mode,
        side: Int,
        source: Pos,
        drain: Pos,
        solution: List<Openings>,
        random: Random,
    ): List<Openings> {
        val turnable =
            solution.indices.filter { i ->
                val pos = posOf(i, side)
                pos != source && pos != drain && !solution[i].isCross
            }
        val needed = Math.ceil(MIN_WRONG_SHARE * turnable.size).toInt()
        repeat(ATTEMPTS) {
            val tiles = solution.toMutableList()
            for (i in turnable) tiles[i] = turned(solution[i], random.nextInt(4))
            val right = turnable.filter { tiles[it] == solution[it] }.shuffled(random).toMutableList()
            var wrong = turnable.size - right.size
            while (wrong < needed) {
                val i = right.removeLast()
                val others = solution[i].rotations.drop(1)
                tiles[i] = others[random.nextInt(others.size)]
                wrong++
            }
            if (!Flow(side, tiles, source).isSolved(mode, drain)) return tiles.toList()
        }
        error("every one of $ATTEMPTS scrambles was already solved")
    }

    private fun turned(
        tile: Openings,
        quarters: Int,
    ): Openings = (0 until quarters).fold(tile) { t, _ -> t.rotatedCw() }
}
