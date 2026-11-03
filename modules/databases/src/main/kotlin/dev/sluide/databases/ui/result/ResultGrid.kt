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
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import java.util.IdentityHashMap

private const val SAMPLE_ROWS = 50
private const val CHANGED_CLASS = "db-cell-changed"
private const val ADDED_CLASS = "db-cell-added"
private const val DELETED_CLASS = "db-cell-deleted"

/** Which cells and rows of the grid carry unsubmitted edits (the grid only paints them). */
internal interface CellMarks {
    fun changed(
        row: Int,
        column: Int,
    ): Boolean

    fun deleted(row: Int): Boolean

    fun added(row: Int): Boolean
}

/**
 * The grid of one result page: row numbers, one column per [ColumnInfo], cell selection and copy. Header clicks sort
 * the page on the client, or call [onServerSort] when the server sorts (data tabs). Cells are editable through
 * [onCellEdit]; [marks] paints edited cells and the cells of added and deleted rows with a style class each.
 */
internal class ResultGrid(
    private val clipboard: (String) -> Unit,
    private val onServerSort: ((ColumnInfo) -> Unit)? = null,
) {
    val table = TableView<List<Any?>>()

    /** Holds Copy and Copy with Header; more items are added after them. */
    val contextMenu = ContextMenu()

    /** Paints edited cells and rows; null: nothing is marked. */
    var marks: CellMarks? = null

    /**
     * Makes the cells editable: gets (row, data column, typed text) when Enter is pressed in a cell editor and returns
     * an error message to keep the editor open, or null when the text was taken. Null: no cell is editable.
     */
    var onCellEdit: ((Int, Int, String) -> String?)? = null

    /** Whether header clicks sort (off while edits are pending or the grid is transposed). */
    var sortEnabled = true

    private var columns: List<ColumnInfo> = emptyList()
    private var firstRow = 0
    private val dataIndex = IdentityHashMap<TableColumn<*, *>, Int>()
    private var numbersColumn: TableColumn<List<Any?>, Any?>? = null
    private var headers: List<Label> = emptyList()
    private var sortColumn = -1
    private var ascending = true

    /** Takes the text of the open cell editor (see [commitOpenEditor]); null while none is open. */
    private var openEditor: (() -> String?)? = null

    init {
        table.styleClass += "result-grid"
        table.selectionModel.isCellSelectionEnabled = true
        table.selectionModel.selectionMode = SelectionMode.MULTIPLE
        table.columnResizePolicy = TableView.UNCONSTRAINED_RESIZE_POLICY
        table.items = FXCollections.observableArrayList()
        table.placeholder = Label("")
        table.isEditable = true
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
        dataIndex.clear()
        val numbers = TableColumn<List<Any?>, Any?>("#")
        numbers.isSortable = false
        numbers.isEditable = false
        numbers.isReorderable = false
        numbersColumn = numbers
        numbers.setCellValueFactory { SimpleObjectProperty(it.value) }
        numbers.setCellFactory { RowNumberCell() }
        val built = mutableListOf<TableColumn<List<Any?>, Any?>>()
        val labels = mutableListOf<Label>()
        newColumns.forEachIndexed { i, info ->
            val column = TableColumn<List<Any?>, Any?>()
            column.isSortable = false
            column.setCellValueFactory { SimpleObjectProperty(it.value[i]) }
            column.setCellFactory { ValueCell() }
            dataIndex[column] = i
            val texts = sample.rows.take(SAMPLE_ROWS).map { CellFormat.display(it[i]) }
            column.prefWidth = GridText.columnWidth(info.label, texts)
            val header = Label(info.label)
            header.tooltip = Tooltip(info.typeName)
            header.prefWidthProperty().bind(column.widthProperty().subtract(16))
            header.setOnMouseClicked { if (it.isStillSincePress) headerClicked(i) }
            column.graphic = header
            labels += header
            built += column
        }
        headers = labels
        table.columns.setAll(listOf(numbers) + built)
        markSort()
        showPage(sample)
    }

    fun showPage(page: Page) {
        firstRow = page.firstRow
        if (onServerSort == null) sortColumn = -1
        markSort()
        numbersColumn?.prefWidth = GridText.rowNumberWidth(page.firstRow + page.rows.size)
        replaceRows(page.rows)
        table.scrollTo(0)
        table.refresh()
    }

    private fun replaceRows(rows: List<List<Any?>>) {
        val items: MutableList<List<Any?>> = table.items
        items.clear()
        // Own copies: edits change the cell lists in place ([setCell]).
        items.addAll(rows.map { ArrayList(it) })
    }

    /** Replaces the rows shown (the columns stay). */
    fun replaceAll(rows: List<List<Any?>>) {
        replaceRows(rows)
        table.refresh()
    }

    /** Sets a cell of a row shown; call [refresh] after. */
    @Suppress("UNCHECKED_CAST")
    fun setCell(
        row: Int,
        column: Int,
        value: Any?,
    ) {
        (table.items[row] as MutableList<Any?>)[column] = value
    }

    fun appendRow(row: List<Any?>) {
        table.items.add(ArrayList(row))
    }

    fun removeRow(row: Int) {
        table.items.removeAt(row)
    }

    fun refresh() = table.refresh()

    /** The column shown as data column [index]. */
    fun column(index: Int): ColumnInfo = columns[index]

    /** The rows that have a selected cell, ascending. */
    fun selectedRows(): List<Int> = selectedCells().map { it.first }.distinct().sorted()

    /** The cell with the focus as (row, data column), null on the row-number column or without focus. */
    fun focusedCell(): Pair<Int, Int>? {
        val focus = table.focusModel.focusedCell
        val column = dataIndex[focus.tableColumn] ?: return null
        return if (focus.row in 0 until table.items.size) focus.row to column else null
    }

    /** Opens the editor of a cell (when [onCellEdit] is set). */
    fun startEdit(
        row: Int,
        column: Int,
    ) {
        val target = dataIndex.entries.firstOrNull { it.value == column }?.key ?: return
        table.edit(row, target as TableColumn<List<Any?>, *>)
    }

    private fun headerClicked(index: Int) {
        if (!sortEnabled) return
        val server = onServerSort
        if (server != null) {
            // The view sets the ORDER BY and, once the data is loaded, the arrow ([setServerSort]).
            server(columns[index])
            return
        }
        sortOnClient(index, if (sortColumn == index) !ascending else true)
    }

    /** The client sort of the rows shown as (data column, ascending), null when unsorted or sorted on the server. */
    val clientSort: Pair<Int, Boolean>?
        get() = if (onServerSort == null && sortColumn >= 0) sortColumn to ascending else null

    /** Sorts the rows shown by data column [index] (a grid that sorts on the client). */
    fun sortOnClient(
        index: Int,
        ascendingOrder: Boolean,
    ) {
        if (onServerSort != null || index !in columns.indices) return
        ascending = ascendingOrder
        sortColumn = index
        replaceRows(table.items.sortedWith(ClientSort.comparator(index, ascendingOrder)))
        markSort()
    }

    /**
     * Hands the text of an open cell editor to [onCellEdit] as Enter does: null when none is open or the text was
     * taken (the editor closes), else the error message (the editor stays open).
     */
    fun commitOpenEditor(): String? {
        if (table.editingCell == null) openEditor = null
        return openEditor?.invoke()
    }

    /** The arrow of a data tab: the column the ORDER BY sorts by, if it is exactly one column's sort (null: none). */
    fun setServerSort(sort: Pair<Int, Boolean>?) {
        sortColumn = sort?.first ?: -1
        ascending = sort?.second ?: true
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
            val c = dataIndex[pos.tableColumn]
            if (c == null) null else pos.row to c
        }

    /** The columns and rows of the selected cells' rectangle as shown, null without a selection. */
    fun selectionRectangle(): Pair<List<ColumnInfo>, List<List<Any?>>>? {
        val cells = selectedCells()
        if (cells.isEmpty()) return null
        return GridText.rectangle(columns, table.items, cells, table.columns.mapNotNull { dataIndex[it] })
    }

    /** The selected cells as TSV (see [GridText.copyText]). */
    fun copyText(withHeader: Boolean): String {
        val visible = table.columns.mapNotNull { dataIndex[it] }
        return GridText.copyText(columns, table.items, selectedCells(), withHeader, visible)
    }

    fun copy(withHeader: Boolean) {
        val text = copyText(withHeader)
        if (text.isNotEmpty()) clipboard(text)
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
            markRow(empty)
            text = if (empty || index < 0) null else (firstRow + index + 1).toString()
        }
    }

    /** The cells of an added or deleted row carry a class each, so that every rule is a single-class selector. */
    private fun TableCell<*, *>.markRow(empty: Boolean) {
        styleClass.removeAll(ADDED_CLASS, DELETED_CLASS)
        val m = marks
        if (empty || m == null || index < 0) return
        if (m.deleted(index)) styleClass += DELETED_CLASS
        if (m.added(index)) styleClass += ADDED_CLASS
    }

    private inner class ValueCell : TableCell<List<Any?>, Any?>() {
        override fun startEdit() {
            val m = marks
            if (onCellEdit == null || (m != null && m.deleted(index))) return
            super.startEdit()
            if (!isEditing) return
            val field = TextField(CellFormat.full(item))
            val commit = {
                val column = dataIndex[tableColumn]
                val message = if (column == null) null else onCellEdit?.invoke(index, column, field.text)
                if (message == null) cancelEdit()
                message
            }
            openEditor = commit
            field.setOnAction { commit() }
            field.setOnKeyPressed {
                if (it.code == KeyCode.ESCAPE) {
                    cancelEdit()
                    it.consume()
                }
            }
            text = null
            graphic = field
            field.selectAll()
            field.requestFocus()
        }

        override fun cancelEdit() {
            openEditor = null
            super.cancelEdit()
            graphic = null
            text = if (isEmpty) null else CellFormat.display(item)
            table.requestFocus()
        }

        override fun updateItem(
            item: Any?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            styleClass.removeAll("null-cell", CHANGED_CLASS)
            markRow(empty)
            if (empty) {
                text = null
                graphic = null
                return
            }
            if (isEditing) return
            graphic = null
            text = CellFormat.display(item)
            if (item == null) styleClass += "null-cell"
            val column = dataIndex[tableColumn]
            if (column != null && marks?.changed(index, column) == true) styleClass += CHANGED_CLASS
        }
    }
}
