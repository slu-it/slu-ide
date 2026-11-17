package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.codeintelligence.ui.CaretPopup
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FilePreview
import dev.sluide.fileviewers.PreviewTexts
import dev.sluide.fileviewers.SyntaxHighlighter
import javafx.beans.value.ObservableValue
import javafx.scene.input.KeyEvent
import javafx.util.Subscription
import java.nio.file.Path

/** The preview's height: about 12 lines, as the preview of Find in Files; its width is the list's. */
private const val PREVIEW_HEIGHT = 232.0

/** The list's width, room for a path and the line's text beside `file.py:LINE`; the preview under it is as wide. */
private const val LIST_WIDTH = 600.0

/** Where a [TargetChooser] shows its list or note: the popup, a fake in tests. FX thread. */
internal interface TargetView : AutoCloseable {
    val isShowing: Boolean

    /** Gets the target taken (Enter, double-click), after the view hid. */
    var onChoose: (NavigationTarget) -> Unit

    /** Told after the view hid, by any cause. */
    var onHidden: () -> Unit

    /** Told when the selected item changed (a key, a click) and when the list shows; `null` without items. */
    var onSelected: (NavigationTarget?) -> Unit

    /**
     * Shows [items] at the text [range] (`start until end`), [placeholder] while there are none, replacing what shows.
     * A [note] (a placeholder only) also hides on the next key press.
     */
    fun show(
        range: IntRange,
        items: List<NavigationTarget>,
        placeholder: String,
        note: Boolean = false,
    )

    /** Replaces the items of the shown list, keeping the selection (the line texts arrived). */
    fun update(items: List<NavigationTarget>)

    /** Shows the code at [position] under the list, `null` for nothing. */
    fun preview(position: SourcePosition?)

    fun hide()
}

/**
 * The [TargetView] of an editor: a [CaretPopup] at the word, without Tab, with a read-only preview of the selected
 * place under its list (ADR 0184): the file highlighted by [highlighter], its text from [texts], in [editorFont] (CSS).
 * FX thread.
 */
internal class TargetPopup(
    private val editor: ExtensibleCodeEditor,
    highlighter: (Path, String) -> SyntaxHighlighter?,
    texts: PreviewTexts,
    editorFont: ObservableValue<String>,
) : TargetView {
    override var onChoose: (NavigationTarget) -> Unit = {}
    override var onHidden: () -> Unit = {}
    override var onSelected: (NavigationTarget?) -> Unit = {}

    // A press in the preview does nothing: no caret, no focus, no selection.
    private val preview =
        FilePreview(highlighter, texts, editorFont).apply {
            node.setPrefSize(LIST_WIDTH, PREVIEW_HEIGHT)
            node.setMinSize(0.0, PREVIEW_HEIGHT)
            node.setMaxSize(Double.MAX_VALUE, PREVIEW_HEIGHT)
        }

    /** The text the preview shows, for tests. */
    internal val previewText: String get() = preview.text

    override fun preview(position: SourcePosition?) {
        if (position == null) preview.clear() else preview.show(position.file) { text -> targetPlace(text, position) }
    }

    private val popup =
        CaretPopup<NavigationTarget>(
            editor,
            cell = { targetCell(it, editor.host.projectFolder) },
            onChoose = { onChoose(it) },
            acceptTab = false,
            listWidth = LIST_WIDTH,
            footer = preview.node,
        ).apply {
            onSelected = { this@TargetPopup.onSelected(it) }
            onHidden = {
                preview.clear()
                stopWatchingKeys()
                this@TargetPopup.onHidden()
            }
        }

    private var keyWatch: Subscription? = null

    override val isShowing: Boolean get() = popup.isShowing

    override fun show(
        range: IntRange,
        items: List<NavigationTarget>,
        placeholder: String,
        note: Boolean,
    ) {
        stopWatchingKeys()
        popup.placeholder = placeholder
        popup.setItems(items)
        if (!popup.show(range.first, range.last + 1) || !note) return
        // Not consumed: the key still does what it does in the editor. A consumed press is a key binding's, such as
        // the Shortcut+B that showed this note: filters added while a press is dispatched see that press too.
        keyWatch =
            editor.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (!event.isConsumed && !event.code.isModifierKey) hide()
            }
    }

    override fun update(items: List<NavigationTarget>) = popup.setItems(items, keepIndex = true)

    override fun hide() = popup.hide()

    override fun close() {
        stopWatchingKeys()
        popup.close()
    }

    private fun stopWatchingKeys() {
        keyWatch?.unsubscribe()
        keyWatch = null
    }
}
