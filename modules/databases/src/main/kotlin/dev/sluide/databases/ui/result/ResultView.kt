package dev.sluide.databases.ui.result

import dev.sluide.databases.result.PageState
import dev.sluide.databases.result.TableDataQuery
import dev.sluide.databases.result.TableRef
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.session.Page
import dev.sluide.databases.session.RowCursor
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.sql.SqlSplitter
import dev.sluide.databases.ui.ConnectionEntry
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** What a result view needs from the area it sits in. */
internal interface ResultHost {
    val entry: ConnectionEntry

    fun pageSize(): Int

    fun fetchAllCap(): Int

    /** Runs [action] on the FX thread. */
    fun fx(action: Runnable)

    fun logOutcome(outcome: StatementOutcome)

    fun log(
        text: String,
        error: Boolean,
    )

    fun copyToClipboard(text: String)
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
    private var status = ""
    private var statusError = false
    private var closed = false

    val node =
        VBox().apply {
            styleClass += "result-view"
            if (dataRef != null) children += filterRow()
            children += bar.node
            children += grid.table
            VBox.setVgrow(grid.table, Priority.ALWAYS)
        }

    /** The grid, for the editing and viewing extensions of the next package. */
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
        refreshBar()
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
        statusError = false
        onColumns(rows.columns)
        bar.pageField.text = rows.cursor.pageSize.toString()
        bar.pageField.promptText = host.pageSize().toString()
        grid.setColumns(rows.columns, rows.firstPage)
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
        status = ResultTabsLogic.status(millis, c?.reExecuted == true, c?.capped == true)
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
        whereField.isDisable = busy
        orderByField.isDisable = busy
        bar.update(
            state,
            busy,
            if (busy) "Loading…" else status,
            statusError && !busy,
            reloadAllowed(),
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
        status = message
        statusError = true
        if (logIt) host.log(message, true)
        refreshBar()
    }

    /** Shows the page [future] gives; [adopt] becomes the cursor only once it did (a new page size). */
    private fun fetch(
        future: CompletableFuture<Page>?,
        adopt: RowCursor? = null,
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

    /** Runs the statement again (a data tab: its SELECT with the WHERE and ORDER BY of the fields). */
    fun reload() {
        if (closed) return
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
                    is StatementOutcome.Rows -> load(outcome)
                    is StatementOutcome.Failed -> fail(outcome.message, logIt = false)
                    is StatementOutcome.Cancelled -> fail("Cancelled", logIt = false)
                    is StatementOutcome.Updated -> fail("Not a query", logIt = false)
                }
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
