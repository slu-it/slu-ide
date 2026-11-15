package dev.sluide.findinfiles.ui

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TextEditor
import dev.sluide.textediting.SearchOptions
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import java.nio.file.Path

/**
 * The preview under the result list: a read-only editor of the file of the selected line with that line's matches
 * overlaid. Each file gets a fresh editor (so no undo history builds up), reused for the other lines of that file. The
 * text comes from [previewTexts] (answering on the FX thread); an answer that arrives after the selection moved on is
 * dropped. A mouse press in the preview goes to [onPress] instead (the focus stays in the popup's fields), the wheel
 * still scrolls. The editor's font is [editorFont], CSS as `FontSpec.toCss()`. FX thread.
 */
internal class FindPreview(
    private val highlighter: (Path, String) -> SyntaxHighlighter?,
    private val previewTexts: PreviewTexts,
    private val query: () -> String,
    private val options: () -> SearchOptions,
    editorFont: ObservableValue<String>,
    private val onPress: () -> Unit,
) {
    private var editor: TextEditor = newEditor("", null)

    /** The container of the current editor; a fixed place in the popup. */
    val node: StackPane =
        StackPane(editor.node).apply {
            styleProperty().bind(editorFont)
            addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
                event.consume()
                onPress()
            }
        }

    /** The file whose text the editor shows. */
    var shownFile: Path? = null
        private set

    /** The editor node showing the text; internal for tests. */
    val editorNode: Node get() = editor.node

    /** The text shown; internal for tests. */
    val text: String get() = editor.text

    /** Changes with every selection of another file and with [clear]: an older load's answer is dropped. */
    private var generation = 0

    /** The file being loaded and the row selected last, which the answer is shown for. */
    private var pending: Path? = null
    private var latest: ResultRow.Line? = null

    /** Shows [row]'s line; [row] `null` empties the preview. */
    fun show(row: ResultRow.Line?) {
        latest = row
        if (row == null) {
            clear()
            return
        }
        val file = row.hits.file
        if (file == shownFile) {
            // A load of another file still running is stale now.
            generation++
            pending = null
            mark(row)
            return
        }
        if (file == pending) return
        pending = file
        val mine = ++generation
        previewTexts.load(file) { loaded ->
            if (mine == generation) {
                pending = null
                if (loaded == null) {
                    empty()
                } else {
                    val highlight = if (loaded.length <= LIMIT) highlighter(file, loaded) else null
                    setEditor(newEditor(loaded, highlight))
                    shownFile = file
                    latest?.let { mark(it, fresh = true) }
                }
            }
        }
    }

    /** Drops loads still running; what is shown stays. */
    fun cancel() {
        generation++
        pending = null
    }

    /** Empties the preview and drops loads still running. */
    fun clear() {
        cancel()
        latest = null
        empty()
    }

    private fun empty() {
        if (shownFile != null) setEditor(newEditor("", null))
        shownFile = null
    }

    private fun setEditor(next: TextEditor) {
        editor = next
        node.children.setAll(next.node)
    }

    private fun newEditor(
        text: String,
        highlighter: SyntaxHighlighter?,
    ): TextEditor =
        CodeEditors.text(text, highlighter).apply {
            editable = false
            node.isFocusTraversable = false
        }

    /**
     * Overlays the line's matches and scrolls to it; a [fresh] editor is laid out first, or the scroll has no viewport
     * to go by.
     */
    private fun mark(
        row: ResultRow.Line,
        fresh: Boolean = false,
    ) {
        val shown = editor
        val target = previewTarget(shown.text, row.hit.line, query(), options())
        if (target == null) {
            shown.overlay(emptyList())
            return
        }
        shown.overlay(target.matches.map { StyledRange(it.first, it.last + 1, HIT_CLASS) })
        if (fresh) {
            node.applyCss()
            node.layout()
        }
        shown.reveal(target.revealStart, target.revealEnd)
    }

    private companion object {
        const val LIMIT = CodeEditors.HIGHLIGHT_LIMIT
    }
}
