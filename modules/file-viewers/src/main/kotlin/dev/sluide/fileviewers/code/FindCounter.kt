package dev.sluide.fileviewers.code

/**
 * What the counter of the [FindBar] says: the [error] of the [query], nothing for an empty query, "No results", the
 * number of the [current] match of [count] (with a `+` when the search was [truncated]) or only the number of results.
 */
internal fun counterText(
    count: Int,
    truncated: Boolean,
    current: Int?,
    error: String?,
    query: String,
): String {
    val total = if (truncated) "$count+" else "$count"
    return when {
        error != null -> error
        query.isEmpty() -> ""
        count == 0 -> "No results"
        current != null -> "${current + 1} of $total"
        count == 1 && !truncated -> "1 result"
        else -> "$total results"
    }
}

/** Whether the counter is shown as an error: the query is invalid, or it matches nothing. */
internal fun counterIsError(
    count: Int,
    error: String?,
    query: String,
): Boolean = error != null || (count == 0 && query.isNotEmpty())

/** What the counter says after [count] replacements. */
internal fun replacedText(count: Int): String = if (count == 1) "1 replaced" else "$count replaced"
