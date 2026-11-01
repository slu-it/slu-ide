package dev.sluide.coloring.ui

import javafx.beans.Observable
import javafx.beans.binding.Bindings
import javafx.css.PseudoClass
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.text.Text
import javafx.util.Subscription

/**
 * A slot of a tool tab: its label, a swatch and a field of its color (ADR 0111). A click on the swatch (which also
 * focuses the field), a press in the field or the focus in it make it the slot the picker column edits, remembered for
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

    /** The slot's name in front of the swatch; [alignLabels] gives the labels of slots shown together one width. */
    internal val label = Label(slot.label)

    val node: Node =
        HBox(6.0, label, swatch, field).apply {
            styleClass += "color-slot"
            alignment = Pos.CENTER_LEFT
            // The ring of the slot being edited hugs the slot, also where its row is wider.
            maxWidth = Region.USE_PREF_SIZE
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

/**
 * Gives the labels of [slots] the width of the widest, so that their swatches and fields line up when the slots are
 * shown one below the other. Each slot stays its own row, so the ring of the slot being edited keeps hugging it. The
 * width follows the labels' texts and fonts.
 */
internal fun alignLabels(vararg slots: SlotView) {
    val labels = slots.map { it.label }
    val width =
        Bindings.createDoubleBinding(
            { labels.maxOf { textWidth(it) } },
            *labels
                .flatMap { listOf<Observable>(it.textProperty(), it.fontProperty(), it.paddingProperty()) }
                .toTypedArray(),
        )
    labels.forEach {
        it.minWidthProperty().bind(width)
        it.prefWidthProperty().bind(width)
    }
}

private fun textWidth(label: Label): Double =
    Math.ceil(Text(label.text).apply { font = label.font }.layoutBounds.width) +
        label.padding.left + label.padding.right
