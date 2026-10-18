package dev.sluide.fileviewers

import dev.sluide.fileviewers.code.CodeView
import dev.sluide.fileviewers.code.codeStyleSpans
import dev.sluide.textediting.CommentSyntax
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import java.nio.file.Path

/**
 * The editable code view of the code viewers, for views of other modules that embed it (e.g. the HTTP client's): line
 * numbers, change markers and further [gutter columns][GutterColumns], undo, saving, changes on disk and background
 * highlighting as described for code views (ADR 0020). Create it with [CodeEditors.open].
 */
interface CodeEditor :
    EditableFileView,
    TextEditableView,
    GutterColumns {
    /** The current text with `\n` line breaks, unsaved changes included. */
    override val text: String

    /** The text as it changes, with each edit; listeners run on the FX thread. */
    val textProperty: ObservableValue<String>

    /** The line (0-based) the caret is on. */
    val caretLine: Int

    /** Moves the caret to the start of [line] (0-based) and scrolls it into view. */
    fun moveCaretTo(line: Int)
}

/** A read-only text with line numbers, highlighted like code views, e.g. the body of an HTTP response. */
interface CodeDisplay {
    val node: Node

    /** The text shown. */
    val text: String

    /**
     * Shows [text] from its start, highlighted by [highlighter] (plain without one). Texts longer than
     * [CodeEditors.HIGHLIGHT_LIMIT] are not highlighted, which would hold up the FX thread.
     */
    fun show(
        text: String,
        highlighter: SyntaxHighlighter?,
    )
}

/** Creates [CodeEditor]s and [CodeDisplay]s. */
object CodeEditors {
    /** Longer texts are shown plain by a [CodeDisplay]. */
    const val HIGHLIGHT_LIMIT = 1 shl 20

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

    /** An empty display. Call on the FX thread. */
    fun display(): CodeDisplay = AreaDisplay()
}

private class AreaDisplay : CodeDisplay {
    private val area =
        CodeArea().apply {
            isEditable = false
            paragraphGraphicFactory = LineNumberFactory.get(this)
        }

    override val node: Node =
        VirtualizedScrollPane(area).apply {
            styleClass += "code-view"
            stylesheets +=
                checkNotNull(CodeView::class.java.getResource("/dev/sluide/fileviewers/syntax.css")).toExternalForm()
        }

    override val text: String get() = area.text

    override fun show(
        text: String,
        highlighter: SyntaxHighlighter?,
    ) {
        area.replaceText(text)
        if (highlighter != null && text.isNotEmpty() && text.length <= CodeEditors.HIGHLIGHT_LIMIT) {
            area.setStyleSpans(0, codeStyleSpans(text, highlighter))
        }
        area.moveTo(0)
        area.requestFollowCaret()
    }
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

    override val canReplaceText: Boolean get() = !view.readOnly

    override fun replaceText(text: String): Boolean = view.replaceText(text)

    override fun moveCaretTo(line: Int) {
        view.area.moveTo(line.coerceIn(0, view.area.paragraphs.size - 1), 0)
        view.area.requestFollowCaret()
    }
}
