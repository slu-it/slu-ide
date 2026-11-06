package dev.sluide.games.sudoku

/** What a step changed: the units it completed (full, no conflict) and whether it solved the puzzle. */
data class StepResult(
    val completedUnits: List<List<Cell>>,
    val solved: Boolean,
)

sealed interface HintResult {
    /** A wrong entry [wrongDigit] was removed from [cell]. */
    data class Fixed(
        val cell: Cell,
        val wrongDigit: Int,
    ) : HintResult

    /** [cell] was filled with [digit]. */
    data class Filled(
        val cell: Cell,
        val digit: Int,
        val step: StepResult,
    ) : HintResult
}

private val NOTHING = StepResult(emptyList(), false)

/**
 * The state of one puzzle: the player's entries and notes on top of the givens, with unlimited undo and redo.
 * Pure, not thread-safe (used on the FX thread). Mistakes are only conflicts; the solution is compared only by [hint].
 */
class SudokuGame(
    val puzzle: Puzzle,
) {
    val size: SudokuSize = puzzle.size
    private val side = size.side

    private class Snapshot(
        val entries: IntArray,
        val notes: IntArray,
    )

    private var entries = IntArray(side * side)
    private var notes = IntArray(side * side)
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var cachedConflicts: Set<Cell>? = null

    var hints = 0
        private set

    private fun index(cell: Cell) = cell.row * side + cell.col

    fun isGiven(cell: Cell): Boolean = puzzle.givens[cell] != 0

    /** The given or the entry; 0 when empty. */
    fun digit(cell: Cell): Int = if (isGiven(cell)) puzzle.givens[cell] else entries[index(cell)]

    fun notes(cell: Cell): Set<Int> {
        val mask = notes[index(cell)]
        return (1..side).filter { mask and (1 shl it) != 0 }.toSet()
    }

    /** Every cell, givens too, whose digit occurs again in one of its units. */
    val conflicts: Set<Cell>
        get() = cachedConflicts ?: computeConflicts().also { cachedConflicts = it }

    /** How many of [digit] are still missing: the side minus the cells holding it, at least 0. */
    fun remaining(digit: Int): Int = (side - size.cells.count { digit(it) == digit }).coerceAtLeast(0)

    val isSolved: Boolean get() = size.cells.all { digit(it) != 0 } && conflicts.isEmpty()

    val hasEntries: Boolean get() = entries.any { it != 0 } || notes.any { it != 0 }

    val canUndo: Boolean get() = undoStack.isNotEmpty() && !isSolved

    val canRedo: Boolean get() = redoStack.isNotEmpty() && !isSolved

    fun place(
        cell: Cell,
        digit: Int,
    ): StepResult? {
        if (isGiven(cell) || digit !in 1..side || entries[index(cell)] == digit || isSolved) return null
        return step { fill(cell, digit) }
    }

    fun erase(cell: Cell): StepResult? {
        if (isGiven(cell) || isSolved) return null
        val i = index(cell)
        if (entries[i] == 0 && notes[i] == 0) return null
        return step { if (entries[i] != 0) entries[i] = 0 else notes[i] = 0 }
    }

    fun toggleNote(
        cell: Cell,
        digit: Int,
    ): StepResult? {
        if (isGiven(cell) || digit(cell) != 0 || digit !in 1..side || isSolved) return null
        return step { notes[index(cell)] = notes[index(cell)] xor (1 shl digit) }
    }

    /** Every empty cell's notes become its candidates, judged from givens and entries. Null when nothing changes. */
    fun fillNotes(): StepResult? {
        if (isSolved) return null
        val grid = currentGrid()
        val target = IntArray(side * side)
        for (cell in size.cells) {
            if (digit(cell) == 0) target[index(cell)] = grid.candidates(cell).fold(0) { m, d -> m or (1 shl d) }
        }
        if (target.contentEquals(notes)) return null
        return step { target.copyInto(notes) }
    }

    /**
     * Removes one wrong entry (the [selected] cell's, else the first one), else fills [selected] when it is empty, else
     * the cell of the easiest deduction. Costs a hint in every case; null when solved.
     */
    fun hint(selected: Cell?): HintResult? {
        if (isSolved) return null
        val wrong = size.cells.filter { entries[index(it)] != 0 && entries[index(it)] != puzzle.solution[it] }
        val result =
            if (wrong.isNotEmpty()) {
                val cell = selected?.takeIf { it in wrong } ?: wrong.first()
                val digit = entries[index(cell)]
                step { entries[index(cell)] = 0 }
                HintResult.Fixed(cell, digit)
            } else {
                val cell =
                    selected?.takeIf { digit(it) == 0 }
                        ?: Grader.nextDeduction(currentGrid())?.cell
                        ?: size.cells.first { digit(it) == 0 }
                val digit = puzzle.solution[cell]
                HintResult.Filled(cell, digit, step { fill(cell, digit) })
            }
        hints++
        return result
    }

    fun undo(): Boolean {
        if (!canUndo) return false
        redoStack.addLast(snapshot())
        restore(undoStack.removeLast())
        return true
    }

    fun redo(): Boolean {
        if (!canRedo) return false
        undoStack.addLast(snapshot())
        restore(redoStack.removeLast())
        return true
    }

    /** Entries and notes back to the givens, no history; the hints stay. */
    fun restart() {
        entries = IntArray(side * side)
        notes = IntArray(side * side)
        undoStack.clear()
        redoStack.clear()
        cachedConflicts = null
    }

    private fun snapshot() = Snapshot(entries.copyOf(), notes.copyOf())

    private fun restore(snapshot: Snapshot) {
        entries = snapshot.entries
        notes = snapshot.notes
        cachedConflicts = null
    }

    /** One undo step: records the state, clears redo, runs [change] and reports completed units. */
    private fun step(change: () -> Unit): StepResult {
        val before = cleanUnits()
        undoStack.addLast(snapshot())
        redoStack.clear()
        change()
        cachedConflicts = null
        val completed = (cleanUnits() - before).sorted().map { size.units[it] }
        return StepResult(completed, isSolved)
    }

    private fun fill(
        cell: Cell,
        digit: Int,
    ) {
        entries[index(cell)] = digit
        notes[index(cell)] = 0
        size.peers(cell).forEach { notes[index(it)] = notes[index(it)] and (1 shl digit).inv() }
    }

    /** Indexes (into [SudokuSize.units]) of the units that are full and hold no conflicting cell. */
    private fun cleanUnits(): Set<Int> {
        val conflicting = conflicts
        return size.units.indices
            .filter { u -> size.units[u].all { digit(it) != 0 && it !in conflicting } }
            .toSet()
    }

    private fun currentGrid(): Grid =
        size.cells.fold(puzzle.givens) { grid, cell ->
            if (isGiven(cell)) grid else grid.with(cell, entries[index(cell)])
        }

    private fun computeConflicts(): Set<Cell> =
        size.units
            .flatMap { unit ->
                unit
                    .filter { digit(it) != 0 }
                    .groupBy { digit(it) }
                    .values
                    .filter { it.size > 1 }
                    .flatten()
            }.toSet()
}
