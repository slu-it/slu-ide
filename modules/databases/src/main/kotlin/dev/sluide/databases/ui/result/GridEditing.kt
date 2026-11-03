package dev.sluide.databases.ui.result

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.result.CellFormat
import dev.sluide.databases.result.DataEdits
import dev.sluide.databases.result.Editabilities
import dev.sluide.databases.result.Editability
import dev.sluide.databases.result.TableRef
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.session.DbSession
import javafx.beans.InvalidationListener
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import org.kordamp.ikonli.javafx.FontIcon
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** What the editing of a grid needs of the result view it belongs to. */
internal interface EditingView {
    val columns: List<ColumnInfo>

    /** A line in the toolbar status, red when [error]. */
    fun say(
        text: String,
        error: Boolean,
    )

    /** The pending state or the editability changed: paging, sorting and the filter fields follow it. */
    fun editsChanged()

    /** Whether a page or reload is being loaded: editing waits for it. */
    val busy: Boolean

    /** Whether the statement is a plain query (what changed data is not editable). */
    val isQuery: Boolean

    /** Loads the page again; [note] is the status afterwards. */
    fun reload(note: String)
}

/**
 * Editing, the value viewer and the toolbar buttons for them, for one result view (ADR 0121, "Editing"). Editability
 * is worked out after each load: one table, a primary key in the result, a connection that is not read-only. The
 * [DataEdits] are created at the first edit from the rows shown and dropped when nothing is pending, so they always
 * match the grid's row order; every edit is mirrored into the grid's rows, which [marks] paints.
 */
internal class GridEditing(
    private val host: ResultHost,
    private val grid: ResultGrid,
    private val view: EditingView,
    private val primaryKey: (DbSession, TableRef) -> CompletableFuture<List<String>>,
) : CellMarks {
    /** Shows the value viewer; null: nothing to apply. Tests replace it. */
    var showValue: (ValueRequest) -> String? = {
        ValueViewer.show(it, grid.table.scene?.window, host::copyToClipboard)
    }

    /** Asks before DELETEs that are committed at once (rows, table); tests replace it. */
    var confirmDelete: (Int, String) -> Boolean = { count, table ->
        EditQuestions.delete(grid.table.scene?.window, count, table)
    }

    private var edits: DataEdits? = null
    private var verdict: Editability? = null
    private var tableRef: TableRef? = null
    private var token = 0
    private var transposed = false
    private var submitting = false

    private val addRow = button("mdi2t-table-row-plus-after", "Add Row") { addRow() }
    private val delete = button("mdi2t-table-row-remove", "Delete Rows") { deleteSelected() }
    private val setNull = button("mdi2n-null", "Set NULL") { setSelectedNull() }
    private val revert = button("mdi2u-undo", "Revert") { revertSelected() }
    private val submit = button("mdi2c-check-bold", "Submit (Shortcut+Enter)") { submit() }
    private val lock =
        Label().apply {
            graphic = FontIcon("mdi2l-lock-outline")
            styleClass += "result-lock"
        }

    /** The nodes for the toolbar, after Reload. */
    val actions: List<Node> = listOf(addRow, delete, setNull, revert, submit, lock)

    private val menuItems =
        listOf(
            item("Add Row") { addRow() },
            item("Delete Rows") { deleteSelected() },
            item("Set NULL") { setSelectedNull() },
            item("Revert") { revertSelected() },
            item("Submit") { submit() },
        )

    /** The value viewer's context-menu item. */
    private val openValue = item("Open Value…") { openValue() }

    val editable: Boolean get() = verdict is Editability.Editable && !transposed

    /** A page or reload loads, or a submit runs: nothing is edited meanwhile. */
    private val locked: Boolean get() = view.busy || submitting

    val pending: Boolean get() = edits?.pending == true

    /** The qualified table the result comes from, else null. */
    val tableName: String? get() = tableRef?.qualified

    init {
        grid.marks = this
        grid.contextMenu.items += SeparatorMenuItem()
        grid.contextMenu.items += menuItems
        grid.contextMenu.items += SeparatorMenuItem()
        grid.contextMenu.items += openValue
        grid.table.selectionModel.selectedCells
            .addListener(InvalidationListener { refreshControls() })
        val submitKey = KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN)
        val valueKey = KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHIFT_DOWN)
        val editEnter = KeyCodeCombination(KeyCode.ENTER)
        val editF2 = KeyCodeCombination(KeyCode.F2)
        grid.table.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (grid.table.editingCell != null) return@addEventFilter
            val handled =
                when {
                    submitKey.match(event) -> {
                        editable.also { if (it) submit() }
                    }

                    valueKey.match(event) -> {
                        true.also { openValue() }
                    }

                    editEnter.match(event) || editF2.match(event) -> {
                        editable.also { if (it && !locked) startEditing() }
                    }

                    else -> {
                        false
                    }
                }
            if (handled) event.consume()
        }
        refreshControls()
    }

    private fun button(
        icon: String,
        tip: String,
        action: () -> Unit,
    ) = Button().apply {
        graphic = FontIcon(icon)
        styleClass += "result-button"
        isFocusTraversable = false
        tooltip = Tooltip(tip)
        setOnAction { action() }
    }

    private fun item(
        text: String,
        action: () -> Unit,
    ) = MenuItem(text).apply { setOnAction { action() } }

    // CellMarks

    override fun changed(
        row: Int,
        column: Int,
    ): Boolean = edits?.let { row < it.rowCount && it.isChanged(row, column) } == true

    override fun deleted(row: Int): Boolean = edits?.let { row < it.rowCount && it.isDeleted(row) } == true

    override fun added(row: Int): Boolean = edits?.let { row < it.rowCount && it.isAdded(row) } == true

    // State

    /** New content: works out the editability of [columns] (the key is read on the metadata connection). */
    fun onLoad(columns: List<ColumnInfo>) {
        val mine = ++token
        edits = null
        transposed = false
        verdict = null
        tableRef = null
        val session = host.entry.session.get()
        if (session == null) {
            verdict = Editability.ReadOnly("Not connected")
            apply()
            return
        }
        val kind = session.config.kind
        val ref = Editabilities.singleTable(kind, columns)
        tableRef = ref
        if (!view.isQuery) {
            verdict = Editability.ReadOnly(STATEMENT_CHANGED_DATA)
            apply()
            return
        }
        if (ref == null || session.config.readOnly) {
            verdict = Editabilities.check(kind, columns, emptyList(), session.config.readOnly)
            apply()
            return
        }
        apply()
        val lookup =
            try {
                primaryKey(session, ref)
            } catch (e: RuntimeException) {
                CompletableFuture.failedFuture(e)
            }
        lookup.whenComplete { key, error ->
            host.fx {
                if (mine != token) return@fx
                verdict =
                    if (error != null) {
                        Editability.ReadOnly("The primary key could not be read: ${message(error)}")
                    } else {
                        Editabilities.check(kind, columns, key, session.config.readOnly)
                    }
                apply()
            }
        }
    }

    /** Another page is shown: edits belonged to the old rows. */
    fun onPage() {
        edits = null
        refreshControls()
    }

    /** Editing is off while the grid shows the transposed rows. */
    fun setTransposed(on: Boolean) {
        transposed = on
        apply()
    }

    private fun apply() {
        refreshControls()
        view.editsChanged()
    }

    private fun changedState() {
        if (edits?.pending != true) edits = null
        refreshControls()
        view.editsChanged()
    }

    /** The lock changed (a load started or ended): the actions and the cells follow it. */
    fun refreshControls() {
        grid.onCellEdit = if (editable && !locked) ::commitCell else null
        val on = editable
        val readOnly = verdict as? Editability.ReadOnly
        for (node in actions.dropLast(1)) {
            node.isVisible = on
            node.isManaged = on
        }
        lock.isVisible = readOnly != null
        lock.isManaged = readOnly != null
        lock.tooltip = readOnly?.let { Tooltip(it.reason) }
        val selected = grid.selectedRows().isNotEmpty()
        val rows = !locked && selected
        addRow.isDisable = locked
        delete.isDisable = !rows
        setNull.isDisable = !rows
        revert.isDisable = !rows || edits == null
        submit.isDisable = locked || !pending
        val disabled = listOf(addRow.isDisable, delete.isDisable, setNull.isDisable, revert.isDisable, submit.isDisable)
        menuItems.forEachIndexed { i, menuItem ->
            menuItem.isVisible = on
            menuItem.isDisable = disabled[i]
        }
        grid.sortEnabled = !transposed && !pending
    }

    // Cell edits

    /** The originals are copies: the grid's own row lists change with every edit. */
    private fun ensureEdits(): DataEdits =
        edits
            ?: DataEdits(
                verdict as Editability.Editable,
                view.columns,
                grid.rows.map { it.toList() },
            ).also { edits = it }

    /** Parses [text] for the column and sets the cell; an error message when it does not parse. */
    private fun commitCell(
        row: Int,
        column: Int,
        text: String,
    ): String? {
        val message = setCell(row, column, text)
        if (message != null) view.say(message, true)
        return message
    }

    private fun setCell(
        row: Int,
        column: Int,
        text: String,
    ): String? {
        val data = ensureEdits()
        if (data.isDeleted(row)) return "Row ${row + 1} is deleted"
        if (text == CellFormat.full(grid.rows[row][column])) return null
        val value =
            try {
                CellFormat.parse(text, view.columns[column])
            } catch (e: IllegalArgumentException) {
                return e.message ?: "Not a valid value"
            }
        data.edit(row, column, value)
        grid.setCell(row, column, data.value(row, column))
        grid.refresh()
        changedState()
        return null
    }

    private fun startEditing() {
        val cell = grid.focusedCell() ?: grid.selectedCells().firstOrNull() ?: return
        grid.startEdit(cell.first, cell.second)
    }

    private fun addRow() {
        if (locked) return
        val data = ensureEdits()
        val index = data.addRow()
        grid.appendRow(List(view.columns.size) { null })
        grid.refresh()
        grid.table.scrollTo(index)
        changedState()
    }

    private fun deleteSelected() {
        if (locked) return
        val rows = grid.selectedRows()
        val data = ensureEdits()
        val added = rows.filter { data.isAdded(it) }
        data.deleteRows(rows)
        added.sortedDescending().forEach(grid::removeRow)
        grid.table.selectionModel.clearSelection()
        grid.refresh()
        changedState()
    }

    private fun setSelectedNull() {
        if (locked) return
        val data = ensureEdits()
        for ((row, column) in grid.selectedCells()) {
            if (data.isDeleted(row)) continue
            data.edit(row, column, null)
            grid.setCell(row, column, null)
        }
        grid.refresh()
        changedState()
    }

    private fun revertSelected() {
        if (locked) return
        val data = edits ?: return
        val rows = grid.selectedRows()
        val added = rows.filter { data.isAdded(it) }
        data.revert(rows)
        for (row in rows - added.toSet()) {
            for (column in view.columns.indices) grid.setCell(row, column, data.value(row, column))
        }
        added.sortedDescending().forEach(grid::removeRow)
        grid.table.selectionModel.clearSelection()
        grid.refresh()
        changedState()
    }

    // Value viewer

    private fun openValue() {
        val cell = grid.focusedCell() ?: grid.selectedCells().firstOrNull() ?: return
        val (row, column) = cell
        val request =
            ValueRequest(
                grid.column(column).label,
                CellFormat.full(grid.rows[row][column]),
                editable && !locked && !deleted(row),
            )
        val text = showValue(request) ?: return
        if (request.editable && !locked) commitCell(row, column, text)
    }

    // Submit

    private fun submit() {
        val data = edits ?: return
        if (!data.pending || locked) return
        val session = host.entry.session.get()
        if (session == null) {
            view.say("Not connected", true)
            return
        }
        val statements =
            try {
                data.statements()
            } catch (e: IllegalStateException) {
                fail(e.message ?: "The edits cannot be submitted")
                return
            }
        val deletes = statements.count { it.sql.startsWith("DELETE") }
        if (deletes > 0 && idle(session) && !confirmDelete(deletes, tableName ?: "the table")) return
        val manual = session.commitMode == CommitMode.MANUAL
        submitting = true
        refreshControls()
        session
            .withSession(changes = true) { conn -> EditSubmitter.submit(conn, statements, idle(session)) }
            .whenComplete { _, error ->
                host.fx {
                    submitting = false
                    if (error != null) {
                        fail(message(error))
                    } else {
                        succeeded(data, statements.size, manual)
                    }
                }
            }
    }

    /** Auto mode without a transaction of the user: a submit commits at once (decided on the session's thread). */
    private fun idle(session: DbSession): Boolean = session.commitMode == CommitMode.AUTO && !session.pendingTransaction

    /** The data is loaded again, so that what the database computed shows (also the page the cursor cached). */
    private fun succeeded(
        data: DataEdits,
        count: Int,
        manual: Boolean,
    ) {
        val needsRefresh = data.accepted().needsRefresh
        edits = null
        val text =
            "Submitted $count ${if (count == 1) "change" else "changes"}" + if (manual) " (not committed yet)" else ""
        host.log(text, false)
        refreshControls()
        view.reload(if (needsRefresh) "Reloaded to show values the database filled in" else text)
    }

    private fun fail(text: String) {
        host.log("Submit failed: $text", true)
        view.say(text, true)
        refreshControls()
    }

    private fun message(error: Throwable): String {
        val cause = if (error is CompletionException && error.cause != null) error.cause!! else error
        return cause.message ?: cause.javaClass.simpleName
    }

    private companion object {
        const val STATEMENT_CHANGED_DATA = "The statement changed data; run a SELECT to edit"
    }
}
