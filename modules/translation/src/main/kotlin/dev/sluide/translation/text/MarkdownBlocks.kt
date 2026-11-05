package dev.sluide.translation.text

/**
 * The block level of the Markdown mode: finds the stretches of [text] that hold text to translate, line by line with
 * exact offsets. Everything else (front matter, code, HTML, definitions, thematic breaks, table delimiters, line
 * markers, blank lines and line breaks) is not part of any stretch and so stays verbatim.
 *
 * Simplifications against CommonMark: a block quote or list item is recognized per line (no lazy-continuation
 * bookkeeping beyond "a list goes on until an unindented line after a blank line"); an indented code block needs a
 * preceding blank line (or the start of a block) and no list; a link reference definition is one line; a table needs
 * a delimiter row under a line with a pipe and goes on while the lines hold a pipe; a front matter block needs a
 * non-blank line after its opener and a closing line; an HTML block ends at its marker or at a blank line, so
 * Markdown inside `<div>` or `<p>` is not translated; code inside a list item is indented four columns past the
 * item's content column and follows a blank line.
 *
 * The lines of a paragraph (also of a list item or block quote paragraph) are one stretch, the line breaks being its
 * gaps, so a sentence that wraps goes to the model whole. A hard break (two spaces or a backslash at the end of a
 * line) ends the stretch and stays outside it.
 */
internal class MarkdownBlocks(
    private val text: String,
) {
    private sealed interface State {
        class Fence(
            val mark: Char,
            val length: Int,
            val maxCloseIndent: Int,
            val quoted: Boolean,
        ) : State

        class Html(
            val endMarker: String,
            val quoted: Boolean,
        ) : State
    }

    private val stretches = ArrayList<Stretch>()
    private var paragraph: Paragraph? = null
    private var quoteEnd = 0
    private var quoteDepth = 0
    private var itemColumn = -1
    private var itemCode = false
    private var state: State? = null
    private var paragraphOpen = false
    private var inList = false
    private var inTable = false
    private var prevBlank = true

    fun stretches(): List<Stretch> {
        var start = frontMatterEnd()
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            val lineEnd = if (newline < 0) text.length else newline
            val end = if (lineEnd > start && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
            prevBlank = if (state != null) continueState(start, end) else processLine(start, end)
            start = lineEnd + 1
        }
        flushParagraph()
        return stretches
    }

    /** The offset after the closing line of YAML (`---`, closed by `---` or `...`) or TOML (`+++`) front matter, or 0. */
    private fun frontMatterEnd(): Int {
        val first = lineAt(0)
        val fence = text.substring(first.first, first.last).trim()
        if (fence != "---" && fence != "+++") return 0
        var start = first.last + 1
        if (start >= text.length) return 0
        val second = lineAt(start)
        if (text.substring(second.first, second.last).isBlank()) return 0
        while (start < text.length) {
            val line = lineAt(start)
            val content = text.substring(start, line.last).trim()
            if (content == fence || (fence == "---" && content == "...")) return minOf(text.length, line.last + 1)
            start = line.last + 1
        }
        return 0
    }

    /** First = start, last = the offset of the line's `\n` (or the text length); both inclusive of nothing else. */
    private fun lineAt(start: Int): IntRange {
        val newline = text.indexOf('\n', start)
        val lineEnd = if (newline < 0) text.length else newline
        return start..lineEnd
    }

    /** A line inside a fence or an HTML block; true when it is blank. */
    private fun continueState(
        start: Int,
        end: Int,
    ): Boolean {
        val current = checkNotNull(state)
        val quoted = if (current is State.Fence) current.quoted else (current as State.Html).quoted
        var p = start
        if (quoted) p = skipQuotes(start, end)
        val q = BlockSyntax.firstNonWs(text, p, end)
        val blank = q >= end
        when (current) {
            is State.Fence -> {
                val indent = BlockSyntax.columns(text, p, q)
                if (!blank && indent <= current.maxCloseIndent &&
                    BlockSyntax.isFenceClose(text, q, end, current.mark, current.length)
                ) {
                    state = null
                }
            }

            is State.Html -> {
                if (current.endMarker == HtmlTags.UNTIL_BLANK) {
                    if (blank) state = null
                } else if (containsIn(current.endMarker, start, end)) {
                    state = null
                }
            }
        }
        return blank
    }

    /** The index after the quote markers (`>` and one blank) at the start of the line. */
    private fun skipQuotes(
        start: Int,
        end: Int,
    ): Int {
        var p = start
        quoteDepth = 0
        while (true) {
            val q = BlockSyntax.firstNonWs(text, p, end)
            if (q >= end || text[q] != '>' || BlockSyntax.columns(text, p, q) > MAX_INDENT) return p
            quoteDepth++
            p = q + 1
            if (p < end && (text[p] == ' ' || text[p] == '\t')) p++
        }
    }

    private fun containsIn(
        marker: String,
        from: Int,
        end: Int,
    ): Boolean {
        for (i in from..end - marker.length) {
            if (text.regionMatches(
                    i,
                    marker,
                    0,
                    marker.length,
                    ignoreCase = true,
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun processLine(
        start: Int,
        end: Int,
    ): Boolean {
        val p = skipQuotes(start, end)
        return content(p, end, p > start)
    }

    /** Reads the line from [from] (after quote markers); true when blank. */
    private fun content(
        from: Int,
        end: Int,
        quoted: Boolean,
    ): Boolean {
        var p = from
        var markers = 0
        quoteEnd = from
        while (true) {
            val q = BlockSyntax.firstNonWs(text, p, end)
            if (q >= end) {
                keepBlock()
                return markers == 0
            }
            val indent = BlockSyntax.columns(text, p, q)
            val structural = indent <= MAX_INDENT || (inList && markers == 0)
            if (indent > MAX_INDENT && markers == 0 && !paragraphOpen && !inList) {
                keepBlock()
                return false
            }
            val code = inList && markers == 0 && itemColumn >= 0 && indent >= itemColumn + CODE_INDENT
            if (code && (prevBlank || itemCode)) {
                itemCode = true
                keepBlock()
                return false
            }
            itemCode = false
            val markerEnd =
                if (structural && !BlockSyntax.isThematicBreak(text, q, end)) {
                    BlockSyntax.listMarkerEnd(text, q, end, paragraphOpen && !inList)
                } else {
                    -1
                }
            if (markers == 0 && indent == 0 && prevBlank && markerEnd < 0) {
                inList = false
                itemColumn = -1
            }
            if (structural && keepsLine(q, end, indent, quoted, markers)) return false
            if (markerEnd >= 0 && structural) {
                inList = true
                markers++
                keepBlock()
                p = markerEnd
                itemColumn = contentColumn(p, end)
                BlockSyntax.taskBoxEnd(text, BlockSyntax.firstNonWs(text, p, end), end).let {
                    if (it >= 0) p = it
                }
                continue
            }
            if (structural && headingOrDefinition(q, end)) return false
            textLine(q, end, markers)
            return false
        }
    }

    /** The column where a list item's content starts, [p] being after its marker and one blank. */
    private fun contentColumn(
        p: Int,
        end: Int,
    ): Int {
        val q = BlockSyntax.firstNonWs(text, p, end)
        val spaces = 1 + BlockSyntax.columns(text, p, q)
        val at = if (q >= end || spaces > MAX_INDENT + 1) p else q
        return BlockSyntax.columns(text, quoteEnd, at)
    }

    /** Opens a fence or HTML block, or keeps a thematic break, setext underline or table delimiter; false otherwise. */
    private fun keepsLine(
        q: Int,
        end: Int,
        indent: Int,
        quoted: Boolean,
        markers: Int,
    ): Boolean {
        val c = text[q]
        val fence = BlockSyntax.fenceLength(text, q, end)
        val htmlEnd = if (c == '<') HtmlTags.blockStart(text, q, end, paragraphOpen) else null
        val last = paragraph?.lastLine
        val delimiter =
            paragraphOpen && last != null && BlockSyntax.isTableDelimiter(text, q, end) &&
                BlockSyntax.hasPipe(text, last.first, last.last + 1)
        when {
            fence > 0 -> {
                val lenient = inList || markers > 0
                state = State.Fence(c, fence, if (lenient) Int.MAX_VALUE else indent + MAX_INDENT, quoted)
            }

            htmlEnd != null -> {
                val closesHere =
                    htmlEnd != HtmlTags.UNTIL_BLANK &&
                        containsIn(htmlEnd, if (htmlEnd == "-->") q + 4 else q + 1, end)
                if (!closesHere) state = State.Html(htmlEnd, quoted)
            }

            delimiter -> {
                headerRow(checkNotNull(last))
                keepBlock()
                inTable = true
                return true
            }

            BlockSyntax.isThematicBreak(text, q, end) -> {}

            c == '=' && paragraphOpen && BlockSyntax.isSetextEquals(text, q, end) -> {}

            else -> {
                return false
            }
        }
        keepBlock()
        return true
    }

    /** An ATX heading (marker kept, text and closing hashes split off) or a definition; true when handled. */
    private fun headingOrDefinition(
        q: Int,
        end: Int,
    ): Boolean {
        when (text[q]) {
            '#' -> {
                var h = q
                while (h < end && text[h] == '#') h++
                if (h - q > MAX_HEADING || (h < end && text[h] != ' ' && text[h] != '\t')) return false
                val from = BlockSyntax.firstNonWs(text, h, end)
                var to = BlockSyntax.trimEnd(text, from, end)
                var closing = to
                while (closing > from && text[closing - 1] == '#') closing--
                if (closing == from) {
                    to = from
                } else if (closing < to && (text[closing - 1] == ' ' || text[closing - 1] == '\t')) {
                    to = BlockSyntax.trimEnd(text, from, closing)
                }
                keepBlock()
                if (from < to) stretches += Stretch(from until to)
                return true
            }

            '[' -> {
                val footnote = FOOTNOTE.matcher(text).region(q, end)
                if (footnote.lookingAt()) {
                    val from = BlockSyntax.firstNonWs(text, footnote.end(), end)
                    keepBlock()
                    textLine(from, end, 0)
                    return true
                }
                if (!paragraphOpen && LINK_DEFINITION.matcher(text).region(q, end).matches()) {
                    keepBlock()
                    return true
                }
                return false
            }

            else -> {
                return false
            }
        }
    }

    /** A line of paragraph text from [q]: a table row inside a table, else part of a paragraph. */
    private fun textLine(
        q: Int,
        end: Int,
        markers: Int,
    ) {
        val to = BlockSyntax.trimEnd(text, q, end)
        if (inTable && markers == 0) {
            if (BlockSyntax.hasPipe(text, q, to)) {
                emitCells(q, to)
                paragraphOpen = false
                return
            }
            inTable = false
        }
        paragraphOpen = true
        val backslash = to > q && text[to - 1] == '\\'
        val textTo = if (backslash) BlockSyntax.trimEnd(text, q, to - 1) else to
        if (textTo <= q) {
            flushParagraph()
            return
        }
        val hard = backslash || (end - to >= 2 && text[end - 1] == ' ' && text[end - 2] == ' ')
        val open = paragraph
        if (open != null && markers == 0 && open.depth == quoteDepth && !open.hardBreak) {
            open.gaps += open.to until q
            open.to = textTo
            open.lastLine = q until textTo
            open.hardBreak = hard
        } else {
            flushParagraph()
            paragraph = Paragraph(q, textTo, quoteDepth, q until textTo, hard)
        }
    }

    private fun flushParagraph() {
        paragraph?.let { stretches += it.stretch() }
        paragraph = null
    }

    /** The paragraph's last line [row] is a table header: the lines before it stay a paragraph, the row is cells. */
    private fun headerRow(row: IntRange) {
        val open = checkNotNull(paragraph)
        if (open.gaps.isNotEmpty()) {
            open.to = open.gaps.removeLast().first
            flushParagraph()
        } else {
            paragraph = null
        }
        emitCells(row.first, row.last + 1)
    }

    /** The cells of a table row from [from] to [to]: each trimmed cell is a stretch, pipes and padding stay. */
    private fun emitCells(
        from: Int,
        to: Int,
    ) {
        var cell = from
        for (i in from..to) {
            if (i < to && (text[i] != '|' || (i > from && text[i - 1] == '\\'))) continue
            val first = BlockSyntax.firstNonWs(text, cell, i)
            val last = BlockSyntax.trimEnd(text, first, i)
            if (first < last) stretches += Stretch(first until last)
            cell = i + 1
        }
    }

    private fun keepBlock() {
        flushParagraph()
        paragraphOpen = false
        inTable = false
    }

    private companion object {
        const val MAX_INDENT = 3
        const val MAX_HEADING = 6
        const val CODE_INDENT = 4
        val FOOTNOTE: java.util.regex.Pattern =
            java.util.regex.Pattern
                .compile("\\[\\^[^\\]\\s]+]:")
        val LINK_DEFINITION: java.util.regex.Pattern =
            java.util.regex.Pattern.compile(
                "\\[[^\\]]+]:[ \\t]*(<[^>]*>|\\S+)([ \\t]+(\"[^\"]*\"|'[^']*'|\\([^)]*\\)))?[ \\t]*",
            )
    }
}
