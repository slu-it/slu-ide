package dev.sluide.games.minesweeper

import kotlin.random.Random

enum class GameState { NOT_STARTED, RUNNING, WON, LOST }

enum class CellKind { CLOSED, FLAGGED, OPENED, MINE, HIT_MINE, WRONG_FLAG }

/**
 * The rules of Minesweeper on a [size] board; x is the column (0 until width), y the row (0 until height), anything
 * else throws [IllegalArgumentException]. Mines are placed by the first [open] (never on that cell or its neighbours),
 * so the first click always opens an area. After [GameState.WON] or [GameState.LOST] every action returns false.
 */
class MinesweeperGame private constructor(
    val size: BoardSize,
    private val random: Random?,
    fixedMines: Set<Pair<Int, Int>>?,
) {
    constructor(size: BoardSize, random: Random = Random.Default) : this(size, random, null)

    /** Fixed mines (x, y) for tests; no placement. */
    internal constructor(size: BoardSize, mines: Set<Pair<Int, Int>>) : this(size, null, mines)

    private val width = size.width
    private val height = size.height
    private val mine = BooleanArray(width * height)
    private val opened = BooleanArray(width * height)
    private val flagged = BooleanArray(width * height)
    private val hit = BooleanArray(width * height)
    private var openedCount = 0
    private var flagCount = 0

    var state: GameState = GameState.NOT_STARTED
        private set

    init {
        if (fixedMines != null) {
            require(fixedMines.size == size.mines) { "expected ${size.mines} mines, got ${fixedMines.size}" }
            fixedMines.forEach { (x, y) -> mine[index(x, y)] = true }
        }
    }

    /** Mines minus flags; negative with too many flags. */
    val minesLeft: Int get() = size.mines - flagCount

    fun cell(
        x: Int,
        y: Int,
    ): CellKind {
        val i = index(x, y)
        return when (state) {
            GameState.WON -> {
                if (mine[i]) CellKind.FLAGGED else CellKind.OPENED
            }

            GameState.LOST -> {
                when {
                    mine[i] -> {
                        if (hit[i]) {
                            CellKind.HIT_MINE
                        } else if (flagged[i]) {
                            CellKind.FLAGGED
                        } else {
                            CellKind.MINE
                        }
                    }

                    flagged[i] -> {
                        CellKind.WRONG_FLAG
                    }

                    opened[i] -> {
                        CellKind.OPENED
                    }

                    else -> {
                        CellKind.CLOSED
                    }
                }
            }

            else -> {
                if (opened[i]) {
                    CellKind.OPENED
                } else if (flagged[i]) {
                    CellKind.FLAGGED
                } else {
                    CellKind.CLOSED
                }
            }
        }
    }

    /** The mines around the cell; meaningful for opened cells. */
    fun adjacentMines(
        x: Int,
        y: Int,
    ): Int {
        index(x, y)
        return neighbours(x, y).count { mine[it] }
    }

    /** Opens a closed, unflagged cell; true if anything changed. */
    fun open(
        x: Int,
        y: Int,
    ): Boolean {
        val i = index(x, y)
        if (isOver() || opened[i] || flagged[i]) return false
        if (state == GameState.NOT_STARTED) {
            if (random != null) placeMines(x, y, random)
            state = GameState.RUNNING
        }
        val lost = openCell(i)
        finish(lost)
        return true
    }

    fun toggleFlag(
        x: Int,
        y: Int,
    ): Boolean {
        val i = index(x, y)
        if (isOver() || opened[i]) return false
        flagged[i] = !flagged[i]
        flagCount += if (flagged[i]) 1 else -1
        return true
    }

    /** On an opened number with as many flags around as mines: opens the other neighbours (a wrong flag loses). */
    fun chord(
        x: Int,
        y: Int,
    ): Boolean {
        val i = index(x, y)
        if (isOver() || !opened[i]) return false
        val around = neighbours(x, y)
        val count = around.count { mine[it] }
        if (count == 0 || around.count { flagged[it] } != count) return false
        var changed = false
        var lost = false
        for (n in around) {
            if (!opened[n] && !flagged[n]) {
                changed = true
                if (openCell(n)) lost = true
            }
        }
        if (changed) finish(lost)
        return changed
    }

    /** The primary click: opens a closed cell, chords an opened one. */
    fun reveal(
        x: Int,
        y: Int,
    ): Boolean = if (cell(x, y) == CellKind.OPENED) chord(x, y) else open(x, y)

    private fun isOver() = state == GameState.WON || state == GameState.LOST

    private fun index(
        x: Int,
        y: Int,
    ): Int {
        require(x in 0 until width && y in 0 until height) { "($x, $y) outside the $width×$height board" }
        return y * width + x
    }

    private fun neighbours(
        x: Int,
        y: Int,
    ): List<Int> =
        buildList {
            for (ny in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(height - 1)) {
                for (nx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(width - 1)) {
                    if (nx != x || ny != y) add(ny * width + nx)
                }
            }
        }

    private fun placeMines(
        x: Int,
        y: Int,
        random: Random,
    ) {
        val free = (neighbours(x, y) + index(x, y)).toSet()
        (0 until width * height)
            .filter { it !in free }
            .shuffled(random)
            .take(size.mines)
            .forEach { mine[it] = true }
    }

    /** Opens the cell (flooding from a zero); true if it is a mine. Flagged and opened cells are left alone. */
    private fun openCell(start: Int): Boolean {
        if (mine[start]) {
            hit[start] = true
            return true
        }
        val queue = ArrayDeque<Int>()
        opened[start] = true
        openedCount++
        queue.add(start)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % width
            val y = i / width
            if (neighbours(x, y).any { mine[it] }) continue
            for (n in neighbours(x, y)) {
                if (!opened[n] && !flagged[n]) {
                    opened[n] = true
                    openedCount++
                    queue.add(n)
                }
            }
        }
        return false
    }

    private fun finish(lost: Boolean) {
        if (lost) {
            state = GameState.LOST
        } else if (openedCount == width * height - size.mines) {
            state = GameState.WON
            flagCount = size.mines
        }
    }
}
