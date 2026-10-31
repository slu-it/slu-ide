package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import dev.sluide.textediting.IndentStyle
import javafx.application.Platform
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
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
    highlighter: SyntaxHighlighter?,
    comment: CommentSyntax = CommentSyntax.NONE,
    indentStyle: IndentStyle = IndentStyle.DEFAULT,
    private val highlightPause: Duration = HIGHLIGHT_PAUSE,
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

    /** The offset of the character marked as an error, see [markError]. */
    private var errorAt: Int? = null

    init {
        area.isWrapText = true
        // The default undo manager would also undo highlighting.
        area.undoManager = UndoUtils.plainTextUndoManager(area)
        area.replaceText(text)
        area.undoManager.forgetHistory()
        Nodes.addInputMap(area, editorKeys(area, { indentStyle }, comment))
        area.moveTo(0)
        // An edit makes the marked error stale.
        area.plainTextChanges().subscribe { markError(null) }
    }

    private var highlighting = backgroundHighlighting(highlighter)

    /** Highlights the text from now on; setting another one highlights it again at once (plain without one). */
    var highlighter: SyntaxHighlighter? = highlighter
        set(value) {
            if (value === field) return
            field = value
            highlighting.dispose()
            highlighting = backgroundHighlighting(value)
            if (area.length > 0) area.setStyleSpans(0, withError(plainSpans(area.length)))
            highlight(area.text)
        }

    init {
        highlight(text)
    }

    fun focus() = area.requestFocus()

    /**
     * Replaces the whole text in one undo step. The caret keeps its offset (or goes to the end) and the first shown
     * line stays at the top, so a text that changes around the caret does not jump.
     */
    fun replaceText(text: String) {
        if (text == area.text) return
        val caret = area.caretPosition
        val top = if (area.visibleParagraphs.isEmpty()) -1 else area.firstVisibleParToAllParIndex()
        // Typing just before or after must not merge into the replacement's undo step.
        area.undoManager.preventMerge()
        area.replaceText(text)
        area.undoManager.preventMerge()
        area.moveTo(caret.coerceAtMost(area.length))
        if (top >= 0) area.showParagraphAtTop(top.coerceAtMost(area.paragraphs.size - 1))
    }

    /** Selects the character at [offset], or puts the caret at the end past it; scrolls there and focuses. */
    fun select(offset: Int) {
        val at = offset.coerceIn(0, area.length)
        if (at < area.length) area.selectRange(at, at + 1) else area.moveTo(at)
        area.requestFollowCaret()
        area.requestFocus()
    }

    /**
     * Marks the character at [offset] (the last one when [offset] is at or past the end) as an error, over its
     * highlighting; a line break, which shows no underline, passes the mark to the character before it. `null`
     * removes the mark, as does the next edit.
     */
    fun markError(offset: Int?) {
        // Every edit clears the mark: without one, nothing to do and no text to build.
        if (offset == null && errorAt == null) return
        val text = area.text
        val at = offset?.takeIf { text.isNotEmpty() }?.coerceIn(0, text.length - 1)?.let { visibleAt(text, it) }
        if (at == errorAt) return
        errorAt = at
        if (area.length == 0) return
        val styled = area.getStyleSpans(0, area.length)
        val wanted = withError(styled.mapStyles { style -> style.filterNot { it == ERROR } })
        val range = changedSpanRange(styled, wanted) ?: return
        area.setStyleSpans(range.first, wanted.subView(range.first, range.last + 1))
    }

    /** [at], or the nearest character before it that is no line break (if there is one). */
    private fun visibleAt(
        text: String,
        at: Int,
    ): Int {
        var visible = at
        while (visible > 0 && text[visible] == '\n') visible--
        return if (text[visible] == '\n') at else visible
    }

    private fun backgroundHighlighting(highlighter: SyntaxHighlighter?) =
        BackgroundHighlighting(area, highlighter, highlightPause, dispatch) { _, spans -> withError(spans) }

    private fun highlight(text: String) {
        val highlighter = highlighter ?: return
        if (text.isNotEmpty()) area.setStyleSpans(0, withError(codeStyleSpans(text, highlighter)))
    }

    /** [spans] with the error style on the marked character. */
    private fun withError(spans: StyleSpans<Collection<String>>): StyleSpans<Collection<String>> {
        val at = errorAt?.takeIf { it < spans.length() } ?: return spans
        return overlayRanges(spans, listOf(at..at)) { ERROR_STYLE }
    }

    private fun plainSpans(length: Int): StyleSpans<Collection<String>> =
        StyleSpansBuilder<Collection<String>>().add(emptyList(), length).create()
}

/** The style class of a character marked as an error (syntax.css). */
private const val ERROR = "text-error"
private val ERROR_STYLE = listOf(ERROR)

/** How long typing pauses before the text is highlighted again. */
private val HIGHLIGHT_PAUSE = Duration.ofMillis(150)
