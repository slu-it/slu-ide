package dev.sluide.translation.text

/** Line-level Markdown syntax checks; every function reads one line of [text] from `q` to `end` (exclusive). */
internal object BlockSyntax {
    private const val TAB = 4

    /** The first index from [from] that is no space or tab, or [end]. */
    fun firstNonWs(
        text: String,
        from: Int,
        end: Int,
    ): Int {
        var i = from
        while (i < end && (text[i] == ' ' || text[i] == '\t')) i++
        return i
    }

    /** The index after the last non-blank char in [from] to [end] (>= [from]). */
    fun trimEnd(
        text: String,
        from: Int,
        end: Int,
    ): Int {
        var i = end
        while (i > from && (text[i - 1] == ' ' || text[i - 1] == '\t')) i--
        return i
    }

    /** The width of the indentation in [from] to [to], a tab counting to the next multiple of four. */
    fun columns(
        text: String,
        from: Int,
        to: Int,
    ): Int {
        var width = 0
        for (i in from until to) width = if (text[i] == '\t') (width / TAB + 1) * TAB else width + 1
        return width
    }

    /** Three or more of the same `-`, `_` or `*`, with spaces between, nothing else. */
    fun isThematicBreak(
        text: String,
        q: Int,
        end: Int,
    ): Boolean {
        val mark = text[q]
        if (mark != '-' && mark != '_' && mark != '*') return false
        var count = 0
        for (i in q until end) {
            val c = text[i]
            if (c == mark) {
                count++
            } else if (c != ' ' && c != '\t') {
                return false
            }
        }
        return count >= 3
    }

    /** The length of the fence at [q] (three or more backticks or tildes), or 0 when the line opens none. */
    fun fenceLength(
        text: String,
        q: Int,
        end: Int,
    ): Int {
        val mark = text[q]
        if (mark != '`' && mark != '~') return 0
        val run = runLength(text, q, end, mark)
        if (run < 3) return 0
        if (mark == '`' && (q + run until end).any { text[it] == '`' }) return 0
        return run
    }

    fun isFenceClose(
        text: String,
        q: Int,
        end: Int,
        mark: Char,
        length: Int,
    ): Boolean {
        if (text[q] != mark) return false
        val run = runLength(text, q, end, mark)
        return run >= length && firstNonWs(text, q + run, end) >= end
    }

    /** A line of `=` only (a setext underline). */
    fun isSetextEquals(
        text: String,
        q: Int,
        end: Int,
    ): Boolean {
        val run = runLength(text, q, end, '=')
        return run > 0 && firstNonWs(text, q + run, end) >= end
    }

    /** A table delimiter row: only `|`, `:`, `-` and blanks, with at least one pipe and one dash. */
    fun isTableDelimiter(
        text: String,
        q: Int,
        end: Int,
    ): Boolean {
        var pipe = false
        var dash = false
        for (i in q until end) {
            when (text[i]) {
                '|' -> {
                    pipe = true
                }

                '-' -> {
                    dash = true
                }

                ':', ' ', '\t' -> {}

                else -> {
                    return false
                }
            }
        }
        return pipe && dash
    }

    fun hasPipe(
        text: String,
        from: Int,
        end: Int,
    ): Boolean = (from until end).any { text[it] == '|' }

    /**
     * The index after a list marker (`-`, `*`, `+`, `1.`, `1)`) at [q] and the one blank that follows it, or -1.
     * [interrupting]: the line would interrupt a paragraph, where an ordered marker must be 1.
     */
    fun listMarkerEnd(
        text: String,
        q: Int,
        end: Int,
        interrupting: Boolean,
    ): Int {
        var markEnd = q + 1
        val c = text[q]
        if (c.isDigit()) {
            while (markEnd < end && text[markEnd].isDigit()) markEnd++
            val digits = markEnd - q
            if (digits > 9 || markEnd >= end || (text[markEnd] != '.' && text[markEnd] != ')')) return -1
            if (interrupting && text.substring(q, markEnd).toInt() != 1) return -1
            markEnd++
        } else if (c != '-' && c != '*' && c != '+') {
            return -1
        }
        if (markEnd >= end) return markEnd
        return if (text[markEnd] == ' ' || text[markEnd] == '\t') markEnd + 1 else -1
    }

    /** The index after a task box (`[ ]`, `[x]`, `[X]`) at [p] and the blank after it, or -1. */
    fun taskBoxEnd(
        text: String,
        p: Int,
        end: Int,
    ): Int {
        if (p + 2 >= end || text[p] != '[' || text[p + 2] != ']') return -1
        if (text[p + 1] != ' ' && text[p + 1] != 'x' && text[p + 1] != 'X') return -1
        if (p + 3 >= end) return p + 3
        return if (text[p + 3] == ' ' || text[p + 3] == '\t') p + 4 else -1
    }

    private fun runLength(
        text: String,
        from: Int,
        end: Int,
        mark: Char,
    ): Int {
        var i = from
        while (i < end && text[i] == mark) i++
        return i - from
    }
}
