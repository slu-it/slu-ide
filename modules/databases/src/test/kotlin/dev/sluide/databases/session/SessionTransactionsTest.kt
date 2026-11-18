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

    // ---- SessionTransactions on its own, over the fake connection: each rule with the inputs that tell it ----

    /** A [SessionTransactions] over the fake session connection; [pending] and [sets] are what it reads and writes. */
    private inner class Harness(
        val kind: DatabaseKind,
        var mode: CommitMode,
        var pending: Boolean = false,
        var ownOpen: Boolean = false,
        exposesState: Boolean = kind == DatabaseKind.POSTGRESQL,
    ) {
        val sets = mutableListOf<Boolean>()
        val transactions: SessionTransactions

        init {
            db.exposesTransactionState = exposesState
            val statements = SessionStatements(db.session, kind, 1, {}, {})
            transactions =
                SessionTransactions(
                    db.session,
                    kind,
                    statements,
                    { mode },
                    { pending },
                    {
                        sets += it
                        pending = it
                    },
                    { ownOpen },
                )
        }
    }

    /** The txid query `serverWrote` asks: [wrote] true / false, null when the server cannot tell. */
    private fun txid(wrote: Boolean?) {
        db.results = {
            when {
                !it.contains("txid_current_if_assigned") -> FakeResult.Count(1)
                wrote == null -> FakeResult.Fails(SQLException("aborted", "25P02"))
                else -> FakeResult.Rows(listOf(listOf(if (wrote) 742L else null)))
            }
        }
    }

    private fun openServerTransaction() {
        db.session.createStatement().use { it.execute("BEGIN") }
    }

    @Test
    fun `a query gets its own transaction only in AUTO mode, on PostgreSQL, with none open`() {
        val auto = Harness(DatabaseKind.POSTGRESQL, CommitMode.AUTO)
        assertTrue(auto.transactions.ownTransactionFor(query = true))
        assertFalse(auto.transactions.ownTransactionFor(query = false), "a change runs in auto-commit")
        auto.mode = CommitMode.MANUAL
        assertFalse(auto.transactions.ownTransactionFor(query = true), "MANUAL has one already")
        auto.mode = CommitMode.AUTO
        openServerTransaction()
        assertFalse(auto.transactions.ownTransactionFor(query = true), "the user's BEGIN is open")
    }

    @Test
    fun `a query gets no transaction of its own when the kind is not PostgreSQL or the server's state is unknown`() {
        // the fake reports pgjdbc's transaction state for a MariaDB kind here, to isolate the kind check
        val maria = Harness(DatabaseKind.MARIADB, CommitMode.AUTO, exposesState = true)
        assertFalse(maria.transactions.ownTransactionFor(query = true))
        val unknown = Harness(DatabaseKind.POSTGRESQL, CommitMode.AUTO, exposesState = false)
        assertFalse(unknown.transactions.ownTransactionFor(query = true), "no state, no guess")
    }

    @Test
    fun `MANUAL on PostgreSQL follows the server on writes, else the old flag or the text rule`() {
        val h = Harness(DatabaseKind.POSTGRESQL, CommitMode.MANUAL)

        txid(true)
        h.transactions.succeeded("SELECT nextval('s')", textRule = false)
        assertEquals(listOf(true), h.sets)
        txid(false)
        h.transactions.succeeded("UPDATE a SET x = 1 WHERE false", textRule = true)
        assertEquals(listOf(true, false), h.sets, "the server says nothing was written")

        txid(null)
        h.pending = true
        h.transactions.succeeded("SELECT 1", textRule = false)
        assertTrue(h.pending, "an earlier write stays")
        h.pending = false
        h.transactions.succeeded("UPDATE a", textRule = true)
        assertTrue(h.pending, "the text rule")
        h.pending = false
        h.transactions.succeeded(null, textRule = false)
        assertFalse(h.pending)
    }

    @Test
    fun `MANUAL on MariaDB follows the text, never the txid query`() {
        val h = Harness(DatabaseKind.MARIADB, CommitMode.MANUAL)
        txid(false)

        h.transactions.succeeded("UPDATE a", textRule = true)
        assertTrue(h.pending, "a change marks it, whatever a PostgreSQL query would say")
        h.transactions.succeeded("COMMIT", textRule = true)
        assertFalse(h.pending)
        h.transactions.succeeded("BEGIN", textRule = true)
        assertTrue(h.pending)
        h.transactions.succeeded("ROLLBACK", textRule = true)
        assertFalse(h.pending)
        h.transactions.succeeded("START TRANSACTION", textRule = false)
        assertTrue(h.pending, "the text of BEGIN decides, not the text rule")
        assertEquals(emptyList(), db.log.filter { "txid" in it })

        h.pending = true
        h.transactions.succeeded("SELECT 1", textRule = false)
        assertTrue(h.pending, "a query keeps what is pending")
        h.pending = false
        h.transactions.succeeded(null, textRule = true)
        assertTrue(h.pending, "work with changes")
        h.pending = false
        h.transactions.succeeded(null, textRule = false)
        assertFalse(h.pending)
    }

    @Test
    fun `in AUTO mode the server's transaction decides, except the session's own, else the text`() {
        val h = Harness(DatabaseKind.POSTGRESQL, CommitMode.AUTO)
        h.transactions.succeeded("SELECT 1", textRule = false)
        assertFalse(h.pending)

        openServerTransaction()
        h.transactions.succeeded("SELECT 1", textRule = false)
        assertTrue(h.pending)
        h.ownOpen = true
        h.transactions.succeeded("SELECT 1", textRule = false)
        assertFalse(h.pending, "the open result's own transaction is not pending")

        val blind = Harness(DatabaseKind.MARIADB, CommitMode.AUTO)
        blind.transactions.succeeded("START TRANSACTION", textRule = true)
        assertTrue(blind.pending)
        blind.transactions.succeeded("SELECT 1", textRule = false)
        assertTrue(blind.pending, "nothing in the text changes it")
        blind.transactions.succeeded(null, textRule = false)
        assertTrue(blind.pending)
        blind.transactions.succeeded("COMMIT", textRule = true)
        assertFalse(blind.pending)
        blind.transactions.succeeded("SELECT 1", textRule = false)
        assertFalse(blind.pending)
    }

    @Test
    fun `a failure shows the server transaction in AUTO, rolls back in MANUAL on PostgreSQL if none is pending`() {
        val auto = Harness(DatabaseKind.POSTGRESQL, CommitMode.AUTO)
        openServerTransaction()
        auto.transactions.failed("SELECT 1/0", textRule = false)
        assertEquals(listOf(true), auto.sets, "the aborted transaction is still open")

        val manual = Harness(DatabaseKind.POSTGRESQL, CommitMode.MANUAL)
        db.log.clear()
        manual.transactions.failed("UPDATE bad", textRule = true)
        assertEquals(listOf("session: rollback"), db.log.toList())
        assertEquals(emptyList(), manual.sets)
        db.log.clear()
        manual.pending = true
        manual.transactions.failed("UPDATE bad", textRule = true)
        assertEquals(emptyList(), db.log.toList(), "work was written before: the user decides")
    }

    @Test
    fun `a rollback that fails after a failed statement is ignored`() {
        val manual = Harness(DatabaseKind.POSTGRESQL, CommitMode.MANUAL)
        db.failOn("session: rollback")

        manual.transactions.failed("UPDATE bad", textRule = true)

        assertEquals(emptyList(), manual.sets)
    }

    @Test
    fun `a failure in MANUAL mode on MariaDB marks the transaction when the text rule says it changes something`() {
        val manual = Harness(DatabaseKind.MARIADB, CommitMode.MANUAL)
        db.log.clear()

        manual.transactions.failed("SELECT broken", textRule = false)
        assertEquals(emptyList(), manual.sets)
        manual.transactions.failed("INSERT INTO t VALUES (1)", textRule = true)
        assertEquals(listOf(true), manual.sets)
        assertEquals(emptyList(), db.log.toList(), "MariaDB keeps the transaction")
    }

    @Test
    fun `end commits or rolls back through JDBC while auto-commit is off`() {
        val h = Harness(DatabaseKind.POSTGRESQL, CommitMode.MANUAL, pending = true)
        db.session.autoCommit = false
        db.log.clear()

        h.transactions.end(commit = true)
        h.pending = true
        h.transactions.end(commit = false)

        assertEquals(listOf("session: commit", "session: rollback"), db.log.toList())
        assertEquals(listOf(false, false), h.sets)
    }

    @Test
    fun `end in auto-commit sends COMMIT or ROLLBACK only when a transaction is pending or the server has one open`() {
        val h = Harness(DatabaseKind.POSTGRESQL, CommitMode.AUTO)

        h.transactions.end(commit = true)
        assertEquals(emptyList(), db.executed(), "nothing open")
        h.pending = true
        h.transactions.end(commit = true)
        h.pending = true
        h.transactions.end(commit = false)
        assertEquals(listOf("COMMIT", "ROLLBACK"), db.executed())
        assertFalse(h.pending)

        openServerTransaction()
        h.transactions.end(commit = false)
        assertEquals(listOf("COMMIT", "ROLLBACK", "BEGIN", "ROLLBACK"), db.executed(), "open on the server, not marked")

        val blind = Harness(DatabaseKind.POSTGRESQL, CommitMode.AUTO, exposesState = false)
        db.log.clear()
        blind.transactions.end(commit = true)
        assertEquals(emptyList(), db.executed(), "without the state an unmarked session has nothing to end")
    }

    @Test
    fun `switching to AUTO turns auto-commit on and clears the flag, to MANUAL turns it off and keeps the flag`() {
        val h = Harness(DatabaseKind.MARIADB, CommitMode.MANUAL, pending = true)
        db.log.clear()

        h.transactions.switchTo(CommitMode.AUTO)
        assertEquals(listOf("session: autoCommit=true"), db.log.toList())
        assertEquals(listOf(false), h.sets)

        db.log.clear()
        h.sets.clear()
        h.pending = true
        h.transactions.switchTo(CommitMode.MANUAL)
        assertEquals(listOf("session: autoCommit=false"), db.log.toList())
        assertEquals(listOf(true), h.sets, "an open BEGIN stays pending")
    }

    @Test
    fun `switching to MANUAL on PostgreSQL asks the server whether the open transaction wrote`() {
        val h = Harness(DatabaseKind.POSTGRESQL, CommitMode.MANUAL)
        txid(true)

        h.transactions.switchTo(CommitMode.MANUAL)

        assertEquals(listOf(true), h.sets)
        assertTrue(db.log.any { "txid_current_if_assigned" in it })
    }

    @Test
    fun `the text of BEGIN and COMMIT is read at the start, in any case, with words kept apart`() {
        assertEquals(true, SessionTransactions.transactionText("begin"))
        assertEquals(true, SessionTransactions.transactionText("Start\n  Transaction"))
        assertEquals(false, SessionTransactions.transactionText("END"))
        assertEquals(false, SessionTransactions.transactionText("rollback and chain"))
        assertNull(SessionTransactions.transactionText("rollback to savepoint a"))
        assertNull(SessionTransactions.transactionText("ROLLBACK WORK TO a"))
        assertNull(SessionTransactions.transactionText("beginning"))
        assertNull(SessionTransactions.transactionText("select 1; begin"))
        assertNull(SessionTransactions.transactionText("commits"))
        assertNull(SessionTransactions.transactionText("start transactions"))
    }
}
