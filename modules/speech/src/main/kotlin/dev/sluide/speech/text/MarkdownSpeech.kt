package dev.sluide.speech.text

/**
 * The block level of [SpeechText.prepare] for Markdown: scans [shown] line by line, skips what is not read (front
 * matter, code blocks, HTML comments, reference definitions, rules, table delimiter rows), strips the block markers
 * (headings, quotes, list bullets and task boxes, footnote labels, table pipes) and hands the text of each block to
 * [MarkdownInline]. Every block boundary is a hard break. Every search stays within its line, so the scan is linear.
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
        if (isBlank(start, end)) {
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

            shown.startsWith("<!--", text) && find("-->", text + COMMENT_OPEN, end) < 0 -> {
                flush(text)
                inComment = true
            }

            heading(text, end) -> {
                inList = false
            }

            (!paragraph.isBlank() && isSetextUnderline(text, end)) || isRule(text, end) -> {
                flush(text)
            }

            footnoteDefinition(text, end) -> {
                inList = false
            }

            isReferenceDefinition(text, end) -> {
                flush(text)
            }

            inTable && isDelimiterRow(index) -> {
                Unit
            }

            inTable && find("|", text, end) >= 0 -> {
                tableRow(text, end)
            }

            find("|", text, end) >= 0 && isDelimiterRow(index + 1) -> {
                tableRow(text, end)
                inTable = true
            }

            listItem(text, end) -> {
                inList = true
            }

            else -> {
                if (wasBlank && indent == 0) inList = false
                paragraphLine(text, end)
            }
        }
    }

    /**
     * Inside a fenced code block or an HTML comment: the line is skipped, and the closing line ends the block. Text
     * after a comment's `-->` is read.
     */
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
            val close = find("-->", start, end)
            if (close >= 0) {
                inComment = false
                if (!isBlank(close + COMMENT_CLOSE, end)) paragraphLine(close + COMMENT_CLOSE, end)
            }
            return true
        }
        return false
    }

    private fun paragraphLine(
        text: Int,
        end: Int,
    ) {
        paragraph.addRange(shown, text, end)
        paragraph.add('\n', end)
    }

    private fun opensFence(
        text: Int,
        end: Int,
    ): Boolean {
        val char = shown.getOrNull(text)
        if (char != '`' && char != '~') return false
        val length = runLength(text, end, char)
        if (length < FENCE_LENGTH) return false
        if (char == '`' && find("`", text + length, end) >= 0) return false
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

    /** `[^label]: text`: the label goes, the text is read as a block of its own. */
    private fun footnoteDefinition(
        text: Int,
        end: Int,
    ): Boolean {
        if (!shown.startsWith("[^", text)) return false
        val labelEnd = labelEnd(text, end) ?: return false
        flush(text)
        paragraphLine(firstNonSpace(labelEnd + 2, end), end)
        return true
    }

    /** `[label]: destination` on one line. */
    private fun isReferenceDefinition(
        text: Int,
        end: Int,
    ): Boolean {
        val labelEnd = labelEnd(text, end) ?: return false
        return !isBlank(labelEnd + 2, end)
    }

    /** The index of the `]` of a definition label `[…]:` at [text] within the line, or null. */
    private fun labelEnd(
        text: Int,
        end: Int,
    ): Int? {
        if (shown.getOrNull(text) != '[') return null
        var index = text + 1
        while (index < end && shown[index] != ']') {
            if (shown[index] == '[') return null
            if (shown[index] == '\\') index++
            index++
        }
        if (index >= end || index == text + 1 || index + 1 >= end || shown[index + 1] != ':') return null
        return index
    }

    private fun listItem(
        text: Int,
        end: Int,
    ): Boolean {
        val markerEnd = listMarkerEnd(text, end) ?: return false
        flush(text)
        var content = firstNonSpace(markerEnd, end)
        if (content + TASK_BOX_LENGTH <= end && shown[content] == '[' && shown[content + 2] == ']' &&
            shown[content + 1] in " xX"
        ) {
            val afterBox = content + TASK_BOX_LENGTH
            if (afterBox >= end || shown[afterBox].isWhitespace()) content = firstNonSpace(afterBox, end)
        }
        paragraphLine(content, end)
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
                    var digitsEnd = text
                    while (digitsEnd < end && digitsEnd - text <= MAX_ORDERED_DIGITS && shown[digitsEnd].isDigit()) {
                        digitsEnd++
                    }
                    if (digitsEnd - text > MAX_ORDERED_DIGITS || digitsEnd >= end) return null
                    if (shown[digitsEnd] != '.' && shown[digitsEnd] != ')') return null
                    digitsEnd + 1
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
            if (index < end && shown[index] == '\\') {
                index = minOf(index + 2, end)
                continue
            }
            if (index == end || shown[index] == '|') {
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
            index++
        }
        out.paragraph(end)
    }

    /** `| :--- | ---: |`: cells of dashes with optional alignment colons, at least one pipe. */
    private fun isDelimiterRow(index: Int): Boolean {
        val line = lines.getOrNull(index) ?: return false
        var start = firstNonSpace(afterQuotes(line.start, line.end), line.end)
        var end = line.end
        while (end > start && shown[end - 1].isWhitespace()) end--
        if (find("|", start, end) < 0) return false
        if (shown[start] == '|') start++
        if (end > start && shown[end - 1] == '|') end--
        var cellStart = start
        for (cellEnd in start..end) {
            if (cellEnd < end && shown[cellEnd] != '|') continue
            if (!isDelimiterCell(cellStart, cellEnd)) return false
            cellStart = cellEnd + 1
        }
        return true
    }

    private fun isDelimiterCell(
        start: Int,
        end: Int,
    ): Boolean {
        var from = firstNonSpace(start, end)
        var to = end
        while (to > from && shown[to - 1].isWhitespace()) to--
        if (from < to && shown[from] == ':') from++
        if (to > from && shown[to - 1] == ':') to--
        return to > from && runLength(from, to, '-') == to - from
    }

    /** `===` or `---` (any length) under a paragraph. */
    private fun isSetextUnderline(
        text: Int,
        end: Int,
    ): Boolean {
        val char = shown[text]
        if (char != '=' && char != '-') return false
        return isBlank(text + runLength(text, end, char), end)
    }

    /** A thematic break: three or more `-`, `*` or `_`, with spaces between them allowed. */
    private fun isRule(
        text: Int,
        end: Int,
    ): Boolean {
        val char = shown[text]
        if (char != '-' && char != '*' && char != '_') return false
        var count = 0
        for (index in text until end) {
            when (shown[index]) {
                char -> count++
                ' ', '\t' -> Unit
                else -> return false
            }
        }
        return count >= RULE_MIN_LENGTH
    }

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
    ): Boolean {
        val line = lines[index]
        return line.end - line.start >= text.length &&
            shown.startsWith(text, line.start) &&
            isBlank(line.start + text.length, line.end)
    }

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

    private fun isBlank(
        start: Int,
        end: Int,
    ): Boolean {
        for (index in start until end) if (!shown[index].isWhitespace()) return false
        return true
    }

    /** The first index of [part] from [start] that ends by [end], or -1; never searches past the line. */
    private fun find(
        part: String,
        start: Int,
        end: Int,
    ): Int {
        for (index in start..end - part.length) if (shown.startsWith(part, index)) return index
        return -1
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
        const val RULE_MIN_LENGTH = 3
        const val MAX_HEADING_LEVEL = 6
        const val MAX_ORDERED_DIGITS = 9
        const val TASK_BOX_LENGTH = 3
        const val COMMENT_OPEN = 4
        const val COMMENT_CLOSE = 3
        const val BYTE_ORDER_MARK = '﻿'

        /** The lines of [text] without their line breaks (`\n`, `\r\n`, `\r`); a leading byte order mark is skipped. */
        fun lines(text: String): List<Line> {
            val lines = mutableListOf<Line>()
            var start = if (text.startsWith(BYTE_ORDER_MARK)) 1 else 0
            var index = start
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
