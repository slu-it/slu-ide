package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.ui.CaretPopup
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.scene.input.KeyEvent
import javafx.util.Subscription

/** Where a [TargetChooser] shows its list or note: the popup, a fake in tests. FX thread. */
internal interface TargetView : AutoCloseable {
    val isShowing: Boolean

    /** Gets the target taken (Enter, double-click), after the view hid. */
    var onChoose: (NavigationTarget) -> Unit

    /** Told after the view hid, by any cause. */
    var onHidden: () -> Unit

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

    fun hide()
}

/** The [TargetView] of an editor: a [CaretPopup] at the word, without Tab. FX thread. */
internal class TargetPopup(
    private val editor: ExtensibleCodeEditor,
) : TargetView {
    override var onChoose: (NavigationTarget) -> Unit = {}
    override var onHidden: () -> Unit = {}

    private val popup =
        CaretPopup<NavigationTarget>(
            editor,
            cell = { targetCell(it, editor.host.projectFolder) },
            onChoose = { onChoose(it) },
            acceptTab = false,
        ).apply {
            onHidden = {
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

    override fun update(items: List<NavigationTarget>) = popup.setItems(items)

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
