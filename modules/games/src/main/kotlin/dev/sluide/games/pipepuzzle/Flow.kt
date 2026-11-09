package dev.sluide.games.pipepuzzle

/**
 * Where the flow reaches on a board of [tiles] (row-major, [side] × [side]): a breadth-first search from [source] over
 * matching edges. Two tiles match on a shared edge when both open toward each other; an opening toward the board edge
 * never matches.
 */
internal class Flow(
    private val side: Int,
    tiles: List<Openings>,
    source: Pos,
) {
    /** Filled tiles grouped by distance from the source; index 0 holds the source. */
    val layers: List<List<Pos>>

    /** Filled tiles in breadth-first order. */
    val filled: Set<Pos>

    /** Every opening of a filled tile that does not meet a matching opening. */
    val leaks: List<Pair<Pos, Dir>>

    init {
        require(tiles.size == side * side) { "${tiles.size} tiles for side $side" }
        val seen = BooleanArray(side * side)
        val order = LinkedHashSet<Pos>()
        val groups = mutableListOf<List<Pos>>()
        val found = mutableListOf<Pair<Pos, Dir>>()
        var layer = listOf(source)
        seen[source.index(side)] = true
        while (layer.isNotEmpty()) {
            groups += layer
            order += layer
            val next = mutableListOf<Pos>()
            for (pos in layer) {
                for (dir in tiles[pos.index(side)].dirs) {
                    if (!matches(side, tiles, pos, dir)) {
                        found += pos to dir
                        continue
                    }
                    val to = pos.step(dir)
                    if (!seen[to.index(side)]) {
                        seen[to.index(side)] = true
                        next += to
                    }
                }
            }
            layer = next
        }
        layers = groups
        filled = order
        leaks = found
    }

    /** Network: every tile filled and nothing leaks. Path: the drain is filled and nothing leaks. */
    fun isSolved(
        mode: Mode,
        drain: Pos,
    ): Boolean =
        leaks.isEmpty() &&
            when (mode) {
                Mode.NETWORK -> filled.size == side * side
                Mode.PATH -> drain in filled
            }

    companion object {
        fun matches(
            side: Int,
            tiles: List<Openings>,
            pos: Pos,
            dir: Dir,
        ): Boolean {
            if (!tiles[pos.index(side)].has(dir)) return false
            val to = pos.step(dir)
            return to.inBoard(side) && tiles[to.index(side)].has(dir.opposite)
        }
    }
}
