package dev.sluide.codereview.find

import dev.sluide.codereview.model.ReviewAnchor
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSide

/**
 * The state of a find in the review: the matches of [query] in the document's rows and the current one. Free of
 * controls; the find bar drives it and shows it.
 */
internal class ReviewFind(
    private val document: ReviewDocument,
) {
    var query = ""
    var matchCase = false

    var matches: List<FindMatch> = emptyList()
        private set

    var truncated = false
        private set

    /** The index of the current match in [matches]. */
    var current: Int? = null
        private set

    /** Where the current match is, so that it can be found again after the rows changed. */
    private var place: Place? = null

    private class Place(
        val anchor: ReviewAnchor,
        val match: FindMatch,
        /** The paths of the files in the order they had, for the next match after a file that is gone. */
        val order: List<String>,
    )

    /** Searches again; the current match is the first at or after row [fromRow], else the first. */
    fun search(fromRow: Int) {
        find()
        select(firstFrom(fromRow))
    }

    /**
     * Searches again after the rows changed. The current match stays where it is by its side, line and column. If it
     * is gone, the next one after it takes its place: the first at or after the row of its line (a collapsed file:
     * of its header), or, when its file is gone, the first of a later file. Without a current match before, the first
     * at or after row [fromRow] becomes current.
     */
    fun rowsChanged(fromRow: Int) {
        val old = place
        find()
        if (old == null) return select(firstFrom(fromRow))
        val row = document.rowOf(old.anchor)
        if (row < 0) return select(firstAfterFile(old))
        val same =
            matches.indexOfFirst {
                it.row == row && it.side == old.match.side && it.columns.first == old.match.columns.first
            }
        select(same.takeIf { it >= 0 } ?: firstFrom(row))
    }

    private fun firstFrom(row: Int) = matches.indexOfFirst { it.row >= row }.takeIf { it >= 0 } ?: 0

    private fun firstAfterFile(old: Place): Int {
        val oldIndex = old.order.indexOf(old.anchor.path)
        return matches
            .indexOfFirst { old.order.indexOf(document.rows[it.row].path) > oldIndex }
            .takeIf { it >= 0 } ?: 0
    }

    /** Makes the next match the current one, the first after the last; `null` without matches. */
    fun next(): FindMatch? = move(1)

    /** Makes the previous match the current one, the last before the first; `null` without matches. */
    fun previous(): FindMatch? = move(-1)

    private fun move(step: Int): FindMatch? {
        if (matches.isEmpty()) return null
        val index = ((current ?: if (step > 0) -1 else 0) + step).mod(matches.size)
        select(index)
        return matches[index]
    }

    private fun find() {
        val result = searchRows(document.rows, document, query, matchCase)
        matches = result.matches
        truncated = result.truncated
    }

    private fun select(index: Int) {
        val match = matches.getOrNull(index)
        current = index.takeIf { match != null }
        place = match?.let { Place(anchorOf(it), it, document.files.map { file -> file.path }) }
    }

    /** The line of the match's own side, so that the match is found again on that side. */
    private fun anchorOf(match: FindMatch): ReviewAnchor {
        val row = document.rows[match.row]
        val line =
            when (row) {
                is ReviewRow.UnifiedLine -> if (match.side == ReviewSide.CURRENT) row.current else row.original
                is ReviewRow.SplitLine -> if (match.side == ReviewSide.CURRENT) row.current else row.original
                else -> null
            }
        return ReviewAnchor(row.path, match.side, line)
    }

    /** The counter: "3 of 17", "0 of 0" without matches, "10000+" as the total at the cap, empty without a query. */
    fun counterText(): String {
        if (query.isEmpty()) return ""
        val total = if (truncated) "${matches.size}+" else "${matches.size}"
        return "${current?.plus(1) ?: 0} of $total"
    }
}
