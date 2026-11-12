package dev.sluide.codereview.model

import javafx.collections.ObservableListBase

/**
 * The document's rows: read-only for its users, replaced range by range with one change event each, so that a list
 * view keeps its viewport when one file's rows change.
 */
internal class RowList : ObservableListBase<ReviewRow>() {
    private val rows = ArrayList<ReviewRow>()

    override fun get(index: Int): ReviewRow = rows[index]

    override val size: Int
        get() = rows.size

    /** Replaces the rows `[from, to)` with [replacement] in one change. */
    fun replace(
        from: Int,
        to: Int,
        replacement: List<ReviewRow>,
    ) {
        if (from == to && replacement.isEmpty()) return
        val range = rows.subList(from, to)
        val removed = ArrayList(range)
        range.clear()
        rows.addAll(from, replacement)
        beginChange()
        if (removed.isNotEmpty()) nextRemove(from, removed)
        if (replacement.isNotEmpty()) nextAdd(from, from + replacement.size)
        endChange()
    }

    /** Replaces all rows with [replacement] in one change. */
    fun replaceAll(replacement: List<ReviewRow>) = replace(0, rows.size, replacement)
}
