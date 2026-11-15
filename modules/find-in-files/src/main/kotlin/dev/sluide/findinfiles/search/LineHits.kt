package dev.sluide.findinfiles.search

/** About how long a snippet gets at most (without its "…"). */
internal const val SNIPPET_LENGTH = 200

/** How many characters a cut snippet shows before the first match of its line. */
internal const val SNIPPET_CONTEXT = 40

private const val ELLIPSIS = "…"

/**
 * [matches] of [text] (`\n` line breaks, in order, not overlapping) grouped by the line they start on. A match running
 * over the end of its line is highlighted to that end.
 */
internal fun lineHits(
    text: String,
    matches: List<IntRange>,
): List<LineHit> {
    val hits = ArrayList<LineHit>()
    var line = 0
    var lineStart = 0
    var index = 0
    while (index < matches.size) {
        val first = matches[index]
        while (true) {
            val nextBreak = text.indexOf('\n', lineStart)
            if (nextBreak < 0 || nextBreak >= first.first) break
            lineStart = nextBreak + 1
            line++
        }
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        var end = index
        // A match starting at the line break belongs to this line too: `<= lineEnd`.
        while (end < matches.size && matches[end].first <= lineEnd) end++
        hits += lineHit(text, line, lineStart, lineEnd, matches.subList(index, end))
        index = end
    }
    return hits
}

/** The [LineHit] of the line [lineStart] until [lineEnd], numbered [line], with its [matches]. */
private fun lineHit(
    text: String,
    line: Int,
    lineStart: Int,
    lineEnd: Int,
    matches: List<IntRange>,
): LineHit {
    val column = matches.first().first - lineStart
    val content = text.substring(lineStart, lineEnd)
    // Leading whitespace goes, but never past the first match (a regex may match it).
    val indent = minOf(content.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) content.length else it }, column)
    val trimmed = content.substring(indent)
    val cutStart = cutStart(trimmed.length, column - indent)
    val cutEnd = minOf(trimmed.length, cutStart + SNIPPET_LENGTH)
    val prefix = if (cutStart > 0) ELLIPSIS else ""
    val snippet = prefix + trimmed.substring(cutStart, cutEnd) + if (cutEnd < trimmed.length) ELLIPSIS else ""
    val shift = prefix.length - cutStart
    val highlights =
        matches.mapNotNull { match ->
            val start = (match.first - lineStart - indent).coerceAtLeast(cutStart)
            val end = (minOf(match.last + 1, lineEnd) - lineStart - indent).coerceAtMost(cutEnd)
            if (start < end) start + shift until end + shift else null
        }
    return LineHit(line, column, snippet, highlights)
}

/** Where a line of [length] characters starts to show: [SNIPPET_CONTEXT] before its first match at [firstAt]. */
private fun cutStart(
    length: Int,
    firstAt: Int,
): Int = if (length <= SNIPPET_LENGTH) 0 else (firstAt - SNIPPET_CONTEXT).coerceIn(0, length - SNIPPET_LENGTH)
