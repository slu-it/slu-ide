package dev.sluide.databases.session

import java.sql.ResultSet
import java.sql.Statement
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** An open result set on the session connection; [next] is the index of the row the next `next()` reads. */
internal class LiveResult(
    val statement: Statement,
    val resultSet: ResultSet,
    /** The statement runs in a transaction of its own that ends when the result closes (AUTO mode, ADR 0121). */
    val autoTransaction: Boolean,
    /**
     * Set when the driver reads at most `limit + 1` rows (`setMaxRows`, PostgreSQL in a user's AUTO
     * transaction): reaching row `limit` caps the result there instead of being its end.
     */
    val limit: Int? = null,
) {
    var next = 0
}

/** What a cursor needs from its session; all but [onSession] are called on the session thread. */
internal interface CursorHost {
    /** Runs cursor work on the session thread, without closing the open cursor first. */
    fun <T> onSession(work: () -> T): CompletableFuture<T>

    /** Runs the cursor's statement again (after closing any other open cursor) and makes it the open cursor. */
    fun reopen(cursor: CursorState): LiveResult

    /** Closes [live] and ends its transaction; [failed] rolls back instead of committing. Never throws. */
    fun end(
        live: LiveResult,
        reachedEnd: Boolean,
        failed: Boolean = false,
    )
}

/**
 * The shared state of a result's cursor views: rows read so far by their index (sparse after skips), the open result
 * set and what is known about the end. Reads run on the session thread; [cached] serves any thread.
 */
internal class CursorState(
    private val host: CursorHost,
    val sql: String,
    val columns: List<ColumnInfo>,
    /** The fetch size the statement runs with. */
    val fetchSize: Int,
    /** Whether the statement was taken for a query: only those are run again. */
    val query: Boolean,
    live: LiveResult,
) {
    private val rows = ConcurrentHashMap<Int, List<Any?>>()

    @Volatile var live: LiveResult? = live
        private set

    @Volatile var knownRowCount: Int? = null
        private set

    @Volatile var capped = false
        private set

    @Volatile var reExecuted = false
        private set

    /** Page [index] of [size] rows if all its rows are cached; empty and last beyond a known end. */
    fun cached(
        index: Int,
        size: Int,
    ): Page? {
        val start = index * size
        val count = knownRowCount
        val end = if (count == null) start + size else minOf(start + size, count)
        val list = (start until end).map { rows[it] ?: return null }
        return Page(index, start, list, count != null && end >= count)
    }

    /** Page [index] of [size] rows, read from the open result set or by running the statement again. */
    fun read(
        index: Int,
        size: Int,
    ): Page =
        cached(index, size) ?: reading {
            val start = index * size
            var from = start
            while (from < start + size && rows.containsKey(from)) from++
            val result = positioned(from)
            if (result != null) {
                while (result.next < start + size && advance(result, keep = true)) Unit
                // One row ahead tells whether this page is the last.
                if (result.next == start + size && live === result) advance(result, keep = true)
            }
            cached(index, size) ?: error("Page $index not read")
        }

    /** Reads to the end, at most [cap] rows in total, and returns the last page of [size] rows. */
    fun readLast(
        cap: Int,
        size: Int,
    ): Page {
        knownRowCount?.let { return read(lastIndex(it, size), size) }
        return reading {
            var result = live
            if (result != null && result.next > cap) {
                capped = true
                knownRowCount = cap
                return@reading read(lastIndex(cap, size), size)
            }
            // The rows of the current page before the open position must be cached, else the page cannot be completed.
            val pageStart = (result?.next ?: 0) / size * size
            if (result == null || (pageStart until result.next).any { !rows.containsKey(it) }) result = positioned(0)
            if (result != null) {
                val bucket = ArrayList<List<Any?>>()
                var bucketStart = result.next / size * size
                (bucketStart until result.next).forEach { bucket += rows.getValue(it) }
                while (live === result) {
                    if (result.next == cap) {
                        stopAtCap(result)
                        break
                    }
                    if (!nextRow(result)) break
                    if (result.next % size == 0) {
                        bucket.clear()
                        bucketStart = result.next
                    }
                    bucket += JdbcValues.row(result.resultSet, columns)
                    result.next++
                }
                bucket.forEachIndexed { i, row -> rows[bucketStart + i] = row }
            }
            val count = knownRowCount ?: error("End not reached")
            cached(lastIndex(count, size), size) ?: read(lastIndex(count, size), size)
        }
    }

    /** All rows from the first, at most [cap]. */
    fun readAll(cap: Int): List<List<Any?>> {
        cachedAll(cap)?.let { return it }
        return reading {
            var have = 0
            while (have < cap && rows.containsKey(have)) have++
            if (have == cap && rows.containsKey(cap)) {
                capped = true
                knownRowCount = cap
            } else {
                val result = positioned(have)
                if (result != null) {
                    while (result.next < cap && advance(result, keep = true)) Unit
                    if (result.next == cap && live === result) stopAtCap(result)
                }
            }
            cachedAll(cap) ?: error("Rows not read")
        }
    }

    /** All rows up to [cap] if the end (or the cap) is known and they are cached. */
    fun cachedAll(cap: Int): List<List<Any?>>? {
        val count = knownRowCount ?: return null
        return (0 until minOf(count, cap)).map { rows[it] ?: return null }
    }

    /** Closes the open result set, if any (session thread). */
    fun release() {
        val result = live ?: return
        live = null
        host.end(result, reachedEnd = false)
    }

    /** Forgets the open result set without talking to the server, whose connection is gone. */
    fun abandon() {
        live = null
    }

    /** An open result set positioned at row [from]: the open one if it is not past it, else a new run; null at the end. */
    private fun positioned(from: Int): LiveResult? {
        var result = live
        if (result == null || result.next > from) {
            check(query) { "The result is closed and its statement is not run again, as it is not a query" }
            release()
            result = host.reopen(this)
            live = result
            reExecuted = true
        }
        while (result.next < from) {
            if (!advance(result, keep = false)) return null
        }
        return result
    }

    /** Reads the next row, caching it when [keep]; false at the end, which closes the result. */
    private fun advance(
        result: LiveResult,
        keep: Boolean,
    ): Boolean {
        if (!nextRow(result)) return false
        if (keep) rows[result.next] = JdbcValues.row(result.resultSet, columns)
        result.next++
        return true
    }

    /** At [cap] rows read: one more row means the result is capped there, none that its end is reached. */
    private fun stopAtCap(result: LiveResult) {
        if (nextRow(result)) {
            result.next++
            capped = true
            knownRowCount = result.next - 1
        }
    }

    /**
     * Moves [result] to its next row; false at its end, or at row `limit` of a limited result, which caps the result
     * at its limit. Either closes it.
     */
    private fun nextRow(result: LiveResult): Boolean {
        if (!result.resultSet.next()) {
            reachedEnd(result)
            return false
        }
        val limit = result.limit
        if (limit != null && result.next >= limit) {
            capped = true
            knownRowCount = limit
            live = null
            host.end(result, reachedEnd = true)
            return false
        }
        return true
    }

    private fun reachedEnd(result: LiveResult) {
        knownRowCount = result.next
        live = null
        host.end(result, reachedEnd = true)
    }

    /**
     * Runs [work]; when it fails, the open result set is given up (rolled back), since its position is unknown; after
     * a lost connection it is only forgotten, without talking to the server.
     */
    private fun <T> reading(work: () -> T): T =
        try {
            work()
        } catch (e: Exception) {
            live?.let {
                live = null
                if (!SqlFailures.isConnectionLost(e)) host.end(it, reachedEnd = false, failed = true)
            }
            throw e
        }

    private fun lastIndex(
        count: Int,
        size: Int,
    ) = if (count == 0) 0 else (count - 1) / size
}

/** A view of a [CursorState] with its own page size (see [RowCursor.withPageSize]). */
internal class CursorView(
    private val host: CursorHost,
    private val state: CursorState,
    override val pageSize: Int,
) : RowCursor {
    init {
        require(pageSize > 0) { "Page size must be positive: $pageSize" }
    }

    override val columns get() = state.columns
    override val knownRowCount get() = state.knownRowCount
    override val capped get() = state.capped
    override val reExecuted get() = state.reExecuted
    override val closed get() = state.live == null

    override fun page(index: Int): CompletableFuture<Page> =
        state.cached(index, pageSize)?.let { CompletableFuture.completedFuture(it) }
            ?: host.onSession { state.read(index, pageSize) }

    override fun lastPage(cap: Int): CompletableFuture<Page> {
        val count = state.knownRowCount
        val cached = if (count == null) null else state.cached(if (count == 0) 0 else (count - 1) / pageSize, pageSize)
        return cached?.let { CompletableFuture.completedFuture(it) } ?: host.onSession { state.readLast(cap, pageSize) }
    }

    override fun fetchAll(cap: Int): CompletableFuture<List<List<Any?>>> =
        state.cachedAll(cap)?.let { CompletableFuture.completedFuture(it) } ?: host.onSession { state.readAll(cap) }

    override fun withPageSize(size: Int): RowCursor = CursorView(host, state, size)

    override fun close() {
        if (state.live != null) host.onSession { state.release() }
    }
}
