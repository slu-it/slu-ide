package dev.sluide.textediting

/** Line commands on texts with `\n` line breaks. The lines a selection ending at a line start touches exclude it. */
object LineOperations {
    /**
     * Inserts a copy of the selected text after it and selects the copy; without a selection, copies the line of
     * the caret below it and moves the caret along.
     */
    fun duplicate(
        text: String,
        selection: Selection,
    ): TextEdit {
        if (!selection.isEmpty) {
            val length = selection.end - selection.start
            return TextEdit(
                selection.end,
                selection.end,
                text.substring(selection.start, selection.end),
                Selection(selection.anchor + length, selection.caret + length),
            )
        }
        val start = lineStart(text, selection.caret)
        val end = lineEnd(text, selection.caret)
        val copy = "\n" + text.substring(start, end)
        return TextEdit(end, end, copy, Selection(selection.caret + copy.length))
    }

    /**
     * Deletes the touched lines with their line break (the one before them for the last line) and keeps the caret's
     * column on the line that takes their place. `null` for an empty text.
     */
    fun deleteLines(
        text: String,
        selection: Selection,
    ): TextEdit? {
        if (text.isEmpty()) return null
        val lines = touchedLines(text, selection)
        val column = selection.caret - lineStart(text, selection.caret)
        return when {
            lines.end < text.length -> {
                val nextLength = lineEnd(text, lines.end + 1) - (lines.end + 1)
                TextEdit(lines.start, lines.end + 1, "", Selection(lines.start + minOf(column, nextLength)))
            }

            lines.start > 0 -> {
                val previousStart = lineStart(text, lines.start - 1)
                val previousLength = lines.start - 1 - previousStart
                TextEdit(lines.start - 1, lines.end, "", Selection(previousStart + minOf(column, previousLength)))
            }

            else -> {
                TextEdit(0, text.length, "", Selection(0))
            }
        }
    }

    /** Swaps the touched lines with the line above; the selection moves along. `null` on the first line. */
    fun moveUp(
        text: String,
        selection: Selection,
    ): TextEdit? {
        val lines = touchedLines(text, selection)
        if (lines.start == 0) return null
        val previousStart = lineStart(text, lines.start - 1)
        val previous = text.substring(previousStart, lines.start - 1)
        val block = text.substring(lines.start, lines.end)
        return TextEdit(previousStart, lines.end, block + "\n" + previous, selection.movedBy(-previous.length - 1))
    }

    /** Swaps the touched lines with the line below; the selection moves along. `null` on the last line. */
    fun moveDown(
        text: String,
        selection: Selection,
    ): TextEdit? {
        val lines = touchedLines(text, selection)
        if (lines.end == text.length) return null
        val nextEnd = lineEnd(text, lines.end + 1)
        val next = text.substring(lines.end + 1, nextEnd)
        val block = text.substring(lines.start, lines.end)
        // A selection ending at the start of the last line would move past the end; the text keeps its length.
        val moved = selection.movedBy(next.length + 1, text.length)
        return TextEdit(lines.start, nextEnd, next + "\n" + block, moved)
    }

    private fun Selection.movedBy(
        delta: Int,
        limit: Int = Int.MAX_VALUE,
    ) = Selection(minOf(anchor + delta, limit), minOf(caret + delta, limit))
}
