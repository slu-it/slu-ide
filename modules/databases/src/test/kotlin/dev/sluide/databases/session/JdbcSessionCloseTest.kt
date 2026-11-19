package dev.sluide.databases.session

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.AfterEach
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Closing a [JdbcSession], losing its connections, the metadata reconnect, cancel failures and their logs. */
class JdbcSessionCloseTest {
    private val fixture = SessionFixture()
    private val db = fixture.db
    private val loggerName = JdbcSession::class.java.name

    @AfterEach
    fun closeSession() = fixture.close()

    @Test
    fun `metadata work that has not started when the session closes fails as closed and does not run`() {
        val hang = CountDownLatch(1)
        val started = CountDownLatch(1)
        var ran = false
        val session = fixture.open(closeGraceMillis = 60_000)
        val running =
            session.withMetadata {
                started.countDown()
                hang.await(10, TimeUnit.SECONDS)
            }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        val queued = session.withMetadata { ran = true }

        session.close()
        hang.countDown()

        running.await()
        assertIs<DbSessionClosedException>(assertFailsWith<ExecutionException> { queued.await() }.cause)
        assertFalse(ran)
        fixture.awaitClosed()
    }

    @Test
    fun `a removed listener gets no more events`() {
        val session = fixture.open()
        val seen = mutableListOf<SessionEvent>()
        val listener: (SessionEvent) -> Unit = { seen += it }
        session.addListener(listener)
        session.execute("UPDATE a", 10).await()
        val before = seen.size
        assertTrue(before > 0)

        session.removeListener(listener)
        session.execute("UPDATE b", 10).await()

        assertEquals(before, seen.size)
    }

    @Test
    fun `an SQL error of metadata work that is not a lost connection is thrown without reconnecting`() {
        val session = fixture.open()

        val failing = session.withMetadata { throw SQLException("syntax", "42601") }

        assertEquals("42601", (assertFailsWith<ExecutionException> { failing.await() }.cause as SQLException).sqlState)
        assertEquals(emptyList(), db.log.filter { it == "metadata: connected" })
    }

    @Test
    fun `metadata work on a closed metadata connection is opened again whatever the error says, and logged`() {
        val session = fixture.open()
        var calls = 0

        LogCapture(loggerName).use { log ->
            val answer =
                session
                    .withMetadata { connection ->
                        if (calls++ == 0) {
                            connection.close()
                            throw SQLException("closed meanwhile", "42000")
                        }
                        42
                    }.await()

            assertEquals(42, answer)
            val lines = log.lines.filter { "metadata connection" in it.second }
            assertEquals(listOf(Level.WARNING, Level.INFO), lines.map { it.first })
            assertTrue(
                Regex("db#\\d+ metadata connection lost, reconnecting").matches(lines[0].second),
                lines[0].second,
            )
            assertTrue(Regex("db#\\d+ metadata connection reopened").matches(lines[1].second), lines[1].second)
        }
        assertEquals(1, db.log.count { it == "metadata: connected" })
    }

    @Test
    fun `a metadata connection that cannot tell whether it is closed counts as lost`() {
        val session = fixture.open()
        db.failOn("metadata: isClosed")
        var calls = 0

        val answer =
            session
                .withMetadata {
                    if (calls++ == 0) throw SQLException("who knows", "42000")
                    7
                }.await()

        assertEquals(7, answer)
        assertEquals(1, db.log.count { it == "metadata: connected" })
    }

    @Test
    fun `a failing cancel of a statement still running is logged by SQLState, one of a closed statement is not`() {
        val gated = FakeResult.Gated()
        db.results = { gated }
        val session = fixture.open(captureCancels = true)
        val outcome = session.execute("UPDATE a", 10)
        assertTrue(gated.started.await(10, TimeUnit.SECONDS))
        session.cancel()
        session.cancel()
        assertEquals(2, fixture.cancels.size)

        db.failOn("session: cancel")
        LogCapture(loggerName).use { log ->
            fixture.cancels[0].run()
            val line = log.lines.single()
            assertEquals(Level.WARNING, line.first)
            assertTrue(Regex("db#\\d+ cancel failed \\(SQLState XX000\\)").matches(line.second), line.second)
        }

        db.failOn("session: cancel", "session: isClosed")
        LogCapture(loggerName).use { log ->
            fixture.cancels[1].run()
            assertEquals(emptyList(), log.lines.toList())
        }
        gated.gate.countDown()
        assertIs<StatementOutcome.Updated>(outcome.await())
    }

    @Test
    fun `a session connection that fails and cannot say it is valid closes the session`() {
        db.results = { FakeResult.Fails(SQLException("odd failure", "42000")) }
        db.failOn("session: isValid")
        val session = fixture.open()

        assertIs<StatementOutcome.Failed>(session.execute("UPDATE a", 10).await())

        fixture.awaitClosed()
        assertEquals(listOf(SessionEvent.Closed("odd failure")), fixture.events.filterIsInstance<SessionEvent.Closed>())
    }

    @Test
    fun `the Closed message is the trimmed message of the error, else the SQLState`() {
        fun closedMessage(message: String?): String? {
            SessionFixture().use { other ->
                other.db.results = { FakeResult.Fails(SQLException(message, "08006")) }
                other.open().execute("UPDATE a", 10).await()
                other.awaitClosed()
                return other.events
                    .filterIsInstance<SessionEvent.Closed>()
                    .single()
                    .error
            }
        }

        assertEquals("gone", closedMessage("  gone \n"))
        assertEquals("Connection lost (SQLState 08006)", closedMessage("   "))
        assertEquals("Connection lost (SQLState 08006)", closedMessage(null))
    }

    @Test
    fun `an error of work that ends after the close began does not ask the connection whether it is valid`() {
        val session = fixture.open(closeGraceMillis = 60_000)
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val work =
            session.withSession {
                started.countDown()
                proceed.await(10, TimeUnit.SECONDS)
                throw SQLException("odd failure", "42000")
            }
        assertTrue(started.await(10, TimeUnit.SECONDS))

        session.close()
        proceed.countDown()

        assertFailsWith<ExecutionException> { work.await() }
        fixture.awaitClosed()
        assertEquals(0, db.isValidCalls.get())
    }

    @Test
    fun `a close is logged once with the kind, a lost connection with its own line`() {
        LogCapture(loggerName).use { log ->
            val session = fixture.open()

            session.close()
            session.close()
            fixture.awaitClosed()

            val lines = log.lines.filter { "closing" in it.second }
            assertEquals(1, lines.size)
            assertEquals(Level.INFO, lines.single().first)
            assertTrue(
                Regex("db#\\d+ closing \\(${DatabaseKind.POSTGRESQL.label}\\)").matches(lines.single().second),
                lines.single().second,
            )
        }
        SessionFixture().use { other ->
            LogCapture(loggerName).use { log ->
                other
                    .open(
                        kind = DatabaseKind.MARIADB,
                    ).withSession<Unit> { throw SQLException("connection gone", "08006") }
                    .let {
                        assertFailsWith<ExecutionException> { it.await() }
                    }
                other.awaitClosed()

                val line = log.lines.single { "closing" in it.second }
                assertEquals(Level.WARNING, line.first)
                assertTrue(
                    Regex("db#\\d+ connection lost, closing \\(${DatabaseKind.MARIADB.label}\\)").matches(line.second),
                    line.second,
                )
            }
        }
    }

    @Test
    fun `a lost connection forgets the open cursor without closing it on the server`() {
        db.results = { FakeResult.Rows(fakeRows(25)) }
        val session = fixture.open()
        val result = assertIs<StatementOutcome.Rows>(session.execute("SELECT * FROM t", 10).await())
        assertFalse(result.cursor.closed)
        db.log.clear()

        val lost = (session as JdbcSession).onSession { throw SQLException("connection gone", "08006") }
        assertFailsWith<ExecutionException> { lost.await() }
        fixture.awaitClosed()

        assertTrue(result.cursor.closed)
        assertEquals(emptyList(), db.log.filter { it == "session: close result" })
    }

    @Test
    fun `a lost connection clears the pending transaction at once, no rollback`() {
        val session = fixture.open(CommitMode.MANUAL, DatabaseKind.MARIADB)
        session.execute("UPDATE a", 10).await()
        assertTrue(session.pendingTransaction)

        val lost = session.withSession<Unit> { throw SQLException("connection gone", "08006") }
        assertFailsWith<ExecutionException> { lost.await() }
        fixture.awaitClosed()

        assertFalse(session.pendingTransaction)
        assertEquals(listOf(true, false), fixture.transactionEvents())
        assertEquals(emptyList(), db.log.filter { it == "session: rollback" })
    }

    @Test
    fun `work that outlasts the grace period is aborted and logged with the grace period`() {
        val hanging = CountDownLatch(1)
        val session = fixture.open(closeGraceMillis = 300)
        LogCapture(loggerName).use { log ->
            try {
                val started = CountDownLatch(1)
                session.withMetadata {
                    started.countDown()
                    hanging.await(10, TimeUnit.SECONDS)
                }
                assertTrue(started.await(10, TimeUnit.SECONDS))

                session.close()
                fixture.awaitClosed()

                val line = log.lines.single { "aborting" in it.second }
                assertEquals(Level.WARNING, line.first)
                assertTrue(Regex("db#\\d+ work did not end within 300 ms, aborting").matches(line.second), line.second)
            } finally {
                hanging.countDown()
            }
        }
    }

    @Test
    fun `work that ends after the close began but within the grace period is not aborted`() {
        val hanging = CountDownLatch(1)
        val session = fixture.open(closeGraceMillis = 60_000)
        val started = CountDownLatch(1)
        val work =
            session.withMetadata {
                started.countDown()
                hanging.await(10, TimeUnit.SECONDS)
                "finished"
            }
        assertTrue(started.await(10, TimeUnit.SECONDS))

        session.close()
        fixture.eventually("the session connection closed") { "session: close" in db.log }
        // A negative check: an abort without the grace period would come within milliseconds.
        Thread.sleep(300)
        hanging.countDown()

        assertEquals("finished", work.await())
        fixture.awaitClosed()
        assertEquals(emptyList(), db.log.filter { "abort" in it })
    }

    @Test
    fun `a close that finished in time is not aborted when the grace period ends`() {
        val session = fixture.open(closeGraceMillis = 50)

        session.close()
        fixture.awaitClosed()
        // A negative check: the grace period's own task runs 50 ms after the close; 400 ms is well past it.
        Thread.sleep(400)

        assertEquals(emptyList(), db.log.filter { "abort" in it })
    }
}
