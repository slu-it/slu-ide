package dev.sluide.translation.ui

import javafx.scene.control.Button
import javafx.scene.control.ContentDisplay
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.VBox
import javafx.stage.Popup
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignM

/** One entry of a [ChoicePicker]: the [value] it picks and the [label] it shows and is searched by. */
internal data class Choice<T>(
    val value: T,
    val label: String,
)

/** The choices whose label contains [query], ignoring case and surrounding blanks; all of them for a blank query. */
internal fun <T> List<Choice<T>>.matching(query: String): List<Choice<T>> {
    val wanted = query.trim()
    return if (wanted.isEmpty()) this else filter { it.label.contains(wanted, ignoreCase = true) }
}

/**
 * A drop-down of [Choice]s with a search field: the button shows the current choice; clicking it opens a popup with a
 * text field and the list, and typing filters the list by name. Enter picks the selected (else the first) entry, the
 * arrow keys move the selection, Escape closes. [onPick] gets the picked value.
 */
internal class ChoicePicker<T>(
    id: String,
    tip: String,
    private val choices: List<Choice<T>>,
    private val onPick: (T) -> Unit,
) {
    private var current: T? = null
    private val field = TextField().apply { promptText = "Search" }
    private val list =
        ListView<Choice<T>>().apply {
            this.id = "$id-list"
            setPrefSize(220.0, 260.0)
            setCellFactory { LabelCell(::pick) }
        }
    private val popup =
        Popup().apply {
            isAutoHide = true
            content +=
                VBox(4.0, field, list).apply {
                    styleClass += "translate-picker-popup"
                    stylesheets += TranslateView.STYLESHEET
                }
            setOnShown { field.requestFocus() }
        }

    val node: Button =
        Button(null, FontIcon(MaterialDesignM.MENU_DOWN)).apply {
            this.id = id
            styleClass += "translate-button"
            contentDisplay = ContentDisplay.RIGHT
            isFocusTraversable = false
            tooltip = Tooltip(tip)
            setOnAction { open() }
        }

    init {
        field.id = "$id-search"
        field.textProperty().subscribe { query ->
            list.items.setAll(choices.matching(query))
            select(0)
        }
        field.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            when (event.code) {
                KeyCode.ENTER -> pickSelected()
                KeyCode.ESCAPE -> popup.hide()
                KeyCode.DOWN -> select(list.selectionModel.selectedIndex + 1)
                KeyCode.UP -> select(list.selectionModel.selectedIndex - 1)
                else -> return@addEventFilter
            }
            event.consume()
        }
    }

    /** Shows [shown] on the button; the list opens on [value]. */
    fun show(
        shown: String,
        value: T?,
    ) {
        node.text = shown
        current = value
    }

    private fun open() {
        field.clear()
        list.items.setAll(choices)
        select(choices.indexOfFirst { it.value == current }.coerceAtLeast(0))
        val bounds = node.localToScreen(node.boundsInLocal) ?: return
        popup.show(node, bounds.minX, bounds.maxY)
    }

    private fun select(index: Int) {
        if (list.items.isEmpty()) return
        val clamped = index.coerceIn(0, list.items.size - 1)
        list.selectionModel.select(clamped)
        list.scrollTo(clamped)
    }

    private fun pickSelected() {
        (list.selectionModel.selectedItem ?: list.items.firstOrNull())?.let(::pick)
    }

    private fun pick(choice: Choice<T>) {
        popup.hide()
        onPick(choice.value)
    }

    private class LabelCell<T>(
        private val pick: (Choice<T>) -> Unit,
    ) : ListCell<Choice<T>>() {
        init {
            // On the cell, so that a click on the scrollbar or the empty part of the list picks nothing.
            setOnMouseClicked { event ->
                val choice = item
                if (!isEmpty && choice != null && event.button == MouseButton.PRIMARY) pick(choice)
            }
        }

        override fun updateItem(
            item: Choice<T>?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else item.label
        }
    }
}
