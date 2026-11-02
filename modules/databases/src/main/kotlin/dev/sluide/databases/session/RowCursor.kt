package dev.sluide.databases.session

import java.util.concurrent.CompletableFuture

/**
 * The rows of one result, read page by page from an open result set on the session connection (ADR 0121).
 *
 * The result set stays open (the cursor is not [closed]) until the session runs its next statement or
 * `withSession` work, the cursor is closed, or its end was read. Pages already read stay cached and are served from
 * any thread, also after the session closed. A page beyond the cached ones on a closed cursor runs the statement again
 * and skips the rows before it ([reExecuted]; only for statements the session took for a query, others fail with
 * [IllegalStateException] so that nothing is changed twice); that opens the cursor again. Futures of uncached pages
 * complete on the session thread, exceptionally with the driver's `SQLException` or [DbSessionClosedException].
 */
interface RowCursor : AutoCloseable {
    val columns: List<ColumnInfo>
    val pageSize: Int

    /** Total row count once the end was reached (or the cap hit), else null. */
    val knownRowCount: Int?

    /** True when Last/fetchAll stopped at the cap before the end. */
    val capped: Boolean

    /** True once pages beyond the cached ones had to re-execute the statement. */
    val reExecuted: Boolean

    /** Whether no result set is open for this cursor right now. */
    val closed: Boolean

    fun page(index: Int): CompletableFuture<Page>

    /** Reads to the end (at most cap rows in total) and returns the last page. */
    fun lastPage(cap: Int): CompletableFuture<Page>

    /** All rows from the first, at most cap (export). */
    fun fetchAll(cap: Int): CompletableFuture<List<List<Any?>>>

    /**
     * A view of the same result with pages of [size] rows: it shares the cache, the open result set and the flags
     * with this one, so closing either closes both.
     */
    fun withPageSize(size: Int): RowCursor

    /** Closes the open result set (on the session thread, without waiting); the cached pages stay. */
    override fun close()
}
