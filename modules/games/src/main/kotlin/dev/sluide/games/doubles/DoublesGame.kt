package dev.sluide.games.doubles

import kotlin.random.Random

internal data class Cell(
    val row: Int,
    val col: Int,
)

internal data class Tile(
    val id: Int,
    val value: Int,
    val cell: Cell,
)

/** A tile of the board before the move that changes its cell; merge partners are included (both slide to the merge cell). */
internal data class Slide(
    val id: Int,
    val value: Int,
    val from: Cell,
    val to: Cell,
)

/** [merged]: the tiles created by merges (new ids, at the merge cell). [spawned]: the new 2 or 4. [gained]: score added.
 * Both merge partners slide to the merged tile's cell; the merged tile has a new id at that cell.
 */
internal data class MoveResult(
    val slides: List<Slide>,
    val merged: List<Tile>,
    val spawned: Tile?,
    val gained: Int,
)

/**
 * The rules of Doubles, a sliding-tile game: no JavaFX, not thread-safe. A spawned tile is a 2 (90 %) or a 4 in a
 * random empty cell; the cell is drawn first, then the value. Ids are unique and increasing for the game's lifetime.
 */
internal class DoublesGame private constructor(
    val size: BoardSize,
    private val random: Random,
    placeStartTiles: Boolean,
) {
    constructor(size: BoardSize, random: Random = Random.Default) : this(size, random, true)

    private class Snapshot(
        val tiles: List<Tile>,
        val score: Int,
    )

    private var board: List<Tile> = emptyList()
    private var nextId = 0
    private var undoState: Snapshot? = null

    val tiles: List<Tile> get() = board

    var score: Int = 0
        private set

    /** A tile of at least [BoardSize.goal] is on the board (undoing the move that made it clears this). */
    val goalReached: Boolean get() = board.any { it.value >= size.goal }

    val isOver: Boolean
        get() {
            if (board.size < size.side * size.side) return false
            val values = board.associate { it.cell to it.value }
            return board.none { t ->
                values[Cell(t.cell.row + 1, t.cell.col)] == t.value ||
                    values[Cell(t.cell.row, t.cell.col + 1)] == t.value
            }
        }

    val canUndo: Boolean get() = undoState != null

    init {
        if (placeStartTiles) repeat(2) { spawn()?.let { board = board + it } }
    }

    /** Slides and merges all tiles; null when nothing changed (no spawn, no score, undo untouched). */
    fun move(direction: Direction): MoveResult? {
        val grid = Array(size.side) { arrayOfNulls<Tile>(size.side) }
        board.forEach { grid[it.cell.row][it.cell.col] = it }
        val slides = mutableListOf<Slide>()
        val merged = mutableListOf<Tile>()
        val result = mutableListOf<Tile>()
        var gained = 0
        var changed = false
        for (line in 0 until size.side) {
            val cells = List(size.side) { cellAt(direction, line, it) }
            val source = cells.mapNotNull { c -> grid[c.row][c.col] }
            var target = 0
            var i = 0
            while (i < source.size) {
                val tile = source[i]
                val partner = source.getOrNull(i + 1)?.takeIf { it.value == tile.value }
                val to = cells[target++]
                if (partner != null) {
                    val created = Tile(nextId++, tile.value * 2, to)
                    slides += Slide(tile.id, tile.value, tile.cell, to)
                    slides += Slide(partner.id, partner.value, partner.cell, to)
                    merged += created
                    result += created
                    gained += created.value
                    changed = true
                    i += 2
                } else {
                    if (tile.cell != to) {
                        slides += Slide(tile.id, tile.value, tile.cell, to)
                        changed = true
                    }
                    result += tile.copy(cell = to)
                    i++
                }
            }
        }
        if (!changed) return null
        undoState = Snapshot(board, score)
        board = result
        score += gained
        val spawned = spawn()?.also { board = board + it }
        return MoveResult(slides, merged, spawned, gained)
    }

    /** Takes back the last move; false when there is nothing to undo. */
    fun undo(): Boolean {
        val state = undoState ?: return false
        board = state.tiles
        score = state.score
        undoState = null
        return true
    }

    /** The [index]th cell of line [line], counted from the edge the tiles slide towards. */
    private fun cellAt(
        direction: Direction,
        line: Int,
        index: Int,
    ): Cell {
        val last = size.side - 1
        return when (direction) {
            Direction.LEFT -> Cell(line, index)
            Direction.RIGHT -> Cell(line, last - index)
            Direction.UP -> Cell(index, line)
            Direction.DOWN -> Cell(last - index, line)
        }
    }

    private fun spawn(): Tile? {
        val taken = board.mapTo(HashSet()) { it.cell }
        val free =
            (0 until size.side).flatMap { r -> (0 until size.side).map { c -> Cell(r, c) } }.filter { it !in taken }
        if (free.isEmpty()) return null
        val cell = free[random.nextInt(free.size)]
        return Tile(nextId++, if (random.nextDouble() < SPAWN_TWO) 2 else 4, cell)
    }

    companion object {
        private const val SPAWN_TWO = 0.9

        /** For tests: rows top to bottom, 0 = empty; ids assigned in reading order. */
        internal fun of(
            size: BoardSize,
            rows: List<List<Int>>,
            random: Random = Random.Default,
        ): DoublesGame {
            require(rows.size == size.side && rows.all { it.size == size.side }) { "rows do not match $size" }
            val game = DoublesGame(size, random, false)
            var id = 0
            game.board =
                rows.flatMapIndexed { r, row ->
                    row.mapIndexedNotNull { c, v -> if (v == 0) null else Tile(id++, v, Cell(r, c)) }
                }
            game.nextId = id
            return game
        }
    }
}
