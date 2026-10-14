package dev.sluide.textediting

/** Indentation of texts with `\n` line breaks. */
object Indentation {
    private const val MIN_DETECTED_SIZE = 2
    private const val MAX_DETECTED_SIZE = 8

    /** Pairs of an opening and its closing bracket. */
    private const val BRACKETS = "{}[]()"

    /**
     * The indentation [text] uses, or `null` if it cannot be told (no indented lines, as many tab- as space-indented
     * lines, or no common step).
     *
     * Tabs or spaces is decided by the first character of most indented lines. Lines indented by a single space are
     * not counted, they are usually continuations such as ` *` in block comments. The size of space indentation is
     * the most common increase in indentation between consecutive non-blank lines, between 2 and 8 (the smaller one
     * on ties). Tab indentation gets the default size.
     */
    fun detect(text: String): IndentStyle? {
        var tabLines = 0
        var spaceLines = 0
        val steps = mutableMapOf<Int, Int>()
        var previousIndent = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val indent = leadingWhitespace(line)
            when {
                indent.startsWith('\t') -> tabLines++
                indent.length > 1 -> spaceLines++
            }
            if (!indent.contains('\t')) {
                val step = indent.length - previousIndent
                if (step in MIN_DETECTED_SIZE..MAX_DETECTED_SIZE) steps.merge(step, 1, Int::plus)
                previousIndent = indent.length
            }
        }
        return when {
            tabLines > spaceLines -> {
                IndentStyle.DEFAULT.copy(useTabs = true)
            }

            spaceLines > tabLines -> {
                steps.entries
                    .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
                    ?.let { IndentStyle(useTabs = false, size = it.key) }
            }

            else -> {
                null
            }
        }
    }

    /**
     * Enter: replaces the selection by a line break and the indentation of the current line up to the caret. One
     * more level is added after an opening `{`, `[` or `(` (trailing spaces before the caret are ignored); if its
     * closing bracket follows the caret, that goes on a line of its own with the current indentation.
     */
    fun newline(
        text: String,
        selection: Selection,
        style: IndentStyle,
    ): TextEdit {
        val start = selection.start
        val beforeCaret = text.substring(lineStart(text, start), start)
        val indent = leadingWhitespace(beforeCaret)
        val bracket = BRACKETS.indexOf(beforeCaret.trimEnd().lastOrNull() ?: ' ')
        if (bracket < 0 || bracket % 2 == 1) {
            val inserted = "\n" + indent
            return TextEdit(start, selection.end, inserted, Selection(start + inserted.length))
        }
        val inner = "\n" + indent + style.unit
        val closes = text.getOrNull(selection.end) == BRACKETS[bracket + 1]
        val inserted = if (closes) inner + "\n" + indent else inner
        return TextEdit(start, selection.end, inserted, Selection(start + inner.length))
    }

    /**
     * Tab: within one line, replaces the selection by a tab or by spaces up to the next indentation stop. A selection
     * over several lines indents every non-empty line it touches by one level instead, and keeps the lines selected.
     * `null` if all touched lines are empty.
     */
    fun indent(
        text: String,
        selection: Selection,
        style: IndentStyle,
    ): TextEdit? {
        val start = selection.start
        if (text.indexOf('\n', start) !in start until selection.end) {
            val inserted =
                if (style.useTabs) "\t" else " ".repeat(style.size - column(text, start, style.tabWidth) % style.size)
            return TextEdit(start, selection.end, inserted, Selection(start + inserted.length))
        }
        val splices =
            touchedLines(text, selection)
                .lineStarts(text)
                .filter { lineEnd(text, it) > it }
                .map { Splice(it, 0, style.unit) }
        return spliceEdit(text, splices, selection)
    }

    /**
     * Shift+Tab: removes one level of indentation (a tab, or up to [IndentStyle.size] spaces) from every line the
     * selection or caret touches. `null` if no touched line is indented.
     */
    fun outdent(
        text: String,
        selection: Selection,
        style: IndentStyle,
    ): TextEdit? {
        val splices =
            touchedLines(text, selection).lineStarts(text).map { start ->
                val removed =
                    if (text.getOrNull(start) == '\t') {
                        1
                    } else {
                        text.substring(start, minOf(start + style.size, text.length)).takeWhile { it == ' ' }.length
                    }
                Splice(start, removed, "")
            }
        return spliceEdit(text, splices, selection)
    }

    /** The visual column of [offset] in its line, with tabs reaching to the next multiple of [tabWidth]. */
    internal fun column(
        text: String,
        offset: Int,
        tabWidth: Int,
    ): Int {
        var column = 0
        for (index in lineStart(text, offset) until offset) {
            column = if (text[index] == '\t') column + tabWidth - column % tabWidth else column + 1
        }
        return column
    }

    internal fun leadingWhitespace(line: CharSequence): String = line.takeWhile { it == ' ' || it == '\t' }.toString()
}
