package dev.sluide.httpclient

/**
 * The one line a view shows of its [problems] and the tooltip with all of them: the text of the only problem, or
 * the first one and how many more there are. Without problems the text is empty and the tooltip `null`.
 */
internal fun problemSummary(problems: List<String>): Pair<String, String?> =
    when (problems.size) {
        0 -> "" to null
        1 -> problems.single() to problems.single()
        else -> "${problems.first()} (and ${problems.size - 1} more)" to problems.joinToString("\n")
    }
