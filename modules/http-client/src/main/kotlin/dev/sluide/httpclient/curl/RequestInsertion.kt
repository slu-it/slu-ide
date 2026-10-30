package dev.sluide.httpclient.curl

import dev.sluide.httpclient.parse.HttpFileParser

/** A text with requests inserted, and the line (0-based) where they start. */
internal data class RequestInsertion(
    val text: String,
    val line: Int,
)

/**
 * [text] of a `.http` file with [requests] (`.http` text) inserted for Paste cURL as Request (ADR 0116): after the
 * request of the part that holds [line] (0-based), or at [line] if that part has none; separated from the text above
 * and below by a blank line and `###` where needed. Blank lines around the insertion point are replaced.
 */
internal fun insertRequests(
    text: String,
    line: Int,
    requests: String,
): RequestInsertion {
    val endsWithNewline = text.endsWith("\n")
    val lines = text.removeSuffix("\n").split('\n')
    val block = HttpFileParser.parse(text).requestAt(line)
    val at = block?.let { it.lines.last + 1 } ?: line.coerceIn(0, lines.size)
    val above = lines.subList(0, at).dropLastWhile(String::isBlank)
    val below = lines.subList(at, lines.size).dropWhile(String::isBlank)
    val inserted = requests.trimEnd('\n').split('\n')
    val result = mutableListOf<String>()
    result += above
    if (above.isNotEmpty()) {
        result += ""
        if (!HttpFileParser.isSeparator(inserted.first())) result += "###"
    }
    val start = result.size
    result += inserted
    if (below.isNotEmpty()) {
        result += ""
        if (!HttpFileParser.isSeparator(below.first())) result += "###"
        result += below
    }
    if (endsWithNewline || text.isEmpty()) result += ""
    return RequestInsertion(result.joinToString("\n"), start)
}
