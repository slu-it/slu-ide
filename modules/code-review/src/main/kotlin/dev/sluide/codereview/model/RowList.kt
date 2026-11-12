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

    /**
     * Replaces the rows `[from, to)` with [replacement] in one change that covers only what differs: the rows both
     * share at the start and the end are kept (no change at all when nothing differs). A list view that is scrolled
     * into the replaced range loses its place when the whole range is reported, so the change must stay minimal.
     */
    fun replace(
        from: Int,
        to: Int,
        replacement: List<ReviewRow>,
    ) {
        val old = rows.subList(from, to)
        var prefix = 0
        val shared = minOf(old.size, replacement.size)
        while (prefix < shared && old[prefix] == replacement[prefix]) prefix++
        var suffix = 0
        while (suffix < shared - prefix &&
            old[old.size - 1 - suffix] == replacement[replacement.size - 1 - suffix]
        ) {
            suffix++
        }
        val start = from + prefix
        val removed = ArrayList(old.subList(prefix, old.size - suffix))
        val added = replacement.subList(prefix, replacement.size - suffix)
        if (removed.isEmpty() && added.isEmpty()) return
        rows.subList(start, start + removed.size).clear()
        rows.addAll(start, added)
        beginChange()
        if (removed.isNotEmpty()) nextRemove(start, removed)
        if (added.isNotEmpty()) nextAdd(start, start + added.size)
        endChange()
    }

    /** Replaces all rows with [replacement] in one change. */
    fun replaceAll(replacement: List<ReviewRow>) = replace(0, rows.size, replacement)
}
