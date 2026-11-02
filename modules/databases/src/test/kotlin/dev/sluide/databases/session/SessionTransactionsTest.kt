package dev.sluide.databases.session

import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.api.AfterEach
import java.sql.SQLException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTransactionsTest {
    private val fixture = SessionFixture()
    private val db = fixture.db

    @AfterEach
    fun closeSession() = fixture.close()

    private fun DbSession.run(sql: String) = execute(sql, 10).await()

    private fun rowsOrCount(sql: String): FakeResult =
        if (sql.startsWith("SELECT")) FakeResult.Rows(fakeRows(25)) else FakeResult.Count(1)

    private fun autoCommitLog() =
        db.log.filter {
            it.startsWith("session: autoCommit") ||
                it.startsWith("session: commit")
        }

    @Test
    fun `in MANUAL mode on MariaDB a statement that is not a query marks the transaction pending until it ends`() {
        db.results = { if (it.startsWith("SELECT")) FakeResult.Rows(fakeRows(1)) else FakeResult.Count(1) }
        val session = fixture.open(CommitMode.MANUAL, DatabaseKind.MARIADB)

        session.run("SELECT 1")
        assertFalse(session.pendingTransaction)
        session.run("UPDATE a")
        assertTrue(session.pendingTransaction)
        session.commit().await()
        assertFalse(session.pendingTransaction)
        session.withSession(changes = true) { }.await()
        assertTrue(session.pendingTransaction)
        session.rollback().await()
        session.run("UPDATE b")
        session.run("COMMIT")

        assertFalse(session.pendingTransaction, "a typed COMMIT ends it too")
        assertEquals(listOf(true, false, true, false, true, false), fixture.transactionEvents())
        assertEquals(
            listOf("session: commit", "session: rollback"),
            db.log.filter { it.endsWith(": commit") || it.endsWith(": rollback") },
        )
    }

    @Test
    fun `in MANUAL mode on PostgreSQL the server tells whether the transaction wrote, the text only when it cannot`() {
        var assigned: Any? = null
        var txidFails = false
        db.results = {
            when {
                it.contains("txid_current_if_assigned") -> {
                    if (txidFails) {
                        FakeResult.Fails(
                            SQLException("no", "42883"),
                        )
                    } else {
                        FakeResult.Rows(listOf(listOf(assigned)))
                    }
                }

                it.startsWith("SELECT") -> {
                    FakeResult.Rows(fakeRows(1))
                }

                else -> {
                    FakeResult.Count(0)
                }
            }
        }
        val session = fixture.open(CommitMode.MANUAL)

        session.run("UPDATE a SET x = 1 WHERE false")
        assertFalse(session.pendingTransaction, "nothing written")
        assigned = 742L
        session.run("SELECT nextval('s')")
        assertTrue(session.pendingTransaction, "a write the text does not show")
        assigned = null
        session.run("COMMIT")
        assertFalse(session.pendingTransaction, "committed by a statement")
        txidFails = true
        session.withSession(changes = false) { }.await()
        assertFalse(session.pendingTransaction)
        session.withSession(changes = true) { }.await()

        assertTrue(session.pendingTransaction)
    }

    @Test
    fun `in MANUAL mode on PostgreSQL a failure rolls back when nothing was written and keeps a pending transaction`() {
        db.results = {
            when (it) {
                "UPDATE ok" -> FakeResult.Count(1)
                "SELECT txid_current_if_assigned()" -> FakeResult.Rows(listOf(listOf(7L)))
                else -> FakeResult.Fails(SQLException("bad", "42601"))
            }
        }
        val session = fixture.open(CommitMode.MANUAL)

        session.run("UPDATE bad")
        assertFalse(session.pendingTransaction)
        assertEquals(1, db.log.count { it == "session: rollback" })
        session.run("UPDATE ok")
        session.run("UPDATE bad")

        assertTrue(session.pendingTransaction)
        assertEquals(1, db.log.count { it == "session: rollback" })
    }

    @Test
    fun `in MANUAL mode on MariaDB a failed change keeps the transaction and marks it pending`() {
        db.results = { FakeResult.Fails(SQLException("Duplicate entry", "23000")) }
        val session = fixture.open(CommitMode.MANUAL, DatabaseKind.MARIADB)

        session.run("SELECT broken")
        assertFalse(session.pendingTransaction)
        session.run("INSERT INTO t VALUES (1)")

        assertTrue(session.pendingTransaction)
        assertEquals(0, db.log.count { it == "session: rollback" })
    }

    @Test
    fun `an AUTO PostgreSQL query turns auto-commit off and commits when the next statement closes it`() {
        db.results = ::rowsOrCount
        val session = fixture.open()
        db.log.clear()

        val rows = assertIs<StatementOutcome.Rows>(session.run("SELECT * FROM t"))
        assertFalse(rows.cursor.closed)
        assertFalse(session.pendingTransaction, "the session's own transaction is not pending")
        assertEquals(listOf("session: autoCommit=false"), autoCommitLog())
        db.log.clear()
        session.run("UPDATE a")

        assertTrue(rows.cursor.closed)
        assertEquals(
            listOf(
                "session: close result",
                "session: close statement",
                "session: commit",
                "session: autoCommit=true",
                "session: fetchSize=10",
                "session: execute UPDATE a",
                "session: close statement",
            ),
            db.log,
        )
        assertEquals(emptyList(), fixture.transactionEvents())
    }

    @Test
    fun `a query whose rows fit the first page ends its transaction at once`() {
        db.results = { FakeResult.Rows(fakeRows(10)) }
        val session = fixture.open()

        val rows = assertIs<StatementOutcome.Rows>(session.run("SELECT * FROM t"))

        assertTrue(rows.firstPage.last)
        assertEquals(10, rows.cursor.knownRowCount)
        assertTrue(rows.cursor.closed)
        assertEquals(listOf("session: commit", "session: autoCommit=true"), db.log.takeLast(2))
    }

    @Test
    fun `auto-commit is restored when an AUTO query fails`() {
        db.results = { FakeResult.Fails(SQLException("bad", "42601")) }
        val session = fixture.open()
        db.log.clear()

        assertIs<StatementOutcome.Failed>(session.run("SELECT broken"))

        assertEquals(
            listOf("session: autoCommit=false", "session: rollback", "session: autoCommit=true"),
            db.log.filter {
                "autoCommit" in
                    it ||
                    it.endsWith("rollback")
            },
        )
        assertFalse(session.pendingTransaction)
    }

    @Test
    fun `auto-commit is restored when reading a page of an AUTO query fails`() {
        db.results = { FakeResult.Rows(fakeRows(25), failAt = 15) }
        val session = fixture.open()
        val rows = assertIs<StatementOutcome.Rows>(session.run("SELECT * FROM t"))
        db.log.clear()

        assertFailsWith<ExecutionException> { rows.cursor.page(1).await() }

        assertTrue(rows.cursor.closed)
        assertEquals(
            listOf("session: rollback", "session: autoCommit=true"),
            db.log.filter {
                "autoCommit" in it ||
                    it.endsWith("rollback") ||
                    it.endsWith("commit")
            },
        )
    }

    @Test
    fun `in AUTO mode a transaction the user opened is pending, and a PostgreSQL query runs inside it untouched`() {
        db.results = ::rowsOrCount
        val session = fixture.open()
        db.log.clear()

        session.run("BEGIN")
        assertTrue(session.pendingTransaction)
        val rows = assertIs<StatementOutcome.Rows>(session.run("SELECT * FROM t"))
        session.run("INSERT INTO t VALUES (1)")
        assertTrue(rows.cursor.closed)
        assertTrue(session.pendingTransaction)
        session.run("ROLLBACK")

        assertFalse(session.pendingTransaction)
        assertEquals(emptyList(), autoCommitLog(), "no auto-commit toggle, no commit")
        assertEquals(listOf(true, false), fixture.transactionEvents())
    }

    /** The fake connections of MariaDB sessions have no transaction state, so this covers the text fallback. */
    @Test
    fun `an AUTO MariaDB query has no transaction of its own, BEGIN and COMMIT tell what is pending`() {
        db.results = ::rowsOrCount
        val session = fixture.open(kind = DatabaseKind.MARIADB)
        db.log.clear()

        session.run("SELECT * FROM t")
        session.run("START TRANSACTION")
        assertTrue(session.pendingTransaction)
        session.run("SELECT * FROM t")
        assertTrue(session.pendingTransaction)
        session.run("COMMIT")

        assertFalse(session.pendingTransaction)
        assertEquals(emptyList(), autoCommitLog())
    }

    @Test
    fun `Commit in AUTO mode ends a transaction the user opened with a COMMIT statement`() {
        val session = fixture.open()
        session.run("BEGIN")

        session.commit().await()

        assertEquals(listOf("BEGIN", "COMMIT"), db.executed())
        assertFalse(session.pendingTransaction)
    }

    @Test
    fun `Roll Back in AUTO mode ends a transaction the user opened with a ROLLBACK statement`() {
        val session = fixture.open()
        session.run("BEGIN")

        session.rollback().await()

        assertEquals(listOf("BEGIN", "ROLLBACK"), db.executed())
        assertFalse(session.pendingTransaction)
    }

    @Test
    fun `inside a user's AUTO transaction PostgreSQL reads at most the fetch-all cap and one row more`() {
        db.results = ::rowsOrCount
        fixture.fetchAllCap = 15
        val session = fixture.open()
        session.run("SELECT 1 FROM t")
        assertEquals(emptyList(), db.log.filter { "maxRows" in it }, "not outside a user's transaction")
        session.run("BEGIN")

        val rows = assertIs<StatementOutcome.Rows>(session.run("SELECT * FROM t"))
        val last = rows.cursor.lastPage(100).await()

        assertEquals(listOf("session: maxRows=16"), db.log.filter { "maxRows" in it })
        assertTrue(rows.cursor.capped)
        assertEquals(15, rows.cursor.knownRowCount)
        assertEquals((10..14).map { it * 10 + 1 }, last.firsts())
        assertTrue(session.pendingTransaction)
    }

    @Test
    fun `leaving MANUAL mode turns auto-commit on and clears the pending flag`() {
        val session = fixture.open(CommitMode.MANUAL)
        session.run("UPDATE a")

        session.setCommitMode(CommitMode.AUTO).await()

        assertEquals(CommitMode.AUTO, session.commitMode)
        assertFalse(session.pendingTransaction)
        assertEquals("session: autoCommit=true", db.log.last())
    }

    @Test
    fun `the text tells statements that open and end a transaction`() {
        assertEquals(true, SessionTransactions.transactionText("  begin"))
        assertEquals(true, SessionTransactions.transactionText("START TRANSACTION READ ONLY"))
        assertEquals(false, SessionTransactions.transactionText("commit"))
        assertEquals(false, SessionTransactions.transactionText("ROLLBACK WORK"))
        assertNull(SessionTransactions.transactionText("ROLLBACK TO SAVEPOINT a"))
        assertNull(SessionTransactions.transactionText("SELECT 1"))
    }
}
