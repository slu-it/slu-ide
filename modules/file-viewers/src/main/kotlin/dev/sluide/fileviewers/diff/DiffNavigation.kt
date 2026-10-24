package dev.sluide.fileviewers.diff

import dev.sluide.fileviewers.DiffRow

/** The row ranges of the changes in [rows]: each run of consecutive changed rows is one change. */
internal fun changeBlocks(rows: List<DiffRow>): List<IntRange> {
    val blocks = mutableListOf<IntRange>()
    var start = -1
    rows.forEachIndexed { index, row ->
        if (row.kind != null && start < 0) start = index
        if (row.kind == null && start >= 0) {
            blocks += start until index
            start = -1
        }
    }
    if (start >= 0) blocks += start until rows.size
    return blocks
}

/** The row of [rows] showing [line] of the file (0-based), or the end for lines beyond them. */
internal fun rowOfCurrent(
    rows: List<DiffRow>,
    line: Int,
): Int = rows.indexOfFirst { it.current == line }.takeIf { it >= 0 } ?: rows.size

/** Jumps between the changes [blocks] (sorted row ranges) of a diff, seen from a row; `null` where there is none. */
internal class DiffNavigation(
    val blocks: List<IntRange>,
) {
    fun first(): IntRange? = blocks.firstOrNull()

    fun last(): IntRange? = blocks.lastOrNull()

    /** The first change that starts after [row]. */
    fun next(row: Int): IntRange? = blocks.firstOrNull { it.first > row }

    /** The last change that ends before [row]; from within a change, the one before it. */
    fun previous(row: Int): IntRange? = blocks.lastOrNull { it.last < row }

    /**
     * The change a marker on [row] stands for: the one [row] is in, else the one right above it (lines removed
     * there), else the next one, else the last one.
     */
    fun at(row: Int): IntRange? =
        blocks.firstOrNull { row in it } ?: blocks.firstOrNull { it.last == row - 1 } ?: next(row) ?: last()

    /** The index of the change [row] is in. */
    fun indexAt(row: Int): Int? = blocks.indexOfFirst { row in it }.takeIf { it >= 0 }

    /** Whether there is a change to go to, for the first and last buttons. */
    val hasChanges: Boolean get() = blocks.isNotEmpty()

    /** What the toolbar's counter says seen from [row]: the number of the change [row] is in, or how many there are. */
    fun counter(row: Int): String {
        val index = indexAt(row)
        return when {
            blocks.isEmpty() -> "No changes"
            index != null -> "Change ${index + 1} of ${blocks.size}"
            blocks.size == 1 -> "1 change"
            else -> "${blocks.size} changes"
        }
    }
}
