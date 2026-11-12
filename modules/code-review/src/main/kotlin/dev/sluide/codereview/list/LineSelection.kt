package dev.sluide.codereview.list

import dev.sluide.codereview.model.ReviewAnchor
import dev.sluide.codereview.model.ReviewLineSource
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSide
import dev.sluide.codereview.model.currentLine
import dev.sluide.codereview.model.originalLine

/**
 * The line of [row] on [side] as an anchor: `null` for rows without lines and for a filler half. [side] `null`
 * ("per row", the unified view) takes the row's current line, for a removed-only row its original one.
 */
internal fun lineAnchor(
    row: ReviewRow,
    side: ReviewSide?,
): ReviewAnchor? {
    val current = row.currentLine()?.let { ReviewAnchor(row.path, ReviewSide.CURRENT, it) }
    val original = row.originalLine()?.let { ReviewAnchor(row.path, ReviewSide.ORIGINAL, it) }
    return when (side) {
        ReviewSide.CURRENT -> current
        ReviewSide.ORIGINAL -> original
        null -> current ?: original
    }
}

/** The lines of [row] that count for [side]: for `null` both of them, since a row of the other layout may pair them. */
private fun anchorsOf(
    row: ReviewRow,
    side: ReviewSide?,
): List<ReviewAnchor> =
    if (side == null) {
        listOfNotNull(lineAnchor(row, ReviewSide.CURRENT), lineAnchor(row, ReviewSide.ORIGINAL))
    } else {
        listOfNotNull(lineAnchor(row, side))
    }

/**
 * The selected lines of one file: a contiguous range of its rows whose rows have a line on [side] (`null`: per row,
 * see [lineAnchor]; a unified selection may mix sides). The lines are kept as anchors, so the selection follows them
 * through changes of the rows (expand, filter, layout toggle) and [rows] is empty once they are gone.
 */
internal class LineSelection private constructor(
    val path: String,
    val side: ReviewSide?,
    private val pivot: ReviewAnchor,
    private val lines: Set<ReviewAnchor>,
) {
    /** The indices of the selected rows in [rows], in order. */
    fun rows(rows: List<ReviewRow>): List<Int> =
        rows.indices.filter { i -> rows[i].path == path && anchorsOf(rows[i], side).any { it in lines } }

    /**
     * Shift+click on row [index] ([clickedSide]: the half of a split row, `null` in the unified view): the range from
     * the first clicked line to it, or a new selection when it is in another file or on the other side.
     */
    fun extend(
        rows: List<ReviewRow>,
        index: Int,
        clickedSide: ReviewSide?,
    ): LineSelection? {
        val target = rows.getOrNull(index) ?: return null
        if (target.path != path || clickedSide != side) return at(rows, index, clickedSide)
        val from = rows.indices.firstOrNull { rows[it].path == path && pivot in anchorsOf(rows[it], side) }
        if (from == null || lineAnchor(target, side) == null) return at(rows, index, clickedSide)
        val range = minOf(from, index)..maxOf(from, index)
        val anchors = range.filter { rows[it].path == path }.mapNotNull { lineAnchor(rows[it], side) }
        return LineSelection(path, side, pivot, LinkedHashSet(anchors))
    }

    /** The sides of [row] whose line is selected: in the split view the halves to paint, empty for other rows. */
    fun sidesOf(row: ReviewRow): List<ReviewSide> =
        if (row.path != path) emptyList() else anchorsOf(row, side).filter { it in lines }.mapNotNull { it.side }

    /**
     * The texts of the selected lines of [rows] joined by newlines: no signs, no numbers, in the order they were
     * selected (a line that is not shown any more is left out).
     */
    fun text(
        rows: List<ReviewRow>,
        source: ReviewLineSource,
    ): String {
        val shown = rows.filter { it.path == path }.flatMap { anchorsOf(it, side) }.toSet()
        return lines.filter { it in shown }.joinToString("\n") { anchor ->
            val line = anchor.line
            val anchorSide = anchor.side
            if (line == null || anchorSide == null) "" else source.line(path, anchorSide, line).text
        }
    }

    companion object {
        /** A click on row [index] ([clickedSide] as in [extend]); `null` when the row has no line there. */
        fun at(
            rows: List<ReviewRow>,
            index: Int,
            clickedSide: ReviewSide?,
        ): LineSelection? {
            val row = rows.getOrNull(index) ?: return null
            val anchor = lineAnchor(row, clickedSide) ?: return null
            return LineSelection(row.path, clickedSide, anchor, setOf(anchor))
        }
    }
}

/**
 * The line of the working tree to open for row [index]: its own current line, else the current line of the next row
 * of the file that has one, else of the previous one; `null` without any.
 */
internal fun nearestCurrentLine(
    rows: List<ReviewRow>,
    index: Int,
): Int? {
    val path = rows.getOrNull(index)?.path ?: return null
    rows[index].currentLine()?.let { return it }
    var i = index + 1
    while (i < rows.size && rows[i].path == path) {
        rows[i].currentLine()?.let { return it }
        i++
    }
    i = index - 1
    while (i >= 0 && rows[i].path == path) {
        rows[i].currentLine()?.let { return it }
        i--
    }
    return null
}

private fun isChangedLine(row: ReviewRow?): Boolean =
    when (row) {
        is ReviewRow.UnifiedLine -> row.kind != null
        is ReviewRow.SplitLine -> row.kind != null
        else -> false
    }

/** The first row of a block of changed lines below ([forward]) or above row [from] (exclusive); `null` without. */
internal fun nextChange(
    rows: List<ReviewRow>,
    from: Int,
    forward: Boolean,
): Int? {
    val starts = { i: Int -> isChangedLine(rows[i]) && !(i > 0 && isChangedLine(rows[i - 1])) }
    return if (forward) {
        ((from + 1).coerceAtLeast(0) until rows.size).firstOrNull(starts)
    } else {
        (minOf(from, rows.size) - 1 downTo 0).firstOrNull(starts)
    }
}

/** The header row of the next ([forward]) or previous file relative to row [from] (exclusive); `null` without. */
internal fun nextFileHeader(
    rows: List<ReviewRow>,
    from: Int,
    forward: Boolean,
): Int? {
    val header = { i: Int -> rows[i] is ReviewRow.FileHeader }
    return if (forward) {
        ((from + 1).coerceAtLeast(0) until rows.size).firstOrNull(header)
    } else {
        (minOf(from, rows.size) - 1 downTo 0).firstOrNull(header)
    }
}
