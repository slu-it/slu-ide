package dev.sluide.fileviewers

import dev.sluide.textediting.CommentSyntax
import javafx.beans.value.ObservableValue
import java.nio.file.Path

/**
 * The editable code view of the code viewers, for views of other modules that embed it (e.g. the HTTP client's): line
 * numbers, change markers and further [gutter columns][GutterColumns], undo, saving, changes on disk and background
 * highlighting as described for code views (ADR 0020). Create it with [CodeEditors.open].
 */
interface CodeEditor :
    EditableFileView,
    GutterColumns {
    /** The current text with `\n` line breaks, unsaved changes included. */
    val text: String

    /** The text as it changes, with each edit; listeners run on the FX thread. */
    val textProperty: ObservableValue<String>

    /** The line (0-based) the caret is on. */
    val caretLine: Int

    /** Moves the caret to the start of [line] (0-based) and scrolls it into view. */
    fun moveCaretTo(line: Int)
}

/** Creates [CodeEditor]s. */
object CodeEditors {
    /**
     * An editor of [file] highlighted by [highlighter] (plain text without one), whose Shortcut+/ comments lines with
     * [lineComment] (e.g. `#`), or wraps them in [blockComment] (e.g. `/*` and `*/`) without one; with neither, lines
     * cannot be commented. Call on the FX thread.
     */
    fun open(
        file: Path,
        highlighter: SyntaxHighlighter?,
        lineComment: String? = null,
        blockComment: Pair<String, String>? = null,
    ): CodeEditor = CodeViewEditor(CodeView(file, highlighter, CommentSyntax(lineComment, blockComment)))
}

/**
 * A viewer showing files as text, e.g. in a [CodeEditor], whose changes the IDE can therefore mark and show side by
 * side ([FileViewers.openDiff]), with [sourceHighlighter] (plain text if `null`).
 */
interface SourceFileViewer : FileViewer {
    val sourceHighlighter: SyntaxHighlighter?
}

private class CodeViewEditor(
    private val view: CodeView,
) : CodeEditor,
    EditableFileView by view,
    GutterColumns by view {
    override val text: String get() = view.area.text

    override val textProperty: ObservableValue<String> get() = view.area.textProperty()

    override val caretLine: Int get() = view.area.currentParagraph

    override fun moveCaretTo(line: Int) {
        view.area.moveTo(line.coerceIn(0, view.area.paragraphs.size - 1), 0)
        view.area.requestFollowCaret()
    }
}
