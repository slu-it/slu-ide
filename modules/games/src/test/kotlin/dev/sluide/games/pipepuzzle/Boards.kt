package dev.sluide.games.pipepuzzle

import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Box-drawing characters per openings value, index = bits; '·' for none. */
private const val GLYPHS = "·╵╶└╷│┌├╴┘─┴┐┤┬┼"

/** A board written as rows of box-drawing characters, e.g. `"╶┐"`, `"╶┘"`; returns the tiles row-major. */
internal fun board(vararg rows: String): List<Openings> {
    require(rows.all { it.length == rows.size }) { "a square board is needed" }
    return rows.flatMap { row ->
        row.map { glyph ->
            val bits = GLYPHS.indexOf(glyph)
            require(bits >= 0) { "unknown tile '$glyph'" }
            Openings(bits)
        }
    }
}

/** The rows of a board as box-drawing characters, with S and D after the rows of the source and the drain. */
internal fun render(
    side: Int,
    tiles: List<Openings>,
    source: Pos? = null,
    drain: Pos? = null,
): String =
    (0 until side).joinToString("\n") { row ->
        val line = (0 until side).joinToString("") { col -> GLYPHS[tiles[row * side + col].bits].toString() }
        val marks =
            listOfNotNull(
                source?.takeIf { it.row == row }?.let { "S@${it.col}" },
                drain?.takeIf { it.row == row }?.let { "D@${it.col}" },
            )
        if (marks.isEmpty()) line else "$line  ${marks.joinToString(" ")}"
    }

internal fun Puzzle.cells(): List<Pos> = (0 until side * side).map { posOf(it, side) }

/**
 * Counts the Network solutions of [tiles] (any turn; the tiles at [fixed] as given) by trying every turn, up to
 * [limit]: a solution has no openings toward the edge, matching openings on every shared edge and one connected
 * network. Independent of [NetworkSolver], to check it.
 */
internal fun countNetworkSolutions(
    side: Int,
    tiles: List<Openings>,
    fixed: Set<Pos> = emptySet(),
    limit: Int = 2,
): Int {
    val chosen = MutableList(side * side) { Openings.NONE }
    var found = 0

    fun fits(
        pos: Pos,
        tile: Openings,
    ): Boolean {
        if (pos.row == 0 && tile.has(Dir.N)) return false
        if (pos.col == 0 && tile.has(Dir.W)) return false
        if (pos.row == side - 1 && tile.has(Dir.S)) return false
        if (pos.col == side - 1 && tile.has(Dir.E)) return false
        if (pos.row > 0 && tile.has(Dir.N) != chosen[pos.step(Dir.N).index(side)].has(Dir.S)) return false
        if (pos.col > 0 && tile.has(Dir.W) != chosen[pos.step(Dir.W).index(side)].has(Dir.E)) return false
        return true
    }

    fun place(i: Int) {
        if (found >= limit) return
        if (i == side * side) {
            if (Flow(side, chosen, Pos(0, 0)).filled.size == side * side) found++
            return
        }
        val pos = posOf(i, side)
        val turns = if (pos in fixed) listOf(tiles[i]) else tiles[i].rotations
        for (tile in turns) {
            if (!fits(pos, tile)) continue
            chosen[i] = tile
            place(i + 1)
        }
    }
    place(0)
    return found
}

/** The start is not solved, and at least 60 % of the turnable tiles other than crosses start wrong by openings. */
internal fun assertScrambled(
    puzzle: Puzzle,
    label: String,
) {
    val side = puzzle.side
    assertFalse(Flow(side, puzzle.start, puzzle.source).isSolved(puzzle.mode, puzzle.drain), label)
    val turnable =
        (0 until side * side).filter { i ->
            val pos = posOf(i, side)
            pos != puzzle.source && pos != puzzle.drain && !puzzle.solution[i].isCross
        }
    val wrong = turnable.count { puzzle.start[it] != puzzle.solution[it] }
    assertTrue(wrong * 10 >= turnable.size * 6, "$label: $wrong of ${turnable.size} start wrong")
}
