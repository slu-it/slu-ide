package dev.sluide.databases.ui.result

import dev.sluide.databases.result.CellFormat
import dev.sluide.databases.result.ClientSort
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.session.Page
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent

private const val SAMPLE_ROWS = 50

/**
 * The grid of one result page: row numbers, one column per [ColumnInfo], cell selection and copy. Header clicks sort
 * the page on the client, or call [onServerSort] when the server sorts (data tabs). Editing, export and the value
 * viewer of the next package hook in through [table] and [contextMenu].
 */
internal class ResultGrid(
    private val clipboard: (String) -> Unit,
    private val onServerSort: ((ColumnInfo) -> Unit)? = null,
) {
    val table = TableView<List<Any?>>()

    /** Holds Copy and Copy with Header; more items are added after them. */
    val contextMenu = ContextMenu()

    private var columns: List<ColumnInfo> = emptyList()
    private var firstRow = 0
    private var dataColumns: List<TableColumn<List<Any?>, Any?>> = emptyList()
    private var headers: List<Label> = emptyList()
    private var sortColumn = -1
    private var ascending = true

    init {
        table.styleClass += "result-grid"
        table.selectionModel.isCellSelectionEnabled = true
        table.selectionModel.selectionMode = SelectionMode.MULTIPLE
        table.columnResizePolicy = TableView.UNCONSTRAINED_RESIZE_POLICY
        table.items = FXCollections.observableArrayList()
        table.placeholder = Label("")
        val copy = MenuItem("Copy").apply { setOnAction { copy(false) } }
        val copyWithHeader = MenuItem("Copy with Header").apply { setOnAction { copy(true) } }
        contextMenu.items.addAll(copy, copyWithHeader)
        table.contextMenu = contextMenu
        val copyKey = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)
        table.addEventFilter(KeyEvent.KEY_PRESSED) {
            if (copyKey.match(it)) {
                copy(false)
                it.consume()
            }
        }
    }

    /** The rows of the current page, in display order. */
    val rows: List<List<Any?>> get() = table.items

    /** Rebuilds the columns for [newColumns]; [sample] sizes them. */
    fun setColumns(
        newColumns: List<ColumnInfo>,
        sample: Page,
    ) {
        columns = newColumns
        sortColumn = -1
        val numbers = TableColumn<List<Any?>, Any?>("#")
        numbers.isSortable = false
        numbers.isReorderable = false
        numbers.prefWidth = GridText.rowNumberWidth(100_000)
        numbers.setCellValueFactory { SimpleObjectProperty(it.value) }
        numbers.setCellFactory { RowNumberCell() }
        val built = mutableListOf<TableColumn<List<Any?>, Any?>>()
        val labels = mutableListOf<Label>()
        newColumns.forEachIndexed { i, info ->
            val column = TableColumn<List<Any?>, Any?>()
            column.isSortable = false
            column.setCellValueFactory { SimpleObjectProperty(it.value[i]) }
            column.setCellFactory { ValueCell() }
            val texts = sample.rows.take(SAMPLE_ROWS).map { CellFormat.display(it[i]) }
            column.prefWidth = GridText.columnWidth(info.label, texts)
            val header = Label(info.label)
            header.tooltip = Tooltip(info.typeName)
            header.prefWidthProperty().bind(column.widthProperty().subtract(16))
            header.setOnMouseClicked { headerClicked(i) }
            column.graphic = header
            labels += header
            built += column
        }
        headers = labels
        dataColumns = built
        table.columns.setAll(listOf(numbers) + built)
        showPage(sample)
    }

    fun showPage(page: Page) {
        firstRow = page.firstRow
        sortColumn = -1
        markSort()
        replaceRows(page.rows)
        table.scrollTo(0)
        table.refresh()
    }

    private fun replaceRows(rows: List<List<Any?>>) {
        val items: MutableList<List<Any?>> = table.items
        items.clear()
        items.addAll(rows)
    }

    private fun headerClicked(index: Int) {
        val server = onServerSort
        if (server != null) {
            server(columns[index])
            return
        }
        ascending = if (sortColumn == index) !ascending else true
        sortColumn = index
        replaceRows(table.items.sortedWith(ClientSort.comparator(index, ascending)))
        markSort()
    }

    private fun markSort() {
        headers.forEachIndexed { i, label ->
            label.text = columns[i].label +
                if (i != sortColumn) {
                    ""
                } else if (ascending) {
                    " ▲"
                } else {
                    " ▼"
                }
        }
    }

    /** The selected cells as (row, data column) pairs; the row-number column is left out. */
    fun selectedCells(): List<Pair<Int, Int>> =
        table.selectionModel.selectedCells.mapNotNull { pos ->
            val c = dataColumns.indexOfFirst { it === pos.tableColumn }
            if (c < 0) null else pos.row to c
        }

    /** The selected cells as TSV (see [GridText.copyText]). */
    fun copyText(withHeader: Boolean): String = GridText.copyText(columns, table.items, selectedCells(), withHeader)

    fun copy(withHeader: Boolean) {
        val text = copyText(withHeader)
        if (text.isNotEmpty() || withHeader) clipboard(text)
    }

    private inner class RowNumberCell : TableCell<List<Any?>, Any?>() {
        init {
            styleClass += "row-number"
            isFocusTraversable = false
        }

        override fun updateIndex(i: Int) {
            super.updateIndex(i)
            text = if (i in 0 until table.items.size) (firstRow + i + 1).toString() else null
        }

        override fun updateItem(
            item: Any?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = if (empty || index < 0) null else (firstRow + index + 1).toString()
        }
    }

    private class ValueCell : TableCell<List<Any?>, Any?>() {
        override fun updateItem(
            item: Any?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            styleClass.remove("null-cell")
            if (empty) {
                text = null
                return
            }
            text = CellFormat.display(item)
            if (item == null) styleClass += "null-cell"
        }
    }
}
