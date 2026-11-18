package dev.sluide.databases.session

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Running statements on a [JdbcSession]: the run flags, outcomes, transaction marks, logs and cursor reopening. */
class JdbcSessionRunTest {
    private val fixture = SessionFixture()
    private val db = fixture.db
    private val loggerName = JdbcSession::class.java.name

    @AfterEach
    fun closeSession() = fixture.close()

    private fun rows(count: Int = 25) = FakeResult.Rows(fakeRows(count))

    /**
     * Lets [millis] pass: the statement under test measures real time with System.nanoTime, and there is no signal
     * for "time has passed"; sleep never returns early, so the elapsed time is at least [millis].
     */
    private fun letTimePass(millis: Long) = Thread.sleep(millis) // real time must pass, see above

    @Test
    fun `a statement without rows ends its run before it closes, an AUTO query commits its own transaction`() {
        db.results = { FakeResult.Count(1) }
        val session = fixture.open()
        session.addListener { if (it == SessionEvent.RunningChanged(false)) db.log += "event: idle" }
        db.log.clear()

        session.execute("SELECT 1", 10).await()

        assertEquals(
            listOf(
                "session: autoCommit=false",
                "session: fetchSize=10",
                "session: execute SELECT 1",
                "event: idle",
                "session: close statement",
                "session: commit",
                "session: autoCommit=true",
            ),
            db.log.toList(),
        )
    }

    @Test
    fun `the page size must be positive`() {
        val session = fixture.open()

        val error = assertFailsWith<IllegalArgumentException> { session.execute("UPDATE a", 0) }

        assertEquals("Page size must be positive: 0", error.message)
        assertIs<StatementOutcome.Updated>(session.execute("UPDATE a", 1).await())
    }

    @Test
    fun `under a statement timeout a cancel is Cancelled and a timeout is a Failed that says so`() {
        fixture.timeout = 7
        val blocking = FakeResult.BlocksUntilCancel()
        db.results = { blocking }
        val session = fixture.open()
        val cancelled = session.execute("UPDATE a", 10)
        assertTrue(blocking.started.await(10, TimeUnit.SECONDS))
        session.cancel()
        assertIs<StatementOutcome.Cancelled>(cancelled.await())

        db.results = { FakeResult.Fails(SQLException("canceling statement due to statement timeout", "57014")) }
        val failed = assertIs<StatementOutcome.Failed>(session.execute("UPDATE a", 10).await())
        assertEquals("Statement timeout of 7 s exceeded: canceling statement due to statement timeout", failed.message)
    }

    @Test
    fun `a failure before the statement exists is a Failed, not a cancel, and leaves the session idle`() {
        fixture.timeout = 7
        db.failOn("session: setQueryTimeout")
        val session = fixture.open()

        val failed = assertIs<StatementOutcome.Failed>(session.execute("UPDATE a", 10).await())

        assertEquals("fake failure of session: setQueryTimeout", failed.message)
        assertFalse(session.running)
        assertFalse(session.closed)
    }

    @Test
    fun `a failure that is no SQLException is logged as an error and does not ask the connection`() {
        db.failOn("session: execute") { IllegalStateException("boom") }
        val session = fixture.open()

        LogCapture(loggerName).use { log ->
            val failed = assertIs<StatementOutcome.Failed>(session.execute("UPDATE a", 10).await())

            assertEquals("boom", failed.message)
            assertNull(failed.sqlState)
            val error = log.lines.single { it.second.endsWith("statement failed") }
            assertEquals(Level.SEVERE, error.first)
            assertTrue(Regex("db#\\d+ statement failed").matches(error.second), error.second)
            assertEquals("boom", log.thrown.single().message)
        }
        assertEquals(0, db.isValidCalls.get())
        assertFalse(session.closed)
    }

    @ParameterizedTest
    @CsvSource("updated, 3", "rows, 0", "failed, 0", "cancelled, 0")
    fun `a finished statement is logged with its outcome and its time, never its SQL`(
        kind: String,
        count: Long,
    ) {
        val blocking = FakeResult.BlocksUntilCancel()
        db.results = {
            when (kind) {
                "updated" -> FakeResult.Count(count)
                "rows" -> rows(2)
                "failed" -> FakeResult.Fails(SQLException("nope", "42P01"))
                else -> blocking
            }
        }
        val session = fixture.open()

        LogCapture(loggerName).use { log ->
            val outcome = session.execute("UPDATE secret_table", 10)
            if (kind == "cancelled") {
                assertTrue(blocking.started.await(10, TimeUnit.SECONDS))
                session.cancel()
            }
            val millis = outcome.await().millis

            val expected =
                mapOf(
                    "updated" to "updated 3",
                    "rows" to "rows",
                    "failed" to "failed \\(SQLState 42P01\\)",
                    "cancelled" to "cancelled",
                ).getValue(kind)
            val line = log.lines.single { "statement finished" in it.second }
            assertEquals(Level.INFO, line.first)
            assertTrue(Regex("db#\\d+ statement finished in $millis ms: $expected").matches(line.second), line.second)
        }
    }

    @ParameterizedTest
    @CsvSource("updated", "rows", "failed")
    fun `the time of a statement is measured from its start to its end`(kind: String) {
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        db.results = {
            started.countDown()
            check(gate.await(10, TimeUnit.SECONDS)) { "gate never opened" }
            when (kind) {
                "updated" -> FakeResult.Count(1)
                "rows" -> rows(2)
                else -> FakeResult.Fails(SQLException("nope", "42P01"))
            }
        }
        val session = fixture.open()

        val outcome = session.execute("UPDATE a", 10)
        assertTrue(started.await(10, TimeUnit.SECONDS))
        letTimePass(30)
        gate.countDown()

        val millis = outcome.await().millis
        assertTrue(millis in 30..60_000, "$millis ms")
    }

    @Test
    fun `a commit mode that is already set only closes the open cursor`() {
        db.results = { rows() }
        val session = fixture.open()
        val result = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM t", 10).await())
        db.log.clear()

        assertEquals(Unit, (session.setCommitMode(CommitMode.AUTO) as CompletableFuture<*>).await())

        assertTrue(result.cursor.closed)
        assertEquals(
            listOf("session: close result", "session: close statement", "session: commit", "session: autoCommit=true"),
            db.log.toList(),
        )
    }

    @ParameterizedTest
    @CsvSource("true", "false")
    fun `commit and rollback close the open cursor first and complete with Unit`(commit: Boolean) {
        db.results = { rows() }
        val session = fixture.open()
        val result = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM t", 10).await())

        val done = if (commit) session.commit() else session.rollback()

        assertEquals(Unit, (done as CompletableFuture<*>).await())
        assertTrue(result.cursor.closed)
    }

    @Test
    fun `a failing work on a MANUAL PostgreSQL session rolls the aborted transaction back`() {
        val session = fixture.open(CommitMode.MANUAL)
        db.log.clear()

        val failing = session.withSession { throw SQLException("nope", "42000") }

        assertFailsWith<ExecutionException> { failing.await() }
        assertTrue("session: rollback" in db.log, db.log.toString())
        assertFalse(session.pendingTransaction)
    }

    @ParameterizedTest
    @CsvSource("true, true", "false, false")
    fun `a failing work marks a MariaDB MANUAL transaction pending only when it was meant to change something`(
        changes: Boolean,
        pending: Boolean,
    ) {
        val session = fixture.open(CommitMode.MANUAL, DatabaseKind.MARIADB)

        val failing = session.withSession(changes) { throw SQLException("nope", "42000") }

        assertFailsWith<ExecutionException> { failing.await() }
        assertEquals(pending, session.pendingTransaction)
    }

    @ParameterizedTest
    @CsvSource(
        "INSERT x RETURNING y, rows, true",
        "SELECT x, rows, false",
        "UPDATE a, count, true",
        "SELECT x, count, false",
    )
    fun `on MariaDB in MANUAL mode a statement that is not a query marks the transaction pending, rows or not`(
        sql: String,
        result: String,
        pending: Boolean,
    ) {
        db.results = { if (result == "rows") rows(2) else FakeResult.Count(1) }
        val session = fixture.open(CommitMode.MANUAL, DatabaseKind.MARIADB)

        session.execute(sql, 10).await()

        assertEquals(pending, session.pendingTransaction)
    }

    @Test
    fun `a page of a result closed by a later statement reopens it and closes the cursor of that statement`() {
        db.results = { rows() }
        val session = fixture.open()
        val first = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM a", 10).await())
        val second = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM b", 10).await())
        assertTrue(first.cursor.closed)
        assertFalse(second.cursor.closed)

        val page = first.cursor.page(2).await()

        assertEquals(ids(20..24), page.firsts())
        assertTrue(second.cursor.closed, "the second result is closed to run the first again")
        assertTrue(first.cursor.reExecuted)
    }

    private fun ids(range: IntRange) = range.map { it * 10 + 1 }

    @Test
    fun `a result run again gets its page size as fetch size, the statement timeout and its own AUTO transaction`() {
        db.results = { rows() }
        fixture.timeout = 7
        val session = fixture.open()
        val result = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM t", 10).await())
        result.cursor.close()
        fixture.eventually("the cursor closed") { result.cursor.closed }
        // `closed` is set at once; the session thread ends the transaction a little later, and its log lines must
        // not land after the clear
        fixture.eventually("the transaction ended") { db.log.lastOrNull() == "session: autoCommit=true" }
        db.log.clear()

        result.cursor.page(2).await()

        assertEquals(
            listOf("session: autoCommit=false", "session: fetchSize=10", "session: queryTimeout=7"),
            db.log.take(3),
        )
        assertEquals(listOf("SELECT * FROM t"), db.executed())
    }

    @Test
    fun `a statement that gives no rows when run again fails the page`() {
        var runs = 0
        db.results = { if (runs++ == 0) rows() else FakeResult.Count(1) }
        val session = fixture.open()
        val result = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM t", 10).await())
        result.cursor.close()

        val error = assertFailsWith<ExecutionException> { result.cursor.page(2).await() }

        assertEquals("The statement gave no rows when run again", error.cause?.message)
    }

    @Test
    fun `a result that cannot be described is closed and rolled back, and the statement Failed`() {
        db.results = { rows() }
        db.failOn("session: getMetaData")
        val session = fixture.open()
        db.log.clear()

        val failed = assertIs<StatementOutcome.Failed>(session.execute("SELECT * FROM t", 10).await())

        assertEquals("fake failure of session: getMetaData", failed.message)
        assertEquals(
            listOf(
                "session: autoCommit=false",
                "session: fetchSize=10",
                "session: execute SELECT * FROM t",
                "session: close result",
                "session: close statement",
                "session: rollback",
                "session: autoCommit=true",
            ),
            db.log.toList(),
        )
    }

    @Test
    fun `a failure before the statement exists rolls an AUTO query's transaction back without a statement to close`() {
        db.failOn("session: createStatement")
        val session = fixture.open()
        db.log.clear()

        assertIs<StatementOutcome.Failed>(session.execute("SELECT 1", 10).await())

        assertEquals(
            listOf("session: autoCommit=false", "session: rollback", "session: autoCommit=true"),
            db.log.toList(),
        )
    }

    @Test
    fun `a failing statement is closed and an AUTO query's transaction rolled back`() {
        db.results = { FakeResult.Fails(SQLException("nope", "42P01")) }
        val session = fixture.open()
        db.log.clear()

        assertIs<StatementOutcome.Failed>(session.execute("SELECT 1", 10).await())

        assertEquals(
            listOf(
                "session: autoCommit=false",
                "session: fetchSize=10",
                "session: execute SELECT 1",
                "session: close statement",
                "session: rollback",
                "session: autoCommit=true",
            ),
            db.log.toList(),
        )
    }

    @Test
    fun `a failing commit at the end of a result is logged by SQLState and auto-commit is still restored`() {
        db.results = { rows(3) }
        db.failOn("session: commit")
        val session = fixture.open()
        db.log.clear()

        LogCapture(SessionStatements::class.java.name).use { log ->
            session.execute("SELECT * FROM t", 10).await()

            val line = log.lines.single()
            assertEquals(Level.WARNING, line.first)
            assertTrue(
                Regex("db#\\d+ ending a result's transaction failed \\(SQLState XX000\\)").matches(line.second),
                line.second,
            )
        }
        assertEquals("session: autoCommit=true", db.log.last())
    }

    @Test
    fun `MariaDB does not cancel a result that was read to its end or that failed`() {
        db.results = { FakeResult.Rows(fakeRows(12), failAt = null) }
        val session = fixture.open(kind = DatabaseKind.MARIADB)
        val first = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM t", 10).await())
        first.cursor.page(1).await()
        assertTrue(first.cursor.closed)

        val failing = FakeResult.Rows(fakeRows(25), failAt = 15)
        db.results = { failing }
        val second = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM t", 10).await())
        assertFailsWith<ExecutionException> { second.cursor.page(1).await() }

        assertEquals(emptyList(), db.log.filter { it == "session: cancel" })
    }

    @Test
    fun `a PostgreSQL result inside the transaction of MANUAL mode is not limited`() {
        db.results = { rows() }
        val manual = fixture.open(CommitMode.MANUAL)
        manual.execute("SELECT * FROM t", 10).await()
        assertEquals(emptyList(), db.log.filter { "maxRows" in it })
    }

    @Test
    fun `a MariaDB result in AUTO mode is not limited, whatever the connection says about its transaction`() {
        db.results = { rows() }
        val session = fixture.open(kind = DatabaseKind.MARIADB)
        // The fake reports a transaction like pgjdbc would; the kind alone decides here.
        db.exposesTransactionState = true
        session.execute("BEGIN", 10).await()

        session.execute("SELECT * FROM t", 10).await()

        assertEquals(emptyList(), db.log.filter { "maxRows" in it })
    }
}
