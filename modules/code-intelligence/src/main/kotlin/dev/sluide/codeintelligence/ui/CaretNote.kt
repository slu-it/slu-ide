package dev.sluide.codeintelligence.ui

import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.scene.input.KeyEvent
import javafx.util.Subscription

/**
 * A note at a place in an editor's text ("Cannot rename this"): a [CaretPopup] with only its placeholder, hidden by
 * the next key press no key binding took (the key still acts), a click outside, scrolling or the focus leaving. FX
 * thread.
 */
internal class CaretNote(
    private val editor: ExtensibleCodeEditor,
) : AutoCloseable {
    private val popup =
        CaretPopup<String>(editor, { PopupCell(it) }, {}, acceptTab = false).apply { onHidden = { stopWatching() } }
    private var keyWatch: Subscription? = null

    val isShowing: Boolean get() = popup.isShowing

    /** The text shown; for tests. */
    val text: String get() = popup.placeholder

    /** Shows [text] at [offset], replacing a note shown. */
    fun show(
        offset: Int,
        text: String,
    ) {
        stopWatching()
        popup.placeholder = text
        popup.setItems(emptyList())
        if (!popup.show(offset)) return
        // A consumed press is a key binding's, such as the one that showed this note: filters added while a press is
        // dispatched see that press too.
        keyWatch =
            editor.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (!event.isConsumed && !event.code.isModifierKey) popup.hide()
            }
    }

    fun hide() = popup.hide()

    override fun close() {
        stopWatching()
        popup.close()
    }

    private fun stopWatching() {
        keyWatch?.unsubscribe()
        keyWatch = null
    }
}
