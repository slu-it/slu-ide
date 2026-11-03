package dev.sluide.databases.ui.result

import dev.sluide.databases.result.PageState
import dev.sluide.databases.result.TableDataQuery
import dev.sluide.databases.result.TableRef
import dev.sluide.databases.result.Transpose
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.session.DbSession
import dev.sluide.databases.session.Page
import dev.sluide.databases.session.RowCursor
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.sql.SqlSplitter
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** What a result view needs from the area it sits in. */
internal interface ResultHost : ExportHost {
    fun pageSize(): Int

    fun logOutcome(outcome: StatementOutcome)

    fun log(
        text: String,
        error: Boolean,
    )

    /** The primary key columns of [ref] (empty: none), read on the metadata connection. */
    fun primaryKey(
        session: DbSession,
        ref: TableRef,
    ): CompletableFuture<List<String>>
}

/**
 * The content of one result tab: the filter row (data tabs), the paging bar and the grid. [load] replaces what it
 * shows; answers of page loads and reloads that belong to older content are ignored (a generation counter).
 */
internal class ResultView(
    private val host: ResultHost,
    val dataRef: TableRef?,
    private val onColumns: (List<ColumnInfo>) -> Unit,
) {
    private val whereField = TextField().apply { promptText = "WHERE" }
    private val orderByField = TextField().apply { promptText = "ORDER BY" }
    private val grid =
        ResultGrid(
            host::copyToClipboard,
            onServerSort = if (dataRef == null) null else { column -> sortOnServer(dataRef, column) },
        )
    private val bar =
        PagingBar(
            PagingActions(
                first = { fetch(cursor?.page(0)) },
                previous = { page?.let { p -> fetch(cursor?.page(p.index - 1)) } },
                next = { page?.let { p -> fetch(cursor?.page(p.index + 1)) } },
                last = { fetch(cursor?.lastPage(host.fetchAllCap())) },
                reload = { reload() },
                pageSize = { applyPageSize(it) },
            ),
        )

    private val viewControls =
        object : EditingView, ExportSource {
            override val columns: List<ColumnInfo> get() = currentColumns
            override val cursor: RowCursor? get() = this@ResultView.cursor
            override val tableName: String? get() = editing.tableName
            override val transposed: Boolean get() = this@ResultView.transposed
            override val busy: Boolean get() = this@ResultView.busy
            override val isQuery: Boolean get() = reloadAllowed()

            override fun selection(): Pair<List<ColumnInfo>, List<List<Any?>>>? = grid.selectionRectangle()

            override fun say(
                text: String,
                error: Boolean,
            ) {
                status = text
                statusError = error
                refreshBar()
            }

            override fun editsChanged() {
                refreshBar()
            }

            override fun reload(note: String) {
                this@ResultView.reload(note, keepView = true)
            }
        }
    private val editing: GridEditing = GridEditing(host, grid, viewControls, host::primaryKey)
    private val export = ExportMenu(host, viewControls)
    private val transpose = TransposeToggle(::setTransposed)

    /** Set by an edit submit that reloads: the status after the new load. */
    private var afterLoad: String? = null

    /** What a reload after a submit shows again: the page index and the client sort of the grid. */
    private data class Shown(
        val pageIndex: Int,
        val sort: Pair<Int, Boolean>?,
    )

    /**
     * Counts the contents and requests of this view: a page, reload or resize answer that finds a newer number was
     * superseded (by a load, a close or another request) and is dropped; a superseded reload closes its cursor.
     */
    private var generation = 0
    private var cursor: RowCursor? = null
    private var page: Page? = null
    private var sql = ""
    private var millis = 0L
    private var busy = false
    private var transposed = false
    private var status = ""
    private var statusError = false
    private var closed = false

    /** Unsubmitted edits: the tab is kept, and paging, sorting, Reload and the filter are off. */
    val pendingEdits: Boolean get() = editing.pending

    /** A submit of the edits runs: the tab is not closed until it ends. */
    val submitRunning: Boolean get() = editing.submitRunning

    /** A line in the status of the toolbar, red when [error]. */
    fun say(
        text: String,
        error: Boolean,
    ) = viewControls.say(text, error)

    /** The editing controller (tests replace its value viewer). */
    internal val gridEditing: GridEditing get() = editing

    val node =
        VBox().apply {
            styleClass += "result-view"
            if (dataRef != null) children += filterRow()
            children += bar.node
            children += grid.table
            VBox.setVgrow(grid.table, Priority.ALWAYS)
        }

    /** The grid, for tests. */
    internal val resultGrid: ResultGrid get() = grid

    internal val pagingBar: PagingBar get() = bar

    internal val currentCursor: RowCursor? get() = cursor

    internal val currentColumns: List<ColumnInfo> get() = cursor?.columns ?: emptyList()

    internal val whereText: String get() = whereField.text

    internal val orderByText: String get() = orderByField.text

    private fun filterRow(): HBox {
        for (field in listOf(whereField, orderByField)) {
            field.isFocusTraversable = true
            field.setOnAction { reload() }
            HBox.setHgrow(field, Priority.ALWAYS)
        }
        return HBox(
            4.0,
            Label("WHERE"),
            whereField,
            Label("ORDER BY"),
            orderByField,
        ).apply {
            styleClass += "result-filter"
            alignment = Pos.CENTER_LEFT
            padding = Insets(2.0, 6.0, 2.0, 6.0)
        }
    }

    init {
        editing.actions.forEach(bar::addAction)
        bar.addAction(transpose.node)
        bar.addAction(export.button)
        grid.contextMenu.items += Menu("Copy as").apply { items.setAll(export.copyMenus()) }
        grid.contextMenu.items += export.scratchMenu("Open as Scratch File")
        refreshBar()
    }

    private fun setTransposed(on: Boolean) {
        transposed = on
        editing.setTransposed(on)
        refreshBar()
        val shown = page ?: return
        if (on) {
            val (columns, rows) = Transpose.of(currentColumns, grid.rows)
            // The columns are the rows of the page: numbered as in the row-number column of the page.
            val numbered =
                columns.mapIndexed { i, column ->
                    if (i == 0) column else column.copy(name = "${shown.firstRow + i}", label = "${shown.firstRow + i}")
                }
            grid.setColumns(numbered, Page(0, 0, rows, true))
        } else {
            grid.setColumns(currentColumns, shown)
            if (dataRef != null) {
                grid.setServerSort(ResultTabsLogic.sortOf(dataRef.kind, orderByField.text, currentColumns))
            }
        }
    }

    /** Shows [rows] (the cursor of the previous content is closed). */
    fun load(rows: StatementOutcome.Rows) {
        generation++
        if (closed) {
            rows.cursor.close()
            return
        }
        val old = cursor
        if (old !== rows.cursor) old?.close()
        cursor = rows.cursor
        sql = rows.sql
        millis = rows.millis
        busy = false
        transposed = false
        statusError = false
        onColumns(rows.columns)
        bar.pageField.text = rows.cursor.pageSize.toString()
        bar.pageField.promptText = host.pageSize().toString()
        transpose.reset()
        grid.setColumns(rows.columns, rows.firstPage)
        editing.onLoad(rows.columns)
        if (dataRef != null) grid.setServerSort(ResultTabsLogic.sortOf(dataRef.kind, orderByField.text, rows.columns))
        show(rows.firstPage, fill = false)
    }

    private fun show(
        newPage: Page,
        fill: Boolean = true,
    ) {
        page = newPage
        if (fill) grid.showPage(newPage)
        busy = false
        statusError = false
        val c = cursor
        status = afterLoad ?: ResultTabsLogic.status(millis, c?.reExecuted == true, c?.capped == true)
        afterLoad = null
        editing.onPage()
        refreshBar()
    }

    private fun refreshBar() {
        val c = cursor
        val p = page
        val state =
            if (c == null || p == null) {
                null
            } else {
                PageState(c.pageSize, p.index, p.rows.size, p.last, c.knownRowCount, c.capped)
            }
        val pending = editing.pending
        val lockTip =
            when {
                pending -> ResultTabsLogic.PENDING_TIP
                transposed -> ResultTabsLogic.TRANSPOSED_TIP
                else -> null
            }
        for (field in listOf(whereField, orderByField)) {
            field.isDisable = busy || lockTip != null
            field.tooltip = lockTip?.let { Tooltip(it) }
        }
        transpose.lock(pending)
        editing.refreshControls()
        bar.update(
            state,
            busy,
            if (busy) "Loading…" else status,
            statusError && !busy,
            reloadAllowed(),
            lockTip,
        )
    }

    /** A console tab does not run again what changes data; a data tab only ever runs its SELECT. */
    private fun reloadAllowed(): Boolean =
        dataRef != null ||
            SqlSplitter.isQuery(
                sql,
                host.entry.config
                    .get()
                    .kind,
            )

    private fun fail(
        message: String,
        logIt: Boolean = true,
    ) {
        busy = false
        afterLoad = null
        status = message
        statusError = true
        if (logIt) host.log(message, true)
        refreshBar()
    }

    /**
     * Shows the page [future] gives; [adopt] becomes the cursor only once it did (a new page size); [then] runs after
     * the page is shown.
     */
    private fun fetch(
        future: CompletableFuture<Page>?,
        adopt: RowCursor? = null,
        then: ((Page) -> Unit)? = null,
    ) {
        if (future == null || closed) return
        val mine = ++generation
        busy = true
        refreshBar()
        future.whenComplete { result, error ->
            host.fx {
                if (mine != generation) return@fx
                if (error != null) {
                    fail("Page failed: ${message(error)}")
                    showPageSize()
                } else {
                    if (adopt != null) cursor = adopt
                    showPageSize()
                    show(result)
                    then?.invoke(result)
                }
            }
        }
    }

    /** The field shows the page size of the cursor in use. */
    private fun showPageSize() {
        cursor?.let { bar.pageField.text = it.pageSize.toString() }
    }

    private fun message(error: Throwable): String {
        val cause = if (error is CompletionException && error.cause != null) error.cause!! else error
        return cause.message ?: cause.javaClass.simpleName
    }

    private fun applyPageSize(text: String) {
        val size = ResultTabsLogic.parsePageSize(text, host.pageSize())
        val c = cursor
        if (size == null) {
            status = "Page size must be a number"
            statusError = true
            showPageSize()
            refreshBar()
            return
        }
        if (c == null) return
        val resized = c.withPageSize(size)
        fetch(resized.page(0), adopt = resized)
    }

    private fun currentSql(): String =
        if (dataRef !=
            null
        ) {
            TableDataQuery.select(dataRef, whereText, orderByText)
        } else {
            sql
        }

    private fun sortOnServer(
        ref: TableRef,
        column: ColumnInfo,
    ) {
        orderByField.text = ResultTabsLogic.nextOrderBy(ref.kind, orderByField.text, column.name)
        reload()
    }

    /**
     * Runs the statement again (a data tab: its SELECT with the WHERE and ORDER BY of the fields). [keepView] (after a
     * submit) shows the page index shown before again, and the client sort.
     */
    fun reload(
        note: String? = null,
        keepView: Boolean = false,
    ) {
        if (closed || pendingEdits || transposed || !reloadAllowed()) return
        afterLoad = note
        val before = if (keepView) Shown(page?.index ?: 0, grid.clientSort) else null
        val session = host.entry.session.get()
        if (session == null) {
            fail("Not connected")
            return
        }
        val statement = currentSql()
        if (statement.isBlank()) return
        val size = ResultTabsLogic.parsePageSize(bar.pageField.text, host.pageSize()) ?: host.pageSize()
        val mine = ++generation
        busy = true
        refreshBar()
        session.execute(statement, size).whenComplete { outcome, error ->
            host.fx {
                if (mine != generation) {
                    (outcome as? StatementOutcome.Rows)?.cursor?.close()
                    return@fx
                }
                if (error != null) {
                    fail(message(error))
                    return@fx
                }
                host.logOutcome(outcome)
                when (outcome) {
                    is StatementOutcome.Rows -> {
                        load(outcome)
                        if (before != null) restore(before, note)
                    }

                    is StatementOutcome.Failed -> {
                        fail(outcome.message, logIt = false)
                    }

                    is StatementOutcome.Cancelled -> {
                        fail("Cancelled", logIt = false)
                    }

                    is StatementOutcome.Updated -> {
                        fail("Not a query", logIt = false)
                    }
                }
            }
        }
    }

    /**
     * Goes back to [before]'s page (the last one when it now lies beyond the end, as after deleting the rows of the
     * last page) and its client sort; [note] stays the status.
     */
    private fun restore(
        before: Shown,
        note: String?,
    ) {
        val sort = { before.sort?.let { (column, ascending) -> grid.sortOnClient(column, ascending) } }
        val c = cursor
        if (before.pageIndex == 0 || c == null) {
            sort()
            return
        }
        afterLoad = note
        fetch(c.page(before.pageIndex)) { shownPage ->
            if (shownPage.rows.isEmpty()) {
                afterLoad = note
                fetch(c.lastPage(host.fetchAllCap())) { sort() }
            } else {
                sort()
            }
        }
    }

    /** Closes the cursor (the pages fetched stay viewable); a later load or reload opens it again. */
    fun closeCursor() {
        cursor?.close()
    }

    /** The tab is gone: closes the cursor; later answers are ignored. */
    fun close() {
        closed = true
        generation++
        cursor?.close()
    }
}
