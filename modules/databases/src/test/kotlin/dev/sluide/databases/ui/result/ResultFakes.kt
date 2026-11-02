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

    fun firstPage(): Page = pageAt(0)

    private fun pageAt(index: Int): Page {
        val from = index * pageSize
        val slice = rows.drop(from).take(pageSize)
        return Page(index, from, slice, from + pageSize >= rows.size)
    }

    override fun page(index: Int): CompletableFuture<Page> {
        asked += index
        return CompletableFuture.completedFuture(pageAt(index))
    }

    override fun lastPage(cap: Int): CompletableFuture<Page> = page((rows.size - 1) / pageSize)

    override fun fetchAll(cap: Int): CompletableFuture<List<List<Any?>>> = CompletableFuture.completedFuture(rows)

    override fun withPageSize(size: Int): RowCursor {
        sizes += size
        return FakeCursor(columns, rows, size)
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

    override fun execute(
        sql: String,
        pageSize: Int,
    ): CompletableFuture<StatementOutcome> {
        executed += sql to pageSize
        return CompletableFuture.completedFuture(answer(sql))
    }
}
