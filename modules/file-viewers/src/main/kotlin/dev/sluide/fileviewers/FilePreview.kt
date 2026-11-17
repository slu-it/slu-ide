package dev.sluide.fileviewers

import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import java.nio.file.Path

/** Loads a file's text for a [FilePreview]; [done] runs on the FX thread, `null` for no text. */
fun interface PreviewTexts {
    fun load(
        file: Path,
        done: (String?) -> Unit,
    )
}

/**
 * Where the preview marks and scrolls in the loaded text: [marks] overlaid, [revealStart] until [revealEnd] scrolled
 * into view.
 */
data class PreviewPlace(
    val marks: List<StyledRange>,
    val revealStart: Int,
    val revealEnd: Int,
)

/**
 * A read-only, highlighted editor of a file at a fixed place in its owner's layout, e.g. under a result list: the
 * file's text comes from [texts], [show]'s place marks and scrolls in it. Each file gets a fresh editor (so no undo
 * history builds up), reused for the other places in that file; an answer that arrives after another file was asked
 * for is dropped. Every mouse press in the preview is consumed and goes to [onPress] (the focus stays where it is), the
 * wheel still scrolls. Texts up to [CodeEditors.HIGHLIGHT_LIMIT] are highlighted by [highlighter]. The editor's font
 * is [editorFont], CSS as `FontSpec.toCss()`. FX thread.
 */
class FilePreview(
    private val highlighter: (Path, String) -> SyntaxHighlighter?,
    private val texts: PreviewTexts,
    editorFont: ObservableValue<String>,
    onPress: () -> Unit = {},
) {
    private var editor: TextEditor = newEditor("", null)

    /** The container of the current editor; a fixed place in the owner's layout, whose height the owner sets. */
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

    /** The text shown, for tests. */
    val text: String get() = editor.text

    /** The editor node showing the text, for tests. */
    val editorNode: Node get() = editor.node

    /** Changes with every show of another file and with [cancel]: an older load's answer is dropped. */
    private var generation = 0

    /** The file being loaded, and the place asked for last, applied when the file's text arrives. */
    private var pending: Path? = null
    private var latest: (String) -> PreviewPlace? = { null }

    /**
     * Shows [file]; [place] is asked against the loaded text (again for every show of the already shown file); `null`
     * gives no marks and no scroll.
     */
    fun show(
        file: Path,
        place: (String) -> PreviewPlace?,
    ) {
        latest = place
        if (file == shownFile) {
            // A load of another file still on its way is stale now.
            generation++
            pending = null
            mark(place)
            return
        }
        if (file == pending) return
        pending = file
        val mine = ++generation
        texts.load(file) { loaded ->
            if (mine == generation) {
                pending = null
                if (loaded == null) {
                    empty()
                } else {
                    setEditor(newEditor(loaded, highlightOf(file, loaded)))
                    shownFile = file
                    mark(latest, fresh = true)
                }
            }
        }
    }

    /** Drops loads on their way; what shows stays. */
    fun cancel() {
        generation++
        pending = null
    }

    /** Empties the preview and drops loads on their way. */
    fun clear() {
        cancel()
        empty()
    }

    /**
     * Makes [node] and everything in it not focus traversable: an editor put into a scene without a focus owner (a
     * popup that does not take the focus) would otherwise become it on the next pulse, and get the keys sent there.
     */
    private fun noFocusTraversal(node: Node) {
        node.isFocusTraversable = false
        if (node is Parent) node.childrenUnmodifiable.forEach(::noFocusTraversal)
    }

    private fun highlightOf(
        file: Path,
        text: String,
    ): SyntaxHighlighter? = if (text.length <= CodeEditors.HIGHLIGHT_LIMIT) highlighter(file, text) else null

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
            noFocusTraversal(node)
        }

    /** Overlays the place's marks and scrolls to it; a [fresh] editor is laid out first, or the scroll has no viewport. */
    private fun mark(
        place: (String) -> PreviewPlace?,
        fresh: Boolean = false,
    ) {
        val shown = editor
        val target = place(shown.text)
        if (target == null) {
            shown.overlay(emptyList())
            return
        }
        shown.overlay(target.marks)
        if (fresh) {
            node.applyCss()
            node.layout()
        }
        shown.reveal(target.revealStart, target.revealEnd)
    }
}

/**
 * `start until end` offsets of [line] (0-based) of [text] (`\n` breaks) with up to [context] lines above and below,
 * `null` when the text has no such line.
 */
fun revealAround(
    text: String,
    line: Int,
    context: Int = 5,
): IntRange? {
    val start = lineStart(text, line) ?: return null
    val revealStart = lineStart(text, (line - context).coerceAtLeast(0)) ?: start
    var revealEnd = lineEnd(text, start)
    repeat(context) { if (revealEnd < text.length) revealEnd = lineEnd(text, revealEnd + 1) }
    return revealStart until revealEnd
}

private fun lineEnd(
    text: String,
    from: Int,
): Int = text.indexOf('\n', from).let { if (it < 0) text.length else it }

private fun lineStart(
    text: String,
    line: Int,
): Int? {
    if (line < 0) return null
    var start = 0
    repeat(line) {
        val next = text.indexOf('\n', start)
        if (next < 0) return null
        start = next + 1
    }
    return start
}
