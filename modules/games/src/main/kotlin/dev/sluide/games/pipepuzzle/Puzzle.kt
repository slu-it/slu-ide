package dev.sluide.games.pipepuzzle

/**
 * One puzzle: [solution] and [start] hold a tile per cell, row-major. [source] and [drain] are fixed tiles with one
 * opening; every other start tile is a turn of its solution tile. Both lists are copied.
 */
class Puzzle(
    val mode: Mode,
    val side: Int,
    val source: Pos,
    val drain: Pos,
    solution: List<Openings>,
    start: List<Openings>,
) {
    val solution: List<Openings> = solution.toList()
    val start: List<Openings> = start.toList()

    init {
        require(side > 1) { "side $side" }
        require(solution.size == side * side) { "solution has ${solution.size} cells, expected ${side * side}" }
        require(start.size == side * side) { "start has ${start.size} cells, expected ${side * side}" }
        require(source.inBoard(side)) { "source $source outside the board" }
        require(drain.inBoard(side)) { "drain $drain outside the board" }
        require(source != drain) { "source and drain at $source" }
        for (i in solution.indices) {
            require(solution[i].count > 0) { "empty tile at ${posOf(i, side)}" }
            require(start[i] in solution[i].rotations) { "start tile at ${posOf(i, side)} is no turn of the solution" }
        }
        for (end in listOf(source, drain)) {
            val i = end.index(side)
            require(solution[i].count == 1) { "source or drain at $end has ${solution[i].count} openings" }
            require(start[i] == solution[i]) { "source or drain at $end is turned" }
        }
    }

    internal fun isFixed(pos: Pos): Boolean = pos == source || pos == drain
}
