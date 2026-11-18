package dev.sluide.databases.session

import java.lang.reflect.Proxy
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CursorState] and [CursorView] over a result set that counts its `next()` calls, with a host that records what the
 * session would do (ends, reopens, session-thread hops). Row i holds `i * 10 + 1`. Fakes of the JDBC calls used:
 * `next` and `getObject(1)` (as FakeDatabase's result set), which behave as every driver's for a forward-only set.
 */
class CursorStateTest {
    private val columns = listOf(ColumnInfo("c1", "c1", "int4", Types.INTEGER, null, null, true, false))

    private class Rows(
        val total: Int,
        val failAt: Int? = null,
        val failure: SQLException = SQLException("read failed", "XX000"),
    ) {
        var nextCalls = 0
        var position = -1
    }

    private fun resultSet(rows: Rows): ResultSet =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ResultSet::class.java)) { _, method, args ->
            when (method.name) {
                "next" -> {
                    rows.nextCalls++
                    if (rows.failAt == rows.position + 1) throw rows.failure
                    rows.position++
                    rows.position < rows.total
                }

                "getObject" -> {
                    rows.position * 10 + 1
                }

                else -> {
                    throw UnsupportedOperationException(method.name + args?.toList())
                }
            }
        } as ResultSet

    private fun statement() =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Statement::class.java)) { _, method, _ ->
            throw UnsupportedOperationException(method.name)
        } as Statement

    private data class End(
        val live: LiveResult,
        val reachedEnd: Boolean,
        val failed: Boolean,
    )

    private inner class Host(
        private val total: Int,
        private val limit: Int? = null,
        /** The rows a run again gives, when the data changed meanwhile. */
        private val reopenTotal: Int? = null,
        private val failAt: Int? = null,
        private val failure: SQLException = SQLException("read failed", "XX000"),
    ) : CursorHost {
        val ends = mutableListOf<End>()
        val runs = mutableListOf<Rows>()
        var sessionCalls = 0
        lateinit var state: CursorState

        fun newLive(): LiveResult {
            val rows = Rows(if (runs.isEmpty()) total else reopenTotal ?: total, failAt, failure).also { runs += it }
            return LiveResult(statement(), resultSet(rows), autoTransaction = false, limit = limit)
        }

        override fun <T> onSession(work: () -> T): CompletableFuture<T> {
            sessionCalls++
            return CompletableFuture.completedFuture(work())
        }

        override fun reopen(cursor: CursorState): LiveResult = newLive()

        override fun end(
            live: LiveResult,
            reachedEnd: Boolean,
            failed: Boolean,
        ) {
            ends += End(live, reachedEnd, failed)
        }

        fun cursor(query: Boolean = true): CursorState {
            val live = newLive()
            return CursorState(this, "SELECT", columns, 10, query, live).also { state = it }
        }

        val lastRun get() = runs.last()
    }

    private fun ids(range: IntRange) = range.map { listOf(it * 10 + 1) }

    @Test
    fun `a page is read one row ahead, which tells whether it is the last`() {
        val host = Host(total = 25)
        val state = host.cursor()

        val first = state.read(0, 10)

        assertEquals(Page(0, 0, ids(0..9), last = false), first)
        assertEquals(11, host.lastRun.nextCalls)
        assertEquals(11, state.live?.next)
        assertNull(state.knownRowCount)
        assertEquals(emptyList(), host.ends)

        assertEquals(Page(1, 10, ids(10..19), last = false), state.read(1, 10))
        assertEquals(21, state.live?.next)
        assertEquals(21, host.lastRun.nextCalls)

        val third = state.read(2, 10)
        assertEquals(Page(2, 20, ids(20..24), last = true), third)
        assertEquals(25, state.knownRowCount)
        assertNull(state.live)
        assertFalse(state.capped)
        assertEquals(26, host.lastRun.nextCalls)
        assertEquals(1, host.ends.size)
        assertTrue(host.ends.single().reachedEnd)
        assertFalse(host.ends.single().failed)
        assertEquals(1, host.runs.size)
    }

    @Test
    fun `a result of exactly one page ends at the look-ahead, a shorter one before it`() {
        val exact = Host(total = 10)
        val page = exact.cursor().read(0, 10)
        assertEquals(Page(0, 0, ids(0..9), last = true), page)
        assertEquals(10, exact.state.knownRowCount)
        assertEquals(11, exact.lastRun.nextCalls)
        assertEquals(listOf(true), exact.ends.map { it.reachedEnd })

        val short = Host(total = 4)
        assertEquals(Page(0, 0, ids(0..3), last = true), short.cursor().read(0, 10))
        assertEquals(4, short.state.knownRowCount)
        assertEquals(5, short.lastRun.nextCalls)

        val empty = Host(total = 0)
        assertEquals(Page(0, 0, emptyList(), last = true), empty.cursor().read(0, 10))
        assertEquals(0, empty.state.knownRowCount)
    }

    @Test
    fun `a page far ahead skips the rows before it without keeping them`() {
        val host = Host(total = 25)
        val state = host.cursor()

        val third = state.read(2, 10)

        assertEquals(Page(2, 20, ids(20..24), last = true), third)
        assertEquals(26, host.lastRun.nextCalls)
        assertNull(state.cached(0, 10))
        assertNull(state.cached(1, 10))
        assertEquals(third, state.cached(2, 10))
    }

    @Test
    fun `a cached page is served without touching the result set, an incomplete one is not`() {
        val host = Host(total = 25)
        val state = host.cursor()
        state.read(0, 10)

        assertEquals(Page(0, 0, ids(0..9), last = false), state.cached(0, 10))
        assertEquals(Page(0, 0, ids(0..4), last = false), state.cached(0, 5))
        assertEquals(Page(1, 5, ids(5..9), last = false), state.cached(1, 5))
        assertNull(state.cached(2, 5))
        assertNull(state.cached(1, 10))
        assertEquals(11, host.lastRun.nextCalls)
    }

    @Test
    fun `a page that is cut short by the known end is last, also when the page is empty`() {
        val host = Host(total = 5)
        val state = host.cursor()
        state.read(0, 10)

        assertEquals(Page(0, 0, ids(0..4), last = true), state.cached(0, 10))
        assertEquals(Page(1, 10, emptyList(), last = true), state.cached(1, 10))
        assertEquals(Page(1, 3, ids(3..4), last = true), state.cached(1, 3))
        assertEquals(Page(0, 0, ids(0..2), last = false), state.cached(0, 3))
        assertEquals(Page(0, 0, ids(0..4), last = true), state.cached(0, 5))
    }

    @Test
    fun `a page before the open position runs the statement again and keeps the cache`() {
        val host = Host(total = 45)
        val state = host.cursor()
        state.read(1, 10)
        assertEquals(21, state.live?.next)
        assertFalse(state.reExecuted)

        val first = state.read(0, 10)

        assertEquals(Page(0, 0, ids(0..9), last = false), first)
        assertTrue(state.reExecuted)
        assertEquals(2, host.runs.size)
        assertEquals(1, host.ends.size)
        assertFalse(host.ends.single().reachedEnd)
        assertFalse(host.ends.single().failed)
        assertEquals(11, state.live?.next)
        assertEquals(ids(10..19), state.cached(1, 10)?.rows)
    }

    @Test
    fun `a closed result is run again only for a query, with the reason in the failure`() {
        val query = Host(total = 25)
        val state = query.cursor()
        state.release()
        assertEquals(1, query.ends.size)
        assertEquals(ids(20..24), state.read(2, 10).rows)
        assertTrue(state.reExecuted)
        assertEquals(2, query.runs.size)

        val write = Host(total = 25)
        val other = write.cursor(query = false)
        other.release()
        val error = assertFailsWith<IllegalStateException> { other.read(2, 10) }
        assertEquals("The result is closed and its statement is not run again, as it is not a query", error.message)
        assertEquals(1, write.runs.size)
        assertFalse(other.reExecuted)
    }

    @Test
    fun `an open result positioned before the page is read on instead of run again`() {
        val host = Host(total = 45)
        val state = host.cursor()
        state.read(0, 10)

        state.read(2, 10)

        assertEquals(1, host.runs.size)
        assertFalse(state.reExecuted)
        assertEquals(31, state.live?.next)
        assertEquals(31, host.lastRun.nextCalls)
    }

    @Test
    fun `release closes the open result once as not reached and not failed, abandon forgets it without telling`() {
        val host = Host(total = 25)
        val state = host.cursor()
        val live = state.live

        state.release()
        state.release()

        assertEquals(listOf(End(assertNotNull(live), reachedEnd = false, failed = false)), host.ends)
        assertNull(state.live)

        val other = Host(total = 25).also { it.cursor() }
        other.state.abandon()
        assertNull(other.state.live)
        assertEquals(emptyList(), other.ends)
    }

    @Test
    fun `the last page reads to the end and keeps only the last page`() {
        val host = Host(total = 25)
        val state = host.cursor()

        val last = state.readLast(100, 10)

        assertEquals(Page(2, 20, ids(20..24), last = true), last)
        assertEquals(25, state.knownRowCount)
        assertFalse(state.capped)
        assertEquals(26, host.lastRun.nextCalls)
        assertNull(state.cached(0, 10))
        assertNull(state.cached(1, 10))
        assertEquals(1, host.runs.size)
        assertEquals(listOf(true), host.ends.map { it.reachedEnd })
    }

    @Test
    fun `the last page of a result that fills its pages exactly is the full page`() {
        val host = Host(total = 20)
        val state = host.cursor()

        assertEquals(Page(1, 10, ids(10..19), last = true), state.readLast(100, 10))
        assertEquals(20, state.knownRowCount)
        assertEquals(Page(1, 10, ids(10..19), last = true), state.readLast(5, 10))
    }

    @Test
    fun `the last page of an empty result is the empty first page`() {
        val host = Host(total = 0)
        val state = host.cursor()

        assertEquals(Page(0, 0, emptyList(), last = true), state.readLast(100, 10))
        assertEquals(0, state.knownRowCount)
    }

    @Test
    fun `the last page stops at the cap, one row more than it tells that the result is capped`() {
        val capped = Host(total = 25)
        val state = capped.cursor()

        assertEquals(Page(1, 10, ids(10..14), last = true), state.readLast(15, 10))
        assertTrue(state.capped)
        assertEquals(15, state.knownRowCount)
        assertEquals(16, capped.lastRun.nextCalls)
        assertNotNull(state.live, "the result stays open at the cap")

        val exact = Host(total = 15)
        val whole = exact.cursor()
        assertEquals(Page(1, 10, ids(10..14), last = true), whole.readLast(15, 10))
        assertFalse(whole.capped)
        assertEquals(15, whole.knownRowCount)
        assertNull(whole.live)
        assertEquals(16, exact.lastRun.nextCalls)
    }

    @Test
    fun `the last page with a cap below the open position is cut from the cache`() {
        val host = Host(total = 45)
        val state = host.cursor()
        state.read(0, 10)
        state.read(1, 10)
        assertEquals(21, state.live?.next)

        val last = state.readLast(15, 10)

        assertEquals(Page(1, 10, ids(10..14), last = true), last)
        assertTrue(state.capped)
        assertEquals(15, state.knownRowCount)
        assertEquals(21, host.lastRun.nextCalls)
        assertEquals(1, host.runs.size)
    }

    @Test
    fun `the last page continues from the open position when the cap is not below it`() {
        val host = Host(total = 45)
        val state = host.cursor()
        state.read(0, 10)
        assertEquals(11, state.live?.next)

        val last = state.readLast(11, 10)

        assertEquals(Page(1, 10, ids(10..10), last = true), last)
        assertTrue(state.capped)
        assertEquals(11, state.knownRowCount)
        assertEquals(1, host.runs.size)
        assertEquals(12, host.lastRun.nextCalls)
    }

    @Test
    fun `the last page continues in the open result when the rows of its page before the position are cached`() {
        val host = Host(total = 25)
        val state = host.cursor()
        state.read(0, 10)
        state.read(1, 10)

        val last = state.readLast(100, 10)

        assertEquals(Page(2, 20, ids(20..24), last = true), last)
        assertEquals(1, host.runs.size)
        assertFalse(state.reExecuted)
        assertEquals(26, host.lastRun.nextCalls)
    }

    @Test
    fun `the last page runs the statement again when rows of its page before the open position were skipped`() {
        val host = Host(total = 45)
        val state = host.cursor()
        state.read(2, 10)
        assertEquals(31, state.live?.next)

        // A page of 40 rows begins at row 0, and rows 0 to 19 were skipped, not kept.
        val last = state.readLast(100, 40)

        assertEquals(Page(1, 40, ids(40..44), last = true), last)
        assertEquals(2, host.runs.size)
        assertTrue(state.reExecuted)
        assertEquals(45, state.knownRowCount)
    }

    @Test
    fun `the last page of a closed result runs the statement again from the first row`() {
        val host = Host(total = 25)
        val state = host.cursor()
        state.release()

        val last = state.readLast(100, 10)

        assertEquals(Page(2, 20, ids(20..24), last = true), last)
        assertEquals(2, host.runs.size)
        assertTrue(state.reExecuted)
        assertEquals(26, host.lastRun.nextCalls)
    }

    @Test
    fun `the last page of a known end is read like any page`() {
        val host = Host(total = 25)
        val state = host.cursor()
        state.readLast(100, 10)

        assertEquals(Page(4, 20, ids(20..24), last = true), state.readLast(100, 5))
        assertEquals(Page(0, 0, ids(0..9), last = false), state.read(0, 10))
        assertEquals(2, host.runs.size)
    }

    @Test
    fun `all rows are read up to the end`() {
        val host = Host(total = 25)
        val state = host.cursor()

        val all = state.readAll(100)

        assertEquals(ids(0..24), all)
        assertEquals(25, state.knownRowCount)
        assertFalse(state.capped)
        assertEquals(26, host.lastRun.nextCalls)
        assertEquals(all, state.cachedAll(100))
        assertEquals(ids(0..9), state.cachedAll(10))
        assertEquals(ids(0..24), state.cachedAll(25))
    }

    @Test
    fun `all rows up to a cap, one row more than it tells that the result is capped`() {
        val host = Host(total = 25)
        val state = host.cursor()

        assertEquals(ids(0..14), state.readAll(15))

        assertTrue(state.capped)
        assertEquals(15, state.knownRowCount)
        assertEquals(16, host.lastRun.nextCalls)
        assertEquals(ids(0..14), state.cachedAll(20))

        val exact = Host(total = 15)
        val whole = exact.cursor()
        assertEquals(ids(0..14), whole.readAll(15))
        assertFalse(whole.capped)
        assertEquals(15, whole.knownRowCount)
        assertEquals(16, exact.lastRun.nextCalls)
    }

    @Test
    fun `all rows up to a cap that the cache already passes are cut without reading`() {
        val host = Host(total = 25)
        val state = host.cursor()
        state.read(0, 10)
        state.read(1, 10)

        assertEquals(ids(0..14), state.readAll(15))

        assertTrue(state.capped)
        assertEquals(15, state.knownRowCount)
        assertEquals(21, host.lastRun.nextCalls)
    }

    @Test
    fun `all rows continue after the cached ones, and read the row after the cap when the cache ends at it`() {
        val host = Host(total = 25)
        val state = host.cursor()
        state.read(0, 10)
        assertEquals(11, host.lastRun.nextCalls)

        assertEquals(ids(0..10), state.readAll(11))

        assertTrue(state.capped)
        assertEquals(11, state.knownRowCount)
        assertEquals(12, host.lastRun.nextCalls)
        assertEquals(1, host.runs.size)

        val more = Host(total = 25)
        val partial = more.cursor()
        partial.read(0, 10)
        assertEquals(ids(0..19), partial.readAll(20))
        assertEquals(1, more.runs.size)
        assertEquals(21, more.lastRun.nextCalls)
    }

    @Test
    fun `all rows of a closed result run the statement again and keep what is cached`() {
        val host = Host(total = 12)
        val state = host.cursor()
        state.read(0, 5)
        state.release()

        assertEquals(ids(0..11), state.readAll(100))

        assertTrue(state.reExecuted)
        assertEquals(2, host.runs.size)
        assertEquals(12, state.knownRowCount)
        assertEquals(13, host.lastRun.nextCalls)
    }

    @Test
    fun `a result with a limit is capped at it by the row the driver read beyond`() {
        val host = Host(total = 21, limit = 20)
        val state = host.cursor()

        assertEquals(Page(1, 10, ids(10..19), last = true), state.read(1, 10))

        assertTrue(state.capped)
        assertEquals(20, state.knownRowCount)
        assertNull(state.live)
        assertEquals(listOf(true), host.ends.map { it.reachedEnd })
        assertFalse(host.ends.single().failed)
        assertEquals(21, host.lastRun.nextCalls)
        assertEquals(
            20,
            host.ends
                .single()
                .live.next,
            "the row beyond the limit is not counted",
        )
    }

    @Test
    fun `a result with a limit that does not reach it ends normally`() {
        val host = Host(total = 8, limit = 20)
        val state = host.cursor()

        assertEquals(Page(0, 0, ids(0..7), last = true), state.read(0, 10))

        assertFalse(state.capped)
        assertEquals(8, state.knownRowCount)
    }

    @Test
    fun `a failing read gives up the open result as failed and rethrows, a lost connection only forgets it`() {
        val host = Host(total = 25, failAt = 5)
        val state = host.cursor()
        val live = state.live

        val error = assertFailsWith<SQLException> { state.read(0, 10) }

        assertEquals("read failed", error.message)
        assertNull(state.live)
        assertEquals(listOf(End(assertNotNull(live), reachedEnd = false, failed = true)), host.ends)

        val gone = Host(total = 25, failAt = 5, failure = SQLException("connection gone", "08006"))
        val lost = gone.cursor()
        assertFailsWith<SQLException> { lost.read(0, 10) }
        assertNull(lost.live)
        assertEquals(emptyList(), gone.ends)
    }

    @Test
    fun `a failing read of the last page and of all rows gives up the result too`() {
        val last = Host(total = 25, failAt = 5)
        assertFailsWith<SQLException> { last.cursor().readLast(100, 10) }
        assertEquals(listOf(true), last.ends.map { it.failed })

        val all = Host(total = 25, failAt = 5)
        assertFailsWith<SQLException> { all.cursor().readAll(100) }
        assertEquals(listOf(true), all.ends.map { it.failed })
    }

    @Test
    fun `a view serves cached pages without the session and reads the others on it`() {
        val host = Host(total = 25)
        val state = host.cursor()
        val view = CursorView(host, state, 10)

        val first = view.page(0).get()
        assertEquals(ids(0..9), first.rows)
        assertEquals(1, host.sessionCalls)

        assertEquals(first, view.page(0).get())
        assertEquals(1, host.sessionCalls)
        assertEquals(ids(10..19), view.page(1).get().rows)
        assertEquals(2, host.sessionCalls)
    }

    @Test
    fun `a view serves the last page and all rows from the cache once they are known`() {
        val host = Host(total = 25)
        val state = host.cursor()
        val view = CursorView(host, state, 10)

        assertEquals(ids(20..24), view.lastPage(100).get().rows)
        assertEquals(1, host.sessionCalls)
        assertEquals(ids(0..24), view.fetchAll(100).get())
        assertEquals(2, host.sessionCalls)
        assertEquals(ids(0..24), view.fetchAll(100).get())
        assertEquals(2, host.sessionCalls)
    }

    @Test
    fun `a view's last page is cached by the known row count, for any page size`() {
        val host = Host(total = 25)
        val state = host.cursor()
        state.readAll(100)
        // Page 4 of 5 rows is cached by readAll, page 2 of 10 rows too: both are served without the session.
        val byFive = CursorView(host, state, 5)
        val byTen = CursorView(host, state, 10)

        assertEquals(Page(4, 20, ids(20..24), last = true), byFive.lastPage(100).get())
        assertEquals(Page(2, 20, ids(20..24), last = true), byTen.lastPage(100).get())
        assertEquals(0, host.sessionCalls)
    }

    @Test
    fun `a view's last page of an empty result is page 0`() {
        val host = Host(total = 0)
        val state = host.cursor()
        state.read(0, 10)
        val view = CursorView(host, state, 10)

        assertEquals(Page(0, 0, emptyList(), last = true), view.lastPage(100).get())
        assertEquals(0, host.sessionCalls)
    }

    @Test
    fun `a view shares columns, flags and the open result with views of other page sizes`() {
        val host = Host(total = 25)
        val state = host.cursor()
        val view = CursorView(host, state, 10)
        val other = view.withPageSize(7)

        assertEquals(columns, view.columns)
        assertEquals(10, view.pageSize)
        assertEquals(7, other.pageSize)
        assertFalse(view.closed)
        assertNull(view.knownRowCount)
        assertFalse(view.capped)
        assertFalse(view.reExecuted)

        other.lastPage(15).get()

        assertEquals(15, view.knownRowCount)
        assertTrue(view.capped)
    }

    @Test
    fun `closing a view releases the open result on the session once, a closed one does not hop`() {
        val host = Host(total = 25)
        val state = host.cursor()
        val view = CursorView(host, state, 10)

        view.close()
        assertTrue(view.closed)
        assertEquals(1, host.sessionCalls)
        assertEquals(1, host.ends.size)

        view.close()
        assertEquals(1, host.sessionCalls)
        assertEquals(1, host.ends.size)
    }

    @Test
    fun `a view needs a positive page size`() {
        val host = Host(total = 1)
        val state = host.cursor()

        assertEquals(
            "Page size must be positive: 0",
            assertFailsWith<IllegalArgumentException> {
                CursorView(host, state, 0)
            }.message,
        )
        assertEquals(1, CursorView(host, state, 1).pageSize)
        assertEquals(
            "Page size must be positive: -3",
            assertFailsWith<IllegalArgumentException> { CursorView(host, state, -3) }.message,
        )
        assertFailsWith<IllegalArgumentException> { CursorView(host, state, 10).withPageSize(-1) }
    }

    @Test
    fun `the last page continues in the open result when its page was skipped to but is cached from its start`() {
        val host = Host(total = 25)
        val state = host.cursor()
        state.read(1, 10)
        assertEquals(21, state.live?.next)

        val last = state.readLast(100, 10)

        assertEquals(Page(2, 20, ids(20..24), last = true), last)
        assertEquals(1, host.runs.size)
        assertFalse(state.reExecuted)
        assertEquals(26, host.lastRun.nextCalls)
    }

    @Test
    fun `all rows that are known are served again without reading`() {
        val host = Host(total = 25)
        val state = host.cursor()
        state.readAll(100)

        assertEquals(ids(0..24), state.readAll(100))

        assertEquals(1, host.runs.size)
        assertEquals(26, host.lastRun.nextCalls)
        assertEquals(1, host.ends.size)
    }

    @Test
    fun `all rows up to a cap run the statement again when the cache has a hole before the cap`() {
        val host = Host(total = 45)
        val state = host.cursor()
        state.read(2, 10)

        assertEquals(ids(0..24), state.readAll(25))

        assertEquals(2, host.runs.size)
        assertTrue(state.reExecuted)
        assertTrue(state.capped)
        assertEquals(25, state.knownRowCount)
    }

    @Test
    fun `a statement run again that gives fewer rows ends where the data ends`() {
        val host = Host(total = 25, reopenTotal = 5)
        val state = host.cursor()
        state.read(0, 10)
        state.release()

        assertEquals(ids(0..4), state.readAll(100))

        assertEquals(2, host.runs.size)
        assertEquals(5, state.knownRowCount)
        assertNull(state.live)

        val page = Host(total = 25, reopenTotal = 5)
        val other = page.cursor()
        other.read(0, 10)
        other.release()
        assertEquals(Page(1, 10, emptyList(), last = true), other.read(1, 10))
    }

    @Test
    fun `with pages of one row the last page of an empty result is the first`() {
        val host = Host(total = 0)
        val state = host.cursor()

        assertEquals(Page(0, 0, emptyList(), last = true), state.readLast(100, 1))

        val view = CursorView(host, state, 1)
        assertEquals(Page(0, 0, emptyList(), last = true), view.lastPage(100).get())
        assertEquals(0, host.sessionCalls)
    }
}
