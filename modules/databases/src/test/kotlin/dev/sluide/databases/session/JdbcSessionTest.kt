package dev.sluide.databases.session

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcSessionTest {
    private val fixture = SessionFixture()
    private val db = fixture.db
    private val events = fixture.events

    @AfterEach
    fun closeSession() = fixture.close()

    @Test
    fun `opening sets up both connections, the metadata one read-only with auto-commit`() {
        fixture.open(CommitMode.MANUAL, readOnly = true)

        assertEquals(
            listOf(
                "session: readOnly=true",
                "session: autoCommit=false",
                "metadata: autoCommit=true",
                "metadata: readOnly=true",
            ),
            db.log,
        )
    }

    @Test
    fun `a statement given while another runs waits for it`() {
        val gated = FakeResult.Gated()
        db.results = { if (it == "UPDATE a") gated else FakeResult.Count(2) }
        val session = fixture.open()

        val first = session.execute("UPDATE a", 10)
        assertTrue(gated.started.await(10, TimeUnit.SECONDS))
        val second = session.execute("UPDATE b", 10)
        assertTrue(session.running)
        assertFalse(second.isDone)
        gated.gate.countDown()

        assertEquals(1, assertIs<StatementOutcome.Updated>(first.await()).count)
        assertEquals(2, assertIs<StatementOutcome.Updated>(second.await()).count)
        assertEquals(listOf("UPDATE a", "UPDATE b"), db.executed())
        assertFalse(session.running)
        assertEquals(
            List(2) { listOf(SessionEvent.RunningChanged(true), SessionEvent.RunningChanged(false)) }.flatten(),
            events.toList(),
        )
    }

    @Test
    fun `cancel from another thread ends the running statement as Cancelled`() {
        val blocking = FakeResult.BlocksUntilCancel()
        db.results = { blocking }
        val session = fixture.open()

        val outcome = session.execute("SELECT pg_sleep(30)", 10)
        assertTrue(blocking.started.await(10, TimeUnit.SECONDS))
        session.cancel()

        assertIs<StatementOutcome.Cancelled>(outcome.await())
        assertFalse(session.running)
        session.cancel() // idle: nothing to cancel
        assertEquals(1, db.log.count { it == "session: cancel" })
    }

    @Test
    fun `a cancel that arrives after its statement ended does not cancel anything`() {
        val gated = FakeResult.Gated()
        db.results = { gated }
        val session = fixture.open(captureCancels = true)
        val outcome = session.execute("UPDATE a", 10)
        assertTrue(gated.started.await(10, TimeUnit.SECONDS))
        session.cancel()

        gated.gate.countDown()
        assertIs<StatementOutcome.Updated>(outcome.await())
        fixture.cancels.single().run()

        assertEquals(0, db.log.count { it == "session: cancel" })
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 7])
    fun `the statement timeout is set only when there is one`(seconds: Int) {
        val session = fixture.open()
        fixture.timeout = seconds

        session.execute("UPDATE a", 10).await()

        val expected = if (seconds > 0) listOf("session: queryTimeout=7") else emptyList()
        assertEquals(expected, db.log.filter { "queryTimeout" in it })
        assertEquals(seconds, session.statementTimeoutSeconds, "what withSession work (edit submits) sets")
    }

    @Test
    fun `an SQL error is Failed with its message and SQLState`() {
        db.results = { FakeResult.Fails(SQLException("relation \"nope\" does not exist", "42P01")) }
        val session = fixture.open()

        val failed = assertIs<StatementOutcome.Failed>(session.execute("SELECT * FROM nope", 10).await())

        assertEquals("relation \"nope\" does not exist", failed.message)
        assertEquals("42P01", failed.sqlState)
        assertNull(failed.position)
        assertFalse(session.closed)
    }

    @ParameterizedTest
    @CsvSource("08006, true", "42000, false")
    fun `a lost connection closes the session with the error's message`(
        sqlState: String,
        valid: Boolean,
    ) {
        db.results = { FakeResult.Fails(SQLException("connection gone", sqlState)) }
        db.valid = valid
        val session = fixture.open()

        assertIs<StatementOutcome.Failed>(session.execute("UPDATE a", 10).await())

        fixture.awaitClosed()
        assertTrue(session.closed)
        assertEquals(listOf(SessionEvent.Closed("connection gone")), events.filterIsInstance<SessionEvent.Closed>())
        assertTrue("session: close" in db.log && "metadata: close" in db.log)
    }

    @Test
    fun `a session connection lost while reading a page closes the session without talking to the server`() {
        val gone = SQLException("connection gone", "08006")
        db.results = { FakeResult.Rows(fakeRows(25), failAt = 15, failure = gone) }
        val session = fixture.open()
        val rows = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM t", 10).await())
        db.log.clear()

        assertIs<SQLException>(assertFailsWith<ExecutionException> { rows.cursor.page(1).await() }.cause)

        fixture.awaitClosed()
        assertEquals(listOf(SessionEvent.Closed("connection gone")), events.filterIsInstance<SessionEvent.Closed>())
        assertTrue(rows.cursor.closed)
        val talked = listOf("session: close result", "session: commit", "session: rollback", "session: cancel")
        assertEquals(emptyList(), db.log.filter { it in talked })
    }

    @Test
    fun `a session connection lost in work closes the session`() {
        val session = fixture.open()

        val failing = session.withSession { throw SQLException("connection gone", "08006") }

        assertIs<SQLException>(assertFailsWith<ExecutionException> { failing.await() }.cause)
        fixture.awaitClosed()
        assertTrue(session.closed)
    }

    @Test
    fun `a lost session connection does not cancel the statement that found it`() {
        // A page beyond the cache runs the query again; that run is still current when the lost connection is found.
        db.results = { FakeResult.Rows(fakeRows(25), failAt = 15, failure = SQLException("connection gone", "08006")) }
        val session = fixture.open(captureCancels = true)
        val rows = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM t", 10).await())
        rows.cursor.close()

        assertFailsWith<ExecutionException> { rows.cursor.page(2).await() }

        fixture.awaitClosed()
        assertEquals(emptyList(), fixture.cancels.toList())
    }

    @Test
    fun `a lost metadata connection is opened again and the work retried, the session stays as it was`() {
        val session = fixture.open(CommitMode.MANUAL, DatabaseKind.MARIADB)
        session.execute("UPDATE a", 10).await()
        var calls = 0

        val answer =
            session
                .withMetadata {
                    if (calls++ == 0) throw SQLException("connection gone", "08006")
                    42
                }.await()

        assertEquals(42, answer)
        assertEquals(2, calls)
        assertEquals(
            listOf("metadata: close", "metadata: connected", "metadata: autoCommit=true", "metadata: readOnly=true"),
            db.log.filter { it.startsWith("metadata: ") }.drop(2),
        )
        assertFalse(session.closed)
        assertTrue(session.pendingTransaction)
        assertFalse("session: rollback" in db.log)
        assertEquals(emptyList(), events.filterIsInstance<SessionEvent.Closed>())
    }

    @Test
    fun `when the metadata connection cannot be opened again the work fails and the session stays open`() {
        db.connectFails = true
        val session = fixture.open()

        val failing = session.withMetadata { throw SQLException("connection gone", "08006") }

        val error = assertIs<SQLException>(assertFailsWith<ExecutionException> { failing.await() }.cause)
        assertEquals("08001", error.sqlState)
        assertFalse(session.closed)
        assertIs<StatementOutcome.Updated>(session.execute("UPDATE a", 10).await())
    }

    @Test
    fun `the session's copy of the password opens the metadata connection again and is zeroed at close`() {
        val password = "secret".toCharArray()
        val session = fixture.open(password = password)
        var calls = 0
        session.withMetadata { if (calls++ == 0) throw SQLException("connection gone", "08006") }.await()
        val copy = assertNotNull(db.reconnectPasswords.single())
        assertEquals("secret", String(copy))
        assertNotSame(password, copy)

        session.close()
        fixture.awaitClosed()

        assertTrue(copy.all { it == '\u0000' })
        assertEquals("secret", String(password))
    }

    @Test
    fun `close is idempotent, fails queued work and closes both connections`() {
        val blocking = FakeResult.BlocksUntilCancel()
        db.results = { blocking }
        val session = fixture.open()
        val running = session.execute("SELECT pg_sleep(30)", 10)
        assertTrue(blocking.started.await(10, TimeUnit.SECONDS))
        val queued = session.execute("SELECT 1", 10)

        session.close()
        session.close()

        assertTrue(session.closed)
        assertIs<StatementOutcome.Cancelled>(running.await())
        assertIs<DbSessionClosedException>(assertFailsWith<ExecutionException> { queued.await() }.cause)
        fixture.awaitClosed()
        assertTrue("session: close" in db.log && "metadata: close" in db.log)
        assertEquals(listOf(SessionEvent.Closed(null)), events.filterIsInstance<SessionEvent.Closed>())
        assertIs<DbSessionClosedException>(assertFailsWith<ExecutionException> { session.commit().await() }.cause)
    }

    @Test
    fun `close rolls back a pending MANUAL transaction before closing the connection`() {
        val session = fixture.open(CommitMode.MANUAL, DatabaseKind.MARIADB)
        session.execute("UPDATE a", 10).await()
        assertTrue(session.pendingTransaction)

        session.close()

        fixture.awaitClosed()
        val log = db.log.filter { it.startsWith("session: ") }
        assertEquals(listOf("session: rollback", "session: close"), log.takeLast(2))
        assertEquals(listOf(true, false), fixture.transactionEvents())
    }

    @Test
    fun `close releases an open cursor and commits its own AUTO transaction`() {
        db.results = { FakeResult.Rows(fakeRows(25)) }
        val session = fixture.open()
        val rows = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM t", 10).await())
        db.log.clear()

        session.close()

        fixture.awaitClosed()
        assertTrue(rows.cursor.closed)
        val log = db.log.filter { it.startsWith("session: ") }
        assertEquals(
            listOf(
                "session: close result",
                "session: close statement",
                "session: commit",
                "session: autoCommit=true",
                "session: close",
            ),
            log,
        )
    }

    @Test
    fun `work on the session connection closes the open cursor first`() {
        db.results = { FakeResult.Rows(fakeRows(25)) }
        val session = fixture.open()
        val rows = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM t", 10).await())

        val closedBefore = session.withSession { rows.cursor.closed }.await()

        assertTrue(closedBefore)
        assertTrue("session: commit" in db.log)
    }

    @Test
    fun `close aborts a connection whose work does not end within the grace period`() {
        val hanging = CountDownLatch(1)
        val session = fixture.open(closeGraceMillis = 300)
        try {
            val started = CountDownLatch(1)
            val work =
                session.withMetadata {
                    started.countDown()
                    hanging.await(10, TimeUnit.SECONDS)
                }
            assertTrue(started.await(10, TimeUnit.SECONDS))

            session.close()

            fixture.awaitClosed()
            assertTrue("metadata: abort" in db.log)
            assertFalse("session: abort" in db.log)
            assertIs<DbSessionClosedException>(assertFailsWith<ExecutionException> { work.await() }.cause)
        } finally {
            hanging.countDown()
        }
    }

    @Test
    fun `Closed is the last event, also when work ends after the abort`() {
        val hanging = CountDownLatch(1)
        val session = fixture.open(CommitMode.MANUAL, DatabaseKind.MARIADB, closeGraceMillis = 300)
        try {
            session.execute("UPDATE a", 10).await()
            val started = CountDownLatch(1)
            session.withSession {
                started.countDown()
                hanging.await(10, TimeUnit.SECONDS)
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            session.close()
            fixture.awaitClosed()
        } finally {
            hanging.countDown()
        }

        // The rollback at close clears the pending flag before the connection closes; its event comes too late.
        fixture.eventually("the session connection closed") { "session: close" in db.log }
        assertIs<SessionEvent.Closed>(events.last())
        assertEquals(listOf(true), fixture.transactionEvents())
    }

    @Test
    fun `the Closed event comes on a thread of the session, not the caller's`() {
        val session = fixture.open()
        val closed = CountDownLatch(1)
        var thread: String? = null
        session.addListener {
            if (it is SessionEvent.Closed) {
                thread = Thread.currentThread().name
                closed.countDown()
            }
        }

        session.close()

        assertTrue(closed.await(10, TimeUnit.SECONDS))
        assertTrue(thread?.startsWith("sluide-db-close-") == true, "$thread")
    }
}
