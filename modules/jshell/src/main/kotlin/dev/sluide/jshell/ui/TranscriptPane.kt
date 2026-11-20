package dev.sluide.jshell.ui

import dev.sluide.jshell.EvalEvent
import javafx.application.Platform
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.ScrollBar
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import org.fxmisc.flowless.VirtualFlow
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.Caret
import org.fxmisc.richtext.StyleClassedTextArea
import org.fxmisc.richtext.util.UndoUtils

/**
 * FX thread. The read-only transcript of a JShell view: the paragraphs of a [TranscriptModel] in a RichTextFX area
 * that only ever appends (and drops leading paragraphs at the cap), selectable, with a context menu (Copy, Select All,
 * Clear). A click on a paragraph with a jump target calls [onJump] with its 0-based document line. The pane follows new
 * text while it is scrolled to the bottom. The font is the monospace family of the stylesheet, which a style on [node]
 * overrides.
 */
internal class TranscriptPane(
    private val onJump: (line: Int) -> Unit,
) {
    private val model = TranscriptModel()

    internal val area =
        StyleClassedTextArea().apply {
            styleClass += "transcript-area"
            isEditable = false
            isWrapText = true
            showCaret = Caret.CaretVisibility.OFF
            // read-only: an undo history would only keep every text the transcript ever showed
            undoManager = UndoUtils.noOpUndoManager()
        }

    private val scroll =
        VirtualizedScrollPane(area).apply {
            styleClass += "transcript-pane"
            stylesheets += checkNotNull(TranscriptPane::class.java.getResource(CSS)).toExternalForm()
        }

    val node: Node get() = scroll

    /**
     * Whether new text scrolls the view to its end. A scroll by the user (wheel, scroll bar, scroll key) sets it to
     * whether the view then shows the end, a taller view that shows the end and Clear set it. The pane's own scrolling
     * never reads the layout, which lags behind text that was just added.
     */
    private var following = true

    /** A scroll to the end is queued, so that one scroll serves all text added before it runs; it scrolls if following. */
    private var scrollQueued = false

    init {
        area.contextMenu =
            ContextMenu(
                MenuItem("Copy").apply { setOnAction { area.copy() } },
                MenuItem("Select All").apply { setOnAction { area.selectAll() } },
                MenuItem("Clear").apply { setOnAction { clear() } },
            ).also { menu ->
                menu.setOnShowing { menu.items.first().isDisable = area.selection.length == 0 }
            }
        scroll.addEventFilter(ScrollEvent.SCROLL) { userScrolled() }
        scroll.addEventFilter(KeyEvent.KEY_PRESSED) { if (it.code in SCROLL_KEYS) userScrolled() }
        // The release decides again: holding the mouse on the track or an arrow keeps scrolling without further events.
        scroll.addEventFilter(MouseEvent.ANY) {
            if (it.eventType in SCROLL_BAR_EVENTS && onScrollBar(it)) userScrolled()
        }
        // a taller view that shows the end follows again; a shorter one keeps following
        area.heightProperty().addListener { _, _, _ -> if (!following) decideLater() }
        area.addEventHandler(MouseEvent.MOUSE_MOVED) {
            area.cursor =
                if (jumpLineAt(it) != null) Cursor.HAND else Cursor.TEXT
        }
        area.addEventHandler(MouseEvent.MOUSE_CLICKED) {
            if (it.button == MouseButton.PRIMARY && it.isStillSincePress && area.selection.length == 0) {
                jumpLineAt(it)?.let(onJump)
            }
        }
    }

    fun startEvaluation(text: String) = model.startEvaluation(text)

    fun accept(event: EvalEvent) = applying { model.accept(event) }

    fun lateOutput(
        text: String,
        error: Boolean,
    ) = applying { model.lateOutput(text, error) }

    fun note(text: String) = applying { model.note(text) }

    /** Empties the transcript; the text that comes next is followed, also after a scroll that is not decided yet. */
    fun clear() {
        following = true
        decisionPending = false
        applying { model.clear() }
    }

    /** The document line of the paragraph under the mouse, null in the empty space around the paragraphs. */
    private fun jumpLineAt(event: MouseEvent): Int? {
        // The area lags behind the model until the flush: its paragraphs would not match the jump lines. The flush does
        // not lay out, and the area keeps the cells it shows where they are, re-indexed after dropped paragraphs.
        flush()
        val position =
            area.offsetToPosition(
                area.hit(event.x, event.y).insertionIndex,
                org.fxmisc.richtext.model.TwoDimensional.Bias.Backward,
            )
        val index = position.major
        val bounds = area.getParagraphBoundsOnScreen(index)
        // below the last paragraph the hit is still the last one
        if (bounds.isEmpty || event.screenY >= bounds.get().maxY) return null
        return model.jumpLine(index)
    }

    private fun onScrollBar(event: MouseEvent): Boolean =
        generateSequence(event.target as? Node) { it.parent }.any { it is ScrollBar }

    /** The user scrolled and whether the view follows is not decided yet. */
    private var decisionPending = false

    /**
     * The bottom of the last paragraph's cell before the first undecided user scroll, null if it was not shown or the
     * decision has taken it (a decision after a height change has no user scroll to compare with).
     */
    private var endBefore: Double? = null

    /** The view follows if it shows the end after the scroll the user's input causes. */
    private fun userScrolled() {
        catchUp()
        decideLater()
    }

    private fun decideLater() {
        decisionPending = true
        Platform.runLater(::decide)
    }

    /**
     * Before the user's input acts: does a queued scroll to the end now and lays the view out, so that the input moves
     * the view from where it shows the text, not from where it was laid out last.
     */
    private fun catchUp() {
        if (scrollQueued) {
            scrollQueued = false
            if (following) scrollToEnd()
        }
        scroll.layout()
        if (!decisionPending) endBefore = endBottom()
    }

    /**
     * Decides a pending user scroll after the input's handler ran and before new text is shown: from the laid-out view,
     * as the handler only sets a target position that the flow moves to in its layout. A scroll that moved the end
     * down on screen is one up, also when it stays within the slack of [showsEnd], as small trackpad steps do.
     */
    private fun decide() {
        if (!decisionPending) return
        decisionPending = false
        scroll.layout()
        val before = endBefore
        endBefore = null
        val after = endBottom()
        val movedUp = before != null && after != null && after > before
        following = !movedUp && showsEnd()
    }

    /** The area's flow of paragraph cells, its only child. */
    private val flow by lazy { area.childrenUnmodifiable.first { it is VirtualFlow<*, *> } as VirtualFlow<*, *> }

    /**
     * Where the last paragraph's cell ends in the flow, null if it is not shown. The area's paragraph bounds would not
     * do: they are cut at the view's edge, so a cell reaching past it does not tell how far it moved.
     */
    private fun endBottom(): Double? {
        val cell = flow.getCellIfVisible(area.paragraphs.size - 1)
        return if (cell.isPresent) {
            cell
                .get()
                .node.boundsInParent.maxY
        } else {
            null
        }
    }

    /**
     * Whether the view shows the end by Flowless's estimates of the scroll offset and the total height, which come
     * from an average paragraph height: with wrapped paragraphs of mixed heights they miss the end the flow scrolls to
     * by a fraction of a pixel (measured up to 0.44 px), which the slack absorbs. A step up within the slack is told
     * by [endBefore], not by this check.
     */
    private fun showsEnd(): Boolean {
        val total = scroll.totalHeightEstimateProperty().value ?: return true
        return scroll.estimatedScrollY + area.height >= total - BOTTOM_SLACK
    }

    /** Changes the model made that the area does not show yet: they are applied together at the end of the FX event. */
    private val queue = ArrayList<TranscriptChange>()

    /** The paragraphs in the area. */
    private var shown = 0

    private fun applying(step: () -> TranscriptChange) {
        queue += step()
        if (queue.size == 1) Platform.runLater(::flush)
    }

    /** Applies the queued changes as one. */
    private fun flush() {
        if (queue.isEmpty()) return
        decide()
        val change = mergeChanges(queue, shown)
        queue.clear()
        change.append?.let { area.append(it.text, styleOf(it.kind)) }
        appendParagraphs(change.added, shown > 0)
        if (change.dropped > 0) area.deleteText(0, 0, change.dropped, 0)
        shown = shown - change.dropped + change.added.size
        followEnd()
    }

    /** Appends the paragraphs, one `append` per run of the same kind, with a line end before the first if [afterText]. */
    private fun appendParagraphs(
        paragraphs: List<TranscriptParagraph>,
        afterText: Boolean,
    ) {
        var i = 0
        var needsBreak = afterText
        while (i < paragraphs.size) {
            val kind = paragraphs[i].kind
            val run = StringBuilder()
            while (i < paragraphs.size && paragraphs[i].kind == kind) {
                if (needsBreak) run.append('\n')
                run.append(paragraphs[i].text)
                needsBreak = true
                i++
            }
            area.append(run.toString(), styleOf(kind))
        }
    }

    private fun followEnd() {
        if (scrollQueued) return
        scrollQueued = true
        Platform.runLater {
            if (scrollQueued) {
                scrollQueued = false
                if (following) scrollToEnd()
            }
        }
    }

    private fun scrollToEnd() = area.showParagraphAtBottom(area.paragraphs.size - 1)

    private fun styleOf(kind: ParagraphKind) =
        when (kind) {
            ParagraphKind.HEADER -> "transcript-header"
            ParagraphKind.OUTPUT -> "transcript-output"
            ParagraphKind.ERROR_OUTPUT -> "transcript-error-output"
            ParagraphKind.VALUE -> "transcript-value"
            ParagraphKind.DIAGNOSTIC -> "transcript-diagnostic"
            ParagraphKind.THROWN -> "transcript-thrown"
            ParagraphKind.NOTE -> "transcript-note"
        }

    private companion object {
        const val CSS = "/dev/sluide/jshell/transcript.css"
        const val BOTTOM_SLACK = 8.0
        val SCROLL_BAR_EVENTS = setOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED)
        val SCROLL_KEYS = setOf(KeyCode.PAGE_UP, KeyCode.PAGE_DOWN, KeyCode.HOME, KeyCode.END, KeyCode.UP, KeyCode.DOWN)
    }
}
