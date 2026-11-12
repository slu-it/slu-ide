package dev.sluide.codereview.find

import dev.sluide.codereview.model.ReviewLineSource
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSide

/** At most this many matches are found and counted ("10000+"). */
internal const val MAX_MATCHES = 10_000

/** One occurrence of the query: in row [row] of the document, on [side], in the columns [columns] (inclusive). */
internal data class FindMatch(
    val row: Int,
    val side: ReviewSide,
    val columns: IntRange,
)

/** The [matches] in document order; [truncated] when there are more than [MAX_MATCHES]. */
internal class FindResult(
    val matches: List<FindMatch>,
    val truncated: Boolean,
)

/**
 * Finds [query] literally in the lines of [rows] (what the list shows: hidden context and the lines of collapsed,
 * Viewed and not yet loaded files are not searched), ignoring case unless [matchCase]. A unified row searches its one
 * line (the current one, else the original), a split row the original half and then the current one; the other rows
 * have no lines. An occurrence overlapping the previous one is not counted. An empty query finds nothing.
 */
internal fun searchRows(
    rows: List<ReviewRow>,
    lines: ReviewLineSource,
    query: String,
    matchCase: Boolean,
): FindResult {
    if (query.isEmpty()) return FindResult(emptyList(), false)
    val found = ArrayList<FindMatch>()
    for ((index, row) in rows.withIndex()) {
        for ((side, line) in linesOf(row)) {
            val text = lines.line(row.path, side, line).text
            var from = 0
            while (true) {
                val at = text.indexOf(query, from, ignoreCase = !matchCase)
                if (at < 0) break
                if (found.size == MAX_MATCHES) return FindResult(found, truncated = true)
                found += FindMatch(index, side, at until at + query.length)
                from = at + query.length
            }
        }
    }
    return FindResult(found, truncated = false)
}

/** The lines a row searches, in order. */
private fun linesOf(row: ReviewRow): List<Pair<ReviewSide, Int>> =
    when (row) {
        is ReviewRow.UnifiedLine -> {
            listOfNotNull(
                row.current?.let { ReviewSide.CURRENT to it } ?: row.original?.let { ReviewSide.ORIGINAL to it },
            )
        }

        is ReviewRow.SplitLine -> {
            listOfNotNull(
                row.original?.let { ReviewSide.ORIGINAL to it },
                row.current?.let { ReviewSide.CURRENT to it },
            )
        }

        else -> {
            emptyList()
        }
    }
