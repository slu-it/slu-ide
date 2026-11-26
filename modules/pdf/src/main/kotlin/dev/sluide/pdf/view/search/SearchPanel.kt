package dev.sluide.pdf.view.search

import javafx.animation.PauseTransition
import javafx.collections.FXCollections
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import javafx.util.Duration

/**
 * The Search panel of the PDF view (adapted from the ebook reader's, ADR 0192): a field, a status line and the hits
 * grouped by page. A change of the query calls [onQueryChanged] at once and [onSearch] [debounce] later; Enter calls
 * [onSearch] at once. A click, or Enter, on a hit calls [onChoose] (the focus stays in the list). Escape clears the
 * field, and on an empty field calls [onClose]. FX thread only.
 */
internal class SearchPanel(
    private val onQueryChanged: () -> Unit,
    private val onSearch: (String) -> Unit,
    private val onChoose: (SearchHit) -> Unit,
    private val onClose: () -> Unit,
    debounce: Duration = Duration.millis(300.0),
) {
    val field =
        TextField().apply {
            promptText = "Search in PDF"
            styleClass += "pdf-search-field"
        }
    val status = Label().apply { styleClass += "pdf-search-status" }
    val list = ListView<SearchRow>().apply { styleClass += "pdf-search-list" }
    private val root = VBox(4.0, field, status, list)
    private val timer = PauseTransition(debounce)

    val node: Node get() = root

    /** The query in the field. */
    val query: String get() = this.field.text

    init {
        root.styleClass += "pdf-search"
        root.setMinSize(0.0, 0.0)
        VBox.setVgrow(list, Priority.ALWAYS)
        list.setCellFactory { Cell() }
        timer.setOnFinished { onSearch(field.text) }
        field.textProperty().subscribe { _, _ ->
            onQueryChanged()
            timer.playFromStart()
        }
        field.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            when (event.code) {
                KeyCode.ENTER -> {
                    timer.stop()
                    onSearch(field.text)
                }

                KeyCode.ESCAPE -> {
                    if (field.text.isNotEmpty()) field.clear() else onClose()
                }

                else -> {
                    return@addEventFilter
                }
            }
            event.consume()
        }
        list.addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (cellOf(event.target)?.item is SearchRow.Heading) event.consume()
        }
        list.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.button == MouseButton.PRIMARY && cellOf(event.target)?.item is SearchRow.Hit) choose()
        }
        list.addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ENTER) {
                choose()
                event.consume()
            }
        }
        list.selectionModel.selectedIndexProperty().subscribe { old, new ->
            if (list.items.getOrNull(new.toInt()) is SearchRow.Heading) {
                val beside = hitBesideHeading(list.items, old.toInt(), new.toInt())
                if (beside >= 0) list.selectionModel.select(beside) else list.selectionModel.clearSelection()
            }
        }
    }

    /** Focuses the field with its text selected. */
    fun focusField() {
        field.requestFocus()
        field.selectAll()
    }

    /** Stops the search of a query typed less than the debounce ago; called when the panel closes. */
    fun closed() {
        timer.stop()
    }

    fun showStatus(text: String) {
        status.text = text
    }

    fun showRows(rows: List<SearchRow>) {
        list.items = FXCollections.observableArrayList(rows)
    }

    private fun choose() {
        val row = list.selectionModel.selectedItem as? SearchRow.Hit ?: return
        onChoose(row.hit)
    }

    private fun cellOf(target: Any?): ListCell<*>? {
        var node = target as? Node
        while (node != null && node !is ListCell<*>) node = node.parent
        // An empty cell's item is null, which the callers treat as no row.
        return node as? ListCell<*>
    }

    private class Cell : ListCell<SearchRow>() {
        override fun updateItem(
            item: SearchRow?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = null
            graphic = null
            styleClass -= HEADING
            when (item) {
                is SearchRow.Heading -> {
                    text = item.title
                    styleClass += HEADING
                }

                is SearchRow.Hit -> {
                    graphic =
                        TextFlow(
                            snippetText(item.hit.before),
                            snippetText(item.hit.match).apply { styleClass += "pdf-search-match" },
                            snippetText(item.hit.after),
                        ).apply {
                            styleClass += "pdf-search-snippet"
                            prefWidthProperty().bind(this@Cell.widthProperty().subtract(24))
                        }
                }

                null -> {}
            }
        }
    }

    private companion object {
        const val HEADING = "pdf-search-heading"

        /** A plain Text has no `text` style class (only a Labeled's inner text has), so it gets its own. */
        fun snippetText(value: String) = Text(value).apply { styleClass += "pdf-search-text" }
    }
}
