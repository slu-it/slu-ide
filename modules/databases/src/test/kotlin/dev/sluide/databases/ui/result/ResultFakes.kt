package dev.sluide.databases.ui.result

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.session.Page
import dev.sluide.databases.session.RowCursor
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.ui.FakeSession
import java.util.concurrent.CompletableFuture

/** A cursor over [rows] in pages of [pageSize]; records the pages asked for and whether it was closed. */
internal class FakeCursor(
    override val columns: List<ColumnInfo>,
    private val rows: List<List<Any?>>,
    override val pageSize: Int = 2,
) : RowCursor {
    val asked = mutableListOf<Int>()
    override var knownRowCount: Int? = null
    override var capped = false
    override var reExecuted = false
    override var closed = false
    val sizes = mutableListOf<Int>()
    val derived = mutableListOf<FakeCursor>()
    val lastCaps = mutableListOf<Int>()

    /** Pages are not answered until the test completes the future (they are queued here, with their index). */
    var manual = false
    val pending = mutableListOf<Pair<Int, CompletableFuture<Page>>>()

    /** Pages fail with this. */
    var failure: Throwable? = null

    fun firstPage(): Page = pageAt(0)

    fun pageOf(index: Int): Page = pageAt(index)

    private fun pageAt(index: Int): Page {
        val from = index * pageSize
        val slice = rows.drop(from).take(pageSize)
        return Page(index, from, slice, from + pageSize >= rows.size)
    }

    override fun page(index: Int): CompletableFuture<Page> {
        asked += index
        failure?.let { return CompletableFuture.failedFuture(it) }
        if (manual) return CompletableFuture<Page>().also { pending += index to it }
        return CompletableFuture.completedFuture(pageAt(index))
    }

    override fun lastPage(cap: Int): CompletableFuture<Page> {
        lastCaps += cap
        return page((rows.size - 1) / pageSize)
    }

    override fun fetchAll(cap: Int): CompletableFuture<List<List<Any?>>> = CompletableFuture.completedFuture(rows)

    override fun withPageSize(size: Int): RowCursor {
        sizes += size
        return FakeCursor(columns, rows, size).also {
            it.failure = failure
            derived += it
        }
    }

    override fun close() {
        closed = true
    }
}

/** A session whose statements are answered by [answer]; records the SQL and page size of each. */
internal class ExecutingSession(
    config: ConnectionConfig = ConnectionConfig("c1", "c", DatabaseKind.POSTGRESQL),
    var answer: (String) -> StatementOutcome = { StatementOutcome.Updated(it, 1, 0) },
) : FakeSession(config) {
    val executed = mutableListOf<Pair<String, Int>>()

    /** Statements are not answered until the test completes the future (queued here). */
    var defer = false
    val deferred = mutableListOf<CompletableFuture<StatementOutcome>>()

    override fun execute(
        sql: String,
        pageSize: Int,
    ): CompletableFuture<StatementOutcome> {
        executed += sql to pageSize
        if (defer) return CompletableFuture<StatementOutcome>().also { deferred += it }
        return CompletableFuture.completedFuture(answer(sql))
    }
}
