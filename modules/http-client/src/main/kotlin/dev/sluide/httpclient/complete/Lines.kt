package dev.sluide.httpclient.complete

/** Finds the line of an offset, splitting as `String.lines()` (and so the parser) does: `\r\n`, `\n` and `\r`. */
internal object Lines {
    /** The line [index] (0-based) spans `text[start, end)`, without its terminator. */
    data class Line(
        val index: Int,
        val start: Int,
        val end: Int,
    )

    /** The line holding [caret]; null if the caret is between the `\r` and the `\n` of a line break. */
    fun locate(
        text: String,
        caret: Int,
    ): Line? {
        var start = 0
        var index = 0
        while (true) {
            var end = start
            while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
            if (caret <= end) return Line(index, start, end)
            val next = if (text.startsWith("\r\n", end)) end + 2 else end + 1
            if (caret < next) return null
            start = next
            index++
        }
    }
}
