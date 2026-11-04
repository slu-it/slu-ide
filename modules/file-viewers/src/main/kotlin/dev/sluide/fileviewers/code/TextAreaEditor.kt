package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.textediting.CommentSyntax
import dev.sluide.textediting.IndentStyle
import javafx.application.Platform
import javafx.beans.value.ObservableValue
import javafx.geometry.Bounds
import javafx.scene.Node
import javafx.scene.control.IndexRange
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.StyleSpans
import org.fxmisc.richtext.model.StyleSpansBuilder
import org.fxmisc.richtext.model.TwoDimensional.Bias
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

    val selection: IndexRange get() = area.selection

    val caretOffset: Int get() = area.caretPosition

    val caretOffsetProperty: ObservableValue<Int> get() = area.caretPositionProperty()

    /**
     * The caret's bounds on screen, or null when the editor is not really shown (not in a showing window, or it or an
     * ancestor invisible) or the caret is scrolled away.
     */
    fun caretScreenBounds(): Bounds? {
        if (area.scene?.window?.isShowing != true) return null
        if (generateSequence<Node>(area) { it.parent }.any { !it.isVisible }) return null
        // Fresh cells, e.g. right after a replace.
        area.layout()
        return area.caretBounds.orElse(null)
    }

    /**
     * Replaces [start, end) in one undo step and puts the caret after the inserted text; no focus change.
     * Out-of-range bounds are clamped and a reversed range inserts at [start].
     */
    fun replace(
        start: Int,
        end: Int,
        text: String,
    ) {
        val from = start.coerceIn(0, area.length)
        val to = end.coerceIn(from, area.length)
        area.undoManager.preventMerge()
        area.replaceText(from, to, text)
        area.undoManager.preventMerge()
        area.moveTo(from + text.length)
    }

    /** Selects [start, end) (kept within the text) and scrolls it into view; no focus change. */
    fun selectRange(
        start: Int,
        end: Int,
    ) {
        val from = start.coerceIn(0, area.length)
        area.selectRange(from, end.coerceIn(from, area.length))
        area.requestFollowCaret()
    }

    /** The offset of the character marked as an error, see [markError]. */
    private var errorAt: Int? = null

    /** The ranges of the current overlay, within the text when set; see [overlay]. */
    private var overlayed: List<StyledRange> = emptyList()

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
            if (area.length > 0) area.setStyleSpans(0, withMarks(plainSpans(area.length)))
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
        overlay(emptyList())
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
        val wanted = withMarks(styled.mapStyles { style -> style.filterNot { it == ERROR } })
        val range = changedSpanRange(styled, wanted) ?: return
        area.setStyleSpans(range.first, wanted.subView(range.first, range.last + 1))
    }

    /** Whether the user can change the text. */
    var editable: Boolean
        get() = area.isEditable
        set(value) {
            area.isEditable = value
        }

    /**
     * Replaces the overlay by [ranges] (clamped to the text; empty ones dropped), restyling only the span from the
     * first to the last character the old or the new overlay touches.
     */
    fun overlay(ranges: List<StyledRange>) {
        val length = area.length
        val wanted =
            ranges
                .map { StyledRange(it.start.coerceIn(0, length), it.end.coerceIn(0, length), it.styleClass) }
                .filter { it.start < it.end }
        val old = overlayed.filter { it.start < length }
        overlayed = wanted
        val touched = old + wanted
        if (touched.isEmpty()) return
        val from = touched.minOf { it.start }
        val to = minOf(touched.maxOf { it.end }, length)
        if (from >= to) return
        val classes = touched.map { it.styleClass }.toSet()
        val styled = area.getStyleSpans(from, to)
        val bare = styled.mapStyles { style -> style.filterNot { it in classes } }
        val changed = withOverlay(bare, wanted, from)
        val range = changedSpanRange(styled, changed) ?: return
        area.setStyleSpans(from + range.first, changed.subView(range.first, range.last + 1))
    }

    /** Scrolls [start, end) into view; caret, selection and focus stay. */
    fun reveal(
        start: Int,
        end: Int,
    ) {
        if (area.length == 0) return
        val from = start.coerceIn(0, area.length)
        val to = end.coerceIn(from, area.length)
        area.showParagraphInViewport(area.offsetToPosition(from, Bias.Forward).major)
        area.layout()
        val shown = area.getCharacterBoundsOnScreen(from, maxOf(to, from + 1).coerceAtMost(area.length)).orElse(null)
        val bounds = shown?.let { area.screenToLocal(it) } ?: return
        if (bounds.minY < 0) {
            area.scrollYBy(bounds.minY)
        } else if (bounds.maxY > area.height) {
            area.scrollYBy(bounds.maxY - area.height)
        }
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
        BackgroundHighlighting(area, highlighter, highlightPause, dispatch) { _, spans -> withMarks(spans) }

    private fun highlight(text: String) {
        val highlighter = highlighter ?: return
        if (text.isNotEmpty()) area.setStyleSpans(0, withMarks(codeStyleSpans(text, highlighter)))
    }

    /** [spans] of the whole text with the error style on the marked character and the overlay on its ranges. */
    private fun withMarks(spans: StyleSpans<Collection<String>>): StyleSpans<Collection<String>> {
        val at = errorAt?.takeIf { it < spans.length() }
        val marked = if (at == null) spans else overlayRanges(spans, listOf(at..at)) { ERROR_STYLE }
        return withOverlay(marked, overlayed, 0)
    }

    /** [spans], which start at [offset] of the text, with each of [ranges] (text offsets) added where it lies in them. */
    private fun withOverlay(
        spans: StyleSpans<Collection<String>>,
        ranges: List<StyledRange>,
        offset: Int,
    ): StyleSpans<Collection<String>> {
        var result = spans
        for (range in ranges) {
            val from = maxOf(range.start - offset, 0)
            val to = minOf(range.end - offset, spans.length())
            if (from < to) result = overlayRanges(result, listOf(from until to)) { listOf(range.styleClass) }
        }
        return result
    }

    private fun plainSpans(length: Int): StyleSpans<Collection<String>> =
        StyleSpansBuilder<Collection<String>>().add(emptyList(), length).create()
}

/** The style class of a character marked as an error (syntax.css). */
private const val ERROR = "text-error"
private val ERROR_STYLE = listOf(ERROR)

/** How long typing pauses before the text is highlighted again. */
private val HIGHLIGHT_PAUSE = Duration.ofMillis(150)
