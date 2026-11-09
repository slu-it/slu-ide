package dev.sluide.games.pipepuzzle

import kotlin.random.Random

/**
 * One puzzle being played: the current tiles, the flow they let through and the hints taken. Pure and not bound to a
 * thread; the model uses it on the FX thread. [random] picks the tile a hint turns.
 */
class PipePuzzleGame internal constructor(
    val puzzle: Puzzle,
    private val random: Random,
) {
    constructor(puzzle: Puzzle) : this(puzzle, Random.Default)

    val side: Int = puzzle.side

    private val tiles = puzzle.start.toMutableList()

    /** The cells the generated solution fills: every cell in Network mode, the route and its spurs in Path mode. */
    private val solutionCells = Flow(side, puzzle.solution, puzzle.source).filled

    private var flow = Flow(side, tiles, puzzle.source)

    val filled: Set<Pos> get() = flow.filled

    val leaks: List<Pair<Pos, Dir>> get() = flow.leaks

    var isSolved: Boolean = flow.isSolved(puzzle.mode, puzzle.drain)
        private set

    var hints: Int = 0
        private set

    fun tile(pos: Pos): Openings {
        require(pos.inBoard(side)) { "$pos outside the board" }
        return tiles[pos.index(side)]
    }

    fun isFixed(pos: Pos): Boolean = puzzle.isFixed(pos)

    /**
     * Turns a tile a quarter; false, and nothing changes, for a fixed tile, a cross, a position off the board or once
     * solved.
     */
    fun turn(
        pos: Pos,
        clockwise: Boolean,
    ): Boolean {
        if (isSolved || !pos.inBoard(side) || isFixed(pos)) return false
        val tile = tiles[pos.index(side)]
        if (tile.isCross) return false
        tiles[pos.index(side)] = if (clockwise) tile.rotatedCw() else tile.rotatedCcw()
        update()
        return true
    }

    /**
     * Turns one wrong tile, chosen at random, into its solution openings and counts the hint; null when none is wrong
     * or the puzzle is solved. Wrong means its openings differ from the solution's, so either turn of a straight that
     * matches counts as right. In Path mode only the generated route and its spurs are compared: turning a decoy
     * would not help.
     */
    fun hint(): Pos? {
        if (isSolved) return null
        val wrong =
            solutionCells.filter { pos ->
                val i = pos.index(side)
                !isFixed(pos) && !tiles[i].isCross && tiles[i] != puzzle.solution[i]
            }
        if (wrong.isEmpty()) return null
        val pos = wrong[random.nextInt(wrong.size)]
        tiles[pos.index(side)] = puzzle.solution[pos.index(side)]
        hints++
        update()
        return pos
    }

    /** Turns every tile back to how the puzzle started and clears the hints. */
    fun restart() {
        for (i in tiles.indices) tiles[i] = puzzle.start[i]
        hints = 0
        update()
    }

    /** Filled tiles grouped by distance from the source; index 0 holds only the source. */
    fun fillOrder(): List<List<Pos>> = flow.layers

    private fun update() {
        flow = Flow(side, tiles, puzzle.source)
        isSolved = flow.isSolved(puzzle.mode, puzzle.drain)
    }
}
