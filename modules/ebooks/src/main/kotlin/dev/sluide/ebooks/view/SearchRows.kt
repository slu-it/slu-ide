package dev.sluide.ebooks.view

/** A row of the result list: a chapter heading, or a hit under it. */
internal sealed interface SearchRow {
    data class Heading(
        val title: String,
    ) : SearchRow

    data class Hit(
        val match: SearchMatch,
    ) : SearchRow
}

/** The rows for [result]: a heading whenever the chapter changes, then its hits. */
internal fun searchRows(result: SearchResult): List<SearchRow> {
    val rows = ArrayList<SearchRow>()
    var chapter = -1
    for (match in result.matches) {
        if (match.chapterIndex != chapter) {
            chapter = match.chapterIndex
            rows += SearchRow.Heading(match.chapterTitle)
        }
        rows += SearchRow.Hit(match)
    }
    return rows
}

/** The status line for [result]. */
internal fun searchStatus(result: SearchResult): String {
    val count = result.matches.size
    return when {
        count == 0 -> "No matches"
        result.capped -> "$count+ matches"
        count == 1 -> "1 match"
        else -> "$count matches"
    }
}
