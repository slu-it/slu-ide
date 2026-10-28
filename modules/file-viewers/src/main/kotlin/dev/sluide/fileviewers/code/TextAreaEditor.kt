package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import dev.sluide.textediting.IndentStyle
import javafx.application.Platform
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.util.UndoUtils
import org.fxmisc.wellbehaved.event.Nodes
import java.time.Duration

/**
 * An editor of a text in memory, wrapping long lines, without gutter or file: the editing keys of code views
 * ([editorKeys], indenting in [indentStyle]), plain-text undo (so highlighting is no undo step) and background
 * highlighting by [highlighter] once typing pauses for [highlightPause]. Tests shorten the pause.
 */
internal class TextAreaEditor(
    text: String,
    private val highlighter: SyntaxHighlighter?,
    comment: CommentSyntax = CommentSyntax.NONE,
    indentStyle: IndentStyle = IndentStyle.DEFAULT,
    highlightPause: Duration = HIGHLIGHT_PAUSE,
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
) {
    val area = CodeArea()

    val node: Node =
        VirtualizedScrollPane(area).apply {
            styleClass += "code-view"
            stylesheets +=
                checkNotNull(
                    TextAreaEditor::class.java.getResource("/dev/sluide/fileviewers/syntax.css"),
                ).toExternalForm()
        }

    val text: String get() = area.text

    val textProperty: ObservableValue<String> get() = area.textProperty()

    init {
        area.isWrapText = true
        // The default undo manager would also undo highlighting.
        area.undoManager = UndoUtils.plainTextUndoManager(area)
        area.replaceText(text)
        area.undoManager.forgetHistory()
        Nodes.addInputMap(area, editorKeys(area, { indentStyle }, comment))
        area.moveTo(0)
        BackgroundHighlighting(area, highlighter, highlightPause, dispatch)
        highlight(text)
    }

    fun focus() = area.requestFocus()

    private fun highlight(text: String) {
        if (highlighter != null && text.isNotEmpty()) area.setStyleSpans(0, codeStyleSpans(text, highlighter))
    }
}

/** How long typing pauses before the text is highlighted again. */
private val HIGHLIGHT_PAUSE = Duration.ofMillis(150)
