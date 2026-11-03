package dev.sluide.databases.ui.console

import dev.sluide.databases.sql.QueryHistory
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.VBox
import javafx.stage.Popup

/**
 * The query history popup of a console (ADR 0121): a filter field over the statements run on the connection, newest
 * first, below the caret (or at the editor's top-left). Enter in the field or the list inserts the statement, Down
 * moves from the field into the list, Escape closes. Registers itself as the pane's [ConsolePane.showHistory].
 */
internal class HistoryPopup(
    private val pane: ConsolePane,
    private val history: QueryHistory,
    private val key: String,
) {
    private var entries: List<String> = emptyList()

    val field: TextField =
        TextField().apply {
            styleClass += "history-filter"
            promptText = "Filter"
            textProperty().addListener { _, _, _ -> rebuild() }
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                when (event.code) {
                    KeyCode.DOWN -> {
                        if (list.items.isNotEmpty()) {
                            list.requestFocus()
                            list.selectionModel.select(0)
                        }
                        event.consume()
                    }

                    KeyCode.ENTER -> {
                        event.consume()
                        insert(list.selectionModel.selectedItem ?: list.items.firstOrNull())
                    }

                    else -> {}
                }
            }
        }

    val list: ListView<String> =
        ListView<String>().apply {
            styleClass += "history-list"
            prefWidth = 520.0
            setCellFactory { HistoryCell() }
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                if (event.code == KeyCode.ENTER) {
                    event.consume()
                    insert(selectionModel.selectedItem)
                } else if (event.code == KeyCode.UP && selectionModel.selectedIndex == 0) {
                    event.consume()
                    field.requestFocus()
                }
            }
            addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
                if (event.button == MouseButton.PRIMARY) selectionModel.selectedItem?.let(::insert)
            }
        }

    private val empty =
        Label("No statements run yet").apply {
            styleClass += "history-empty"
        }

    val popup: Popup =
        Popup().apply {
            isAutoHide = true
            isHideOnEscape = true
            content +=
                VBox(field, list, empty).apply {
                    styleClass += "history-popup"
                    stylesheets +=
                        checkNotNull(HistoryPopup::class.java.getResource("console.css")).toExternalForm()
                }
        }

    init {
        pane.showHistory = ::show
    }

    /** Whether the popup is showing, for tests. */
    val isShowing: Boolean get() = popup.isShowing

    fun show() {
        val window = pane.node.scene?.window ?: return
        entries = history.entries(key)
        field.clear()
        rebuild()
        val bounds = pane.editor.caretScreenBounds()
        val corner = pane.editor.node.localToScreen(0.0, 0.0)
        val x = bounds?.minX ?: corner?.x ?: return
        val y = bounds?.maxY ?: corner?.y ?: return
        popup.show(window, x, y)
        field.requestFocus()
    }

    fun dispose() = popup.hide()

    private fun rebuild() {
        val shown = HistoryFilter.filter(entries, field.text)
        list.items.setAll(shown)
        val none = entries.isEmpty()
        empty.isVisible = none
        empty.isManaged = none
        list.isVisible = !none
        list.isManaged = !none
        list.prefHeight = minOf(shown.size, ROWS) * ROW + 2.0
    }

    private fun insert(statement: String?) {
        if (statement == null) return
        val editor = pane.editor
        val sel = editor.selection
        val edit = HistoryFilter.insertion(editor.text, sel.start, sel.end, editor.caretOffset, statement)
        popup.hide()
        editor.replace(edit.start, edit.end, edit.text)
        editor.focus()
    }

    private class HistoryCell : ListCell<String>() {
        override fun updateItem(
            item: String?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else HistoryFilter.label(item)
            tooltip = if (empty || item == null) null else Tooltip(item)
        }
    }

    private companion object {
        const val ROWS = 10
        const val ROW = 24
    }
}
