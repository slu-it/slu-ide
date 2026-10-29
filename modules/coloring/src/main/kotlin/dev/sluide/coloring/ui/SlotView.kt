package dev.sluide.coloring.ui

import javafx.css.PseudoClass
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.util.Subscription

/**
 * A slot of a tool tab: its label, a swatch and a field of its color (ADR 0111). A click on the swatch (which also
 * focuses the field), a press in the field or the focus in it make it the slot the left column edits, remembered for
 * the tool tab [tab]; while it is, the node is marked `:editing`.
 */
internal class SlotView(
    slot: ColorSlot,
    state: ColoringState,
    tab: String = slot.tab,
    columns: Int = 10,
) {
    private val colorField = ColorField(slot.color, columns = columns)

    /** The field of the slot's color. */
    internal val field: TextField = colorField.node

    /** The swatch of the slot's color; a click edits the slot and does not inspect. */
    internal val swatch: Swatch =
        Swatch(slot.color) {
            state.edit(slot, tab)
            // Focus follows the slot being edited: left in another slot's field, it would take the slot back when
            // the window is focused again.
            field.requestFocus()
        }

    val node: Node =
        HBox(6.0, Label(slot.label), swatch, field).apply {
            styleClass += "color-slot"
            alignment = Pos.CENTER_LEFT
        }

    private val subscriptions: List<Subscription>

    init {
        field.addEventHandler(MouseEvent.MOUSE_PRESSED) { e ->
            if (e.button == MouseButton.PRIMARY) state.edit(slot, tab)
        }
        subscriptions =
            listOf(
                // Only a change of slot: focus coming back with the window must not reset the inspector.
                field.focusedProperty().subscribe { focused ->
                    if (focused && state.editing.value !== slot) state.edit(slot, tab)
                },
                state.editing.subscribe { editing -> node.pseudoClassStateChanged(EDITING, editing === slot) },
            )
    }

    /** Releases the listeners on the window's state. */
    fun dispose() {
        subscriptions.forEach(Subscription::unsubscribe)
        colorField.dispose()
        swatch.dispose()
    }

    private companion object {
        val EDITING: PseudoClass = PseudoClass.getPseudoClass("editing")
    }
}
