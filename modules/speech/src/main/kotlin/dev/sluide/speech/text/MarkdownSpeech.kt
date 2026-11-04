package dev.sluide.speech.text

/**
 * The block level of [SpeechText.prepare] for Markdown: scans [shown] line by line, skips what is not read (front
 * matter, code blocks, HTML comments, reference definitions, rules, table delimiter rows), strips the block markers
 * (headings, quotes, list bullets and task boxes, table pipes) and hands the text of each block to [MarkdownInline].
 * Every block boundary is a hard break.
 */
internal class MarkdownSpeech(
    private val shown: String,
) {
    private val out = SpokenTextBuilder()
    private val paragraph = InlineText()
    private val lines = lines(shown)
    private var fence: Fence? = null
    private var inComment = false
    private var inIndentedCode = false
    private var inTable = false
    private var inList = false
    private var previousBlank = true

    fun spoken(): SpokenText {
        for (index in frontMatterEnd() until lines.size) line(index)
        flush(shown.length)
        return out.build()
    }

    private fun line(index: Int) {
        val line = lines[index]
        val start = afterQuotes(line.start, line.end)
        val end = line.end
        if (skippedInBlock(start, end)) return
        if (shown.substring(start, end).isBlank()) {
            flush(line.start)
            previousBlank = true
            inTable = false
            return
        }
        val indent = indentOf(start, end)
        if (inIndentedCode && indent >= CODE_INDENT) return
        inIndentedCode = false
        if (indent >= CODE_INDENT && previousBlank && !inList && paragraph.isBlank()) {
            flush(start)
            inIndentedCode = true
            return
        }
        val wasBlank = previousBlank
        previousBlank = false
        val text = firstNonSpace(start, end)
        when {
            opensFence(text, end) -> {
                flush(text)
            }

            shown.startsWith("<!--", text) -> {
                flush(text)
                inComment = shown.indexOf("-->", text + 4).let { it < 0 || it >= end }
            }

            heading(text, end) -> {
                inList = false
            }

            (!paragraph.isBlank() && isSetextUnderline(text, end)) || isRule(text, end) -> {
                flush(text)
            }

            REFERENCE_DEFINITION.matchesAt(shown, text) -> {
                flush(text)
            }

            inTable && isDelimiterRow(index) -> {
                Unit
            }

            inTable && shown.indexOf('|', text).let { it in 0 until end } -> {
                tableRow(text, end)
            }

            shown.indexOf('|', text).let { it in 0 until end } && isDelimiterRow(index + 1) -> {
                tableRow(text, end)
                inTable = true
            }

            listItem(text, end) -> {
                inList = true
            }

            else -> {
                if (wasBlank && indent == 0) inList = false
                paragraph.addRange(shown, text, end)
                paragraph.add('\n', end)
            }
        }
    }

    /** Inside a fenced code block or an HTML comment: the line is skipped, and the closing line ends the block. */
    private fun skippedInBlock(
        start: Int,
        end: Int,
    ): Boolean {
        val open = fence
        if (open != null) {
            if (open.closedBy(shown, firstNonSpace(start, end), end)) fence = null
            return true
        }
        if (inComment) {
            if (shown.indexOf("-->", start).let { it in 0 until end }) inComment = false
            return true
        }
        return false
    }

    private fun opensFence(
        text: Int,
        end: Int,
    ): Boolean {
        val char = shown.getOrNull(text)
        if (char != '`' && char != '~') return false
        val length = runLength(text, end, char)
        if (length < FENCE_LENGTH) return false
        if (char == '`' && shown.indexOf('`', text + length).let { it in 0 until end }) return false
        fence = Fence(char, length)
        return true
    }

    private fun heading(
        text: Int,
        end: Int,
    ): Boolean {
        val hashes = runLength(text, end, '#')
        if (hashes !in 1..MAX_HEADING_LEVEL) return false
        if (text + hashes < end && shown[text + hashes] != ' ' && shown[text + hashes] != '\t') return false
        var contentEnd = end
        while (contentEnd > text + hashes && shown[contentEnd - 1].isWhitespace()) contentEnd--
        var closing = contentEnd
        while (closing > text + hashes && shown[closing - 1] == '#') closing--
        if (closing < contentEnd &&
            (closing == text + hashes || shown[closing - 1].isWhitespace())
        ) {
            contentEnd = closing
        }
        flush(text)
        paragraph.addRange(shown, text + hashes, maxOf(text + hashes, contentEnd))
        flush(end)
        return true
    }

    private fun listItem(
        text: Int,
        end: Int,
    ): Boolean {
        val markerEnd = listMarkerEnd(text, end) ?: return false
        flush(text)
        var content = firstNonSpace(markerEnd, end)
        if (TASK_BOX.matchesAt(shown, content)) {
            val afterBox = content + TASK_BOX_LENGTH
            if (afterBox >= end || shown[afterBox].isWhitespace()) content = firstNonSpace(afterBox, end)
        }
        paragraph.addRange(shown, content, end)
        paragraph.add('\n', end)
        return true
    }

    /** The end of a list marker (`-`, `*`, `+`, `1.`, `1)`) at [text] followed by whitespace or the line end. */
    private fun listMarkerEnd(
        text: Int,
        end: Int,
    ): Int? {
        val markerEnd =
            when (shown[text]) {
                '-', '*', '+' -> {
                    text + 1
                }

                in '0'..'9' -> {
                    val digits = (text until end).takeWhile { shown[it].isDigit() }.size
                    if (digits > MAX_ORDERED_DIGITS || text + digits >= end) return null
                    if (shown[text + digits] != '.' && shown[text + digits] != ')') return null
                    text + digits + 1
                }

                else -> {
                    return null
                }
            }
        return markerEnd.takeIf { it >= end || shown[it] == ' ' || shown[it] == '\t' }
    }

    /** One table row: each non-empty cell's text, separated by ", ", the row its own sentence. */
    private fun tableRow(
        text: Int,
        end: Int,
    ) {
        flush(text)
        var cellStart = if (shown[text] == '|') text + 1 else text
        var separator = -1
        var index = cellStart
        while (index <= end) {
            when {
                index < end && shown[index] == '\\' -> {
                    index += 2
                    continue
                }

                index == end || shown[index] == '|' -> {
                    var cellEnd = index
                    while (cellEnd > cellStart && shown[cellEnd - 1].isWhitespace()) cellEnd--
                    val cell = InlineText().apply { addRange(shown, firstNonSpace(cellStart, cellEnd), cellEnd) }
                    if (!cell.isBlank()) {
                        if (separator >= 0) {
                            out.char(',', separator)
                            out.space(separator)
                        }
                        MarkdownInline.emit(cell, out)
                        separator = index
                    }
                    cellStart = index + 1
                }
            }
            index++
        }
        out.paragraph(end)
    }

    private fun isDelimiterRow(index: Int): Boolean {
        val line = lines.getOrNull(index) ?: return false
        val start = afterQuotes(line.start, line.end)
        val row = shown.substring(start, line.end).trim()
        return '|' in row && DELIMITER_ROW.matches(row)
    }

    private fun isSetextUnderline(
        text: Int,
        end: Int,
    ): Boolean = SETEXT_UNDERLINE.matches(shown.substring(text, end))

    private fun isRule(
        text: Int,
        end: Int,
    ): Boolean = RULE.matches(shown.substring(text, end))

    /** Resolves the gathered block text and ends it with a hard break at [source]. */
    private fun flush(source: Int) {
        if (!paragraph.isBlank()) MarkdownInline.emit(paragraph, out)
        paragraph.clear()
        out.paragraph(source)
    }

    private fun frontMatterEnd(): Int {
        if (lines.isEmpty() || !isLine(0, "---")) return 0
        val closing = (1 until lines.size).firstOrNull { isLine(it, "---") || isLine(it, "...") } ?: return 0
        return closing + 1
    }

    private fun isLine(
        index: Int,
        text: String,
    ): Boolean = shown.substring(lines[index].start, lines[index].end).trimEnd() == text

    /** Skips blockquote markers: up to 3 spaces, `>` and one optional space, repeated. */
    private fun afterQuotes(
        start: Int,
        end: Int,
    ): Int {
        var content = start
        while (true) {
            var marker = content
            while (marker < end && marker - content < CODE_INDENT - 1 && shown[marker] == ' ') marker++
            if (marker >= end || shown[marker] != '>') return content
            content = marker + 1
            if (content < end && shown[content] == ' ') content++
        }
    }

    private fun indentOf(
        start: Int,
        end: Int,
    ): Int {
        var columns = 0
        for (index in start until end) {
            when (shown[index]) {
                ' ' -> columns++
                '\t' -> columns = (columns / TAB_WIDTH + 1) * TAB_WIDTH
                else -> return columns
            }
        }
        return columns
    }

    private fun firstNonSpace(
        start: Int,
        end: Int,
    ): Int {
        var index = start
        while (index < end && (shown[index] == ' ' || shown[index] == '\t')) index++
        return index
    }

    private fun runLength(
        start: Int,
        end: Int,
        char: Char,
    ): Int {
        var index = start
        while (index < end && shown[index] == char) index++
        return index - start
    }

    private class Line(
        val start: Int,
        val end: Int,
    )

    private class Fence(
        val char: Char,
        val length: Int,
    ) {
        fun closedBy(
            shown: String,
            text: Int,
            end: Int,
        ): Boolean {
            var index = text
            while (index < end && shown[index] == char) index++
            return index - text >= length && shown.substring(index, end).isBlank()
        }
    }

    private companion object {
        const val CODE_INDENT = 4
        const val TAB_WIDTH = 4
        const val FENCE_LENGTH = 3
        const val MAX_HEADING_LEVEL = 6
        const val MAX_ORDERED_DIGITS = 9
        const val TASK_BOX_LENGTH = 3
        val TASK_BOX = Regex("""\[[ xX]]""")
        val REFERENCE_DEFINITION = Regex("""\[[^\]]+]:\s*\S""")
        val SETEXT_UNDERLINE = Regex("""(=+|-+)\s*""")
        val RULE = Regex("""([-*_])(\s*\1){2,}\s*""")
        val DELIMITER_ROW = Regex("""\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?""")

        /** The lines of [text] without their line breaks (`\n`, `\r\n`, `\r`). */
        fun lines(text: String): List<Line> {
            val lines = mutableListOf<Line>()
            var start = 0
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char == '\n' || char == '\r') {
                    lines += Line(start, index)
                    if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                    start = index + 1
                }
                index++
            }
            if (start < text.length) lines += Line(start, text.length)
            return lines
        }
    }
}
