package dev.sluide.coloring.ui

import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.util.Subscription

/**
 * The head of the picker column: which slot it edits ("Editing: <tab> › <slot>"), the input field for that slot's
 * color in any CSS notation and a large preview (ADR 0111).
 */
internal class InputSection(
    state: ColoringState,
) {
    private val header =
        Label().apply {
            id = "coloring-editing"
            styleClass += "coloring-editing"
        }
    private val colorField =
        ColorField(state.editingColor, promptText = "Any CSS color, e.g. #3b82f6 or oklch(62% 0.19 260)")
    private val field = colorField.node
    private val preview =
        Swatch(state.editingColor, width = PREVIEW_MIN_WIDTH, height = PREVIEW_HEIGHT).apply {
            styleClass += "coloring-preview"
            maxWidth = Double.MAX_VALUE
        }
    private val subscription: Subscription = state.editing.subscribe { slot -> header.text = "Editing: ${slot.title}" }

    val node: Node = VBox(6.0, header, field, preview).apply { styleClass += "coloring-input-section" }

    /** Gives the focus to the input field. */
    fun focus() = field.requestFocus()

    /** Releases the listeners on the window's state. */
    fun dispose() {
        subscription.unsubscribe()
        colorField.dispose()
        preview.dispose()
    }

    internal companion object {
        const val PREVIEW_MIN_WIDTH = 120.0
        const val PREVIEW_HEIGHT = 56.0
    }
}
