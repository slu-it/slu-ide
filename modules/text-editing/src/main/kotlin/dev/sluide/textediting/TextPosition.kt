package dev.sluide.textediting

/** A 0-based [line] and [column] in a text with `\n` line breaks. */
data class TextPosition(
    val line: Int,
    val column: Int,
) {
    /** The offset of this position in [text], with line and column clamped to the text. */
    fun offsetIn(text: String): Int {
        var start = 0
        repeat(line) {
            val end = text.indexOf('\n', start)
            if (end < 0) return start + minOf(column, text.length - start)
            start = end + 1
        }
        return start + minOf(column, lineEnd(text, start) - start)
    }
}

private val goToInput = Regex("""(\d+)(?:\s*:\s*(\d+))?""")

/**
 * The position for go-to-line input `line` or `line:column` (1-based) in a text of [lineCount] lines, with the line
 * clamped to the text; `null` for blank or other input.
 */
fun parseGoTo(
    input: String,
    lineCount: Int,
): TextPosition? {
    val match = goToInput.matchEntire(input.trim()) ?: return null

    fun number(group: Int) = match.groupValues[group].let { if (it.isEmpty()) 1 else it.toIntOrNull() ?: Int.MAX_VALUE }
    val line = (number(1) - 1).coerceIn(0, maxOf(lineCount, 1) - 1)
    return TextPosition(line, maxOf(number(2) - 1, 0))
}
