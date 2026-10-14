package dev.sluide.fileviewers

import dev.sluide.textediting.TextReplacement

/** How a line of a file differs from the original, shown beside its line number; [styleClass] colours it. */
internal enum class LineMarker(
    val styleClass: String,
) {
    /** The line is new. */
    ADDED("diff-added"),

    /** The line replaces different lines. */
    MODIFIED("diff-modified"),

    /** Lines of the original were removed right above this line (or below it, if it is the last line). */
    REMOVED_ABOVE("diff-removed-above"),
}

/** The [LineMarker]s of the lines of a file, `null` for unchanged lines. */
internal object LineMarkers {
    /**
     * The marker of each of the [lineCount] lines of a file with the [changes]: added and modified lines as such, and
     * removed lines on the line after them, or the last line if they were at the end. Changes beyond [lineCount] are
     * cut off.
     */
    fun of(
        changes: List<LineChange>,
        lineCount: Int,
    ): List<LineMarker?> {
        val markers = arrayOfNulls<LineMarker>(lineCount)
        for (change in changes) {
            val kind = change.kind
            if (kind == DiffKind.REMOVED) {
                markRemoved(markers, change.current.first)
            } else {
                val marker = if (kind == DiffKind.ADDED) LineMarker.ADDED else LineMarker.MODIFIED
                for (line in change.current.clampTo(lineCount)) markers[line] = marker
            }
        }
        return markers.asList()
    }

    /**
     * The [markers] after lines `[line, line + removedLines)` were replaced by [insertedLines] lines: replaced lines
     * that are still there are modified (or stay added), further inserted lines are added, and if lines are gone, the
     * line after them is marked as removed above; lines below move along.
     */
    fun afterEdit(
        markers: List<LineMarker?>,
        line: Int,
        removedLines: Int,
        insertedLines: Int,
    ): List<LineMarker?> {
        val start = line.coerceIn(0, markers.size)
        val end = (line + removedLines).coerceIn(start, markers.size)
        val edited =
            List(insertedLines) { i ->
                when {
                    i >= end - start -> LineMarker.ADDED
                    markers[start + i] == LineMarker.ADDED -> LineMarker.ADDED
                    else -> LineMarker.MODIFIED
                }
            }
        val result = (markers.subList(0, start) + edited + markers.subList(end, markers.size)).toTypedArray()
        if (insertedLines < end - start) markRemoved(result, start + insertedLines)
        return result.asList()
    }

    /** Marks lines removed before [line] on that line, or on the last line at the end, unless it has a marker. */
    private fun markRemoved(
        markers: Array<LineMarker?>,
        line: Int,
    ) {
        val marked = line.coerceAtMost(markers.lastIndex)
        if (marked >= 0 && markers[marked] == null) markers[marked] = LineMarker.REMOVED_ABOVE
    }
}

/**
 * An edit of a text in whole lines: lines `[line, line + removedLines)` were replaced by [insertedLines] lines. Lines
 * are the text's paragraphs, separated by `\n`.
 */
internal data class LineEdit(
    val line: Int,
    val removedLines: Int,
    val insertedLines: Int,
) {
    companion object {
        /**
         * The lines that replacing [removed] by [inserted] edits, at a position in [line] that [startsLine] (is at the
         * start of the line) and before text that [endsLine] (starts with a line break, or is the end). Inserting or
         * removing whole lines leaves the lines around them alone: typing Enter at the end of a line adds a line below
         * it without modifying it.
         */
        fun of(
            line: Int,
            startsLine: Boolean,
            endsLine: Boolean,
            removed: String,
            inserted: String,
        ): LineEdit {
            val removedBreaks = removed.count { it == '\n' }
            val insertedBreaks = inserted.count { it == '\n' }
            return when {
                // Whole lines before the line the position is in, which stays as it was.
                startsLine && removed.isEmptyOr { it.endsWith('\n') } && inserted.isEmptyOr { it.endsWith('\n') } -> {
                    LineEdit(line, removedBreaks, insertedBreaks)
                }

                // Whole lines after the line the position is at the end of.
                endsLine && removed.isEmptyOr { it.startsWith('\n') } && inserted.isEmptyOr { it.startsWith('\n') } -> {
                    LineEdit(line + 1, removedBreaks, insertedBreaks)
                }

                else -> {
                    LineEdit(line, removedBreaks + 1, insertedBreaks + 1)
                }
            }
        }

        /** The lines that replacing [removed] at [position] of [text], the text before the change, by [inserted] edits. */
        fun at(
            text: String,
            position: Int,
            removed: String,
            inserted: String,
        ): LineEdit {
            var line = 0
            for (i in 0 until position) if (text[i] == '\n') line++
            val end = position + removed.length
            return of(
                line = line,
                startsLine = position == 0 || text[position - 1] == '\n',
                endsLine = end == text.length || text[end] == '\n',
                removed = removed,
                inserted = inserted,
            )
        }

        /** The lines that turning [old] into [new] edits, seen as one replacement; `null` if they are equal. */
        fun between(
            old: String,
            new: String,
        ): LineEdit? {
            val replacement = TextReplacement.between(old, new)
            if (replacement.isEmpty) return null
            return at(
                old,
                replacement.start,
                old.substring(replacement.start, replacement.end),
                replacement.replacement,
            )
        }

        private fun String.isEmptyOr(predicate: (String) -> Boolean) = isEmpty() || predicate(this)
    }
}

/**
 * The [markers] of an edited text: exact for the saved text from its diff ([show]), moved along with the edits since
 * ([edited]). The edits since the text was last saved ([saved]) are kept, so that a new diff of the saved text gets
 * them again; markers of lines changed apart from each other stay apart.
 */
internal class ChangeMarkers(
    text: String,
) {
    /** The text [base] belongs to: the saved text when the diff was shown. */
    private var baseText = text
    private var base: List<LineMarker?> = unmarked(text)

    /** The saved text the [edits] started from; `null` when they do not lead from it to the text (too many, or kept). */
    private var editsFrom: String? = text
    private val edits = mutableListOf<LineEdit>()

    /** The marker of each line of the text. */
    var markers: List<LineMarker?> = base
        private set

    /**
     * Takes the markers of [diff] (`null`: no changes), which is of [savedText], for the edited [text]: the edits since
     * [savedText] are applied to them again. The same diff of the same text changes nothing. Without the edits, what
     * changed between [savedText] and [text] counts as one edit.
     */
    fun show(
        diff: TextDiff?,
        savedText: String,
        text: String,
    ) {
        val newBase = diff?.let { LineMarkers.of(it.changes, lineCount(savedText)) } ?: unmarked(savedText)
        if (savedText == baseText && newBase == base) return
        baseText = savedText
        base = newBase
        markers =
            if (savedText == editsFrom) {
                edits.fold(base, ::afterEdit)
            } else {
                LineEdit.between(savedText, text)?.let { afterEdit(base, it) } ?: base
            }
    }

    /** Moves the markers along with [edit] of the text. */
    fun edited(edit: LineEdit) {
        markers = afterEdit(markers, edit)
        if (edits.size < MAX_EDITS) edits += edit else editsFrom = null
    }

    /** The text was saved or read as [savedText] while it was [text]; the next diff is of [savedText]. */
    fun saved(
        savedText: String,
        text: String,
    ) {
        edits.clear()
        editsFrom = savedText.takeIf { it == text }
    }

    /** Takes the diff's markers exactly if [text] is the diff's text again, e.g. once all edits are undone. */
    fun resetIfUnedited(text: String) {
        if (text != baseText) return
        markers = base
        edits.clear()
        editsFrom = text
    }

    private companion object {
        /** Edits kept to apply to a new diff; beyond, the text before and after them is compared instead. */
        const val MAX_EDITS = 1000

        /** The lines of [text] as paragraphs: one more than it has line breaks. */
        fun lineCount(text: String) = text.count { it == '\n' } + 1

        fun unmarked(text: String): List<LineMarker?> = List(lineCount(text)) { null }

        fun afterEdit(
            markers: List<LineMarker?>,
            edit: LineEdit,
        ) = LineMarkers.afterEdit(markers, edit.line, edit.removedLines, edit.insertedLines)
    }
}
