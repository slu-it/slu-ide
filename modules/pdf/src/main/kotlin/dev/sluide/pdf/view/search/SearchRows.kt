package dev.sluide.pdf.view.search

/** A row of the result list: a page heading, or a hit under it. */
internal sealed interface SearchRow {
    /** "Page N" for [page] (0-based). */
    data class Heading(
        val page: Int,
    ) : SearchRow {
        val title: String get() = "Page ${page + 1}"
    }

    data class Hit(
        val hit: SearchHit,
    ) : SearchRow
}

/** The rows for [result]: a heading whenever the page changes, then its hits. */
internal fun searchRows(result: SearchResult): List<SearchRow> {
    val rows = ArrayList<SearchRow>()
    var page = -1
    for (hit in result.hits) {
        if (hit.page != page) {
            page = hit.page
            rows += SearchRow.Heading(page)
        }
        rows += SearchRow.Hit(hit)
    }
    return rows
}

/** The status line for [result]. */
internal fun searchStatus(result: SearchResult): String {
    val count = result.hits.size
    return when {
        result.noText -> "This PDF has no text."
        count == 0 -> "No matches"
        result.capped -> "$count+ matches"
        count == 1 -> "1 match"
        else -> "$count matches"
    }
}

/** The status line while the page texts are collected: [arrived] of [pageCount] are there. */
internal fun collectingStatus(
    arrived: Int,
    pageCount: Int,
): String = "Searching… page $arrived of $pageCount"

/** The status line while the texts are matched. */
internal const val SEARCHING = "Searching…"
