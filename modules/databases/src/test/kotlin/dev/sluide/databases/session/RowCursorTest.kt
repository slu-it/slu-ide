package dev.sluide.databases.session

import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RowCursorTest {
    private val fixture = SessionFixture()
    private val db = fixture.db

    init {
        db.results =
            {
                if (it.startsWith("SELECT") ||
                    it.startsWith("INSERT")
                ) {
                    FakeResult.Rows(fakeRows(25))
                } else {
                    FakeResult.Count(1)
                }
            }
    }

    private lateinit var session: DbSession

    @AfterEach
    fun closeSession() = fixture.close()

    private fun query(
        sql: String = "SELECT * FROM t",
        kind: DatabaseKind = DatabaseKind.POSTGRESQL,
    ): StatementOutcome.Rows {
        session = fixture.open(kind = kind)
        return assertIs(session.execute(sql, 10).await())
    }

    /** Waits for what the session thread does before (a cursor's close): the next statement queues behind it. */
    private fun sessionIdle() = session.execute("UPDATE a", 10).await()

    private fun ids(range: IntRange) = range.map { it * 10 + 1 }

    @Test
    fun `pages are read from the open cursor and stay cached after it closed`() {
        val rows = query()

        val second = rows.cursor.page(1).await()
        rows.cursor.close()
        fixture.eventually("the cursor closed") { rows.cursor.closed }

        assertEquals(ids(10..19), second.firsts())
        assertFalse(second.last)
        assertEquals(second, rows.cursor.page(1).await())
        assertEquals(rows.firstPage, rows.cursor.page(0).await())
        assertFalse(rows.cursor.reExecuted)
        assertEquals(1, db.executed().size)
    }

    @Test
    fun `closing a cursor closes its result and ends its own transaction once`() {
        val rows = query()
        db.log.clear()

        rows.cursor.close()
        fixture.eventually("the cursor closed") { rows.cursor.closed }
        sessionIdle()

        assertEquals(
            listOf("session: close result", "session: close statement", "session: commit", "session: autoCommit=true"),
            db.log.take(4),
        )
        assertEquals(1, db.log.count { it == "session: close result" })
    }

    @Test
    fun `a page beyond the cache of a closed cursor runs the query again and skips the rows before it`() {
        val rows = query()
        rows.cursor.close()

        val third = rows.cursor.page(2).await()

        assertTrue(rows.cursor.reExecuted)
        assertEquals(ids(20..24), third.firsts())
        assertTrue(third.last)
        assertEquals(25, rows.cursor.knownRowCount)
        assertEquals(listOf("SELECT * FROM t", "SELECT * FROM t"), db.executed())
    }

    @Test
    fun `a closed result of a statement that is not a query is not run again`() {
        val rows = query("INSERT INTO t RETURNING id")
        rows.cursor.close()

        val error = assertFailsWith<ExecutionException> { rows.cursor.page(2).await() }

        assertIs<IllegalStateException>(error.cause)
        assertEquals(1, db.executed().size)
    }

    @Test
    fun `the last page and fetching all stop at the cap`() {
        val rows = query()

        val last = rows.cursor.lastPage(15).await()

        assertEquals(1, last.index)
        assertEquals(ids(10..14), last.firsts())
        assertTrue(last.last)
        assertTrue(rows.cursor.capped)
        assertEquals(15, rows.cursor.knownRowCount)
        assertEquals(
            ids(0..14),
            rows.cursor
                .fetchAll(15)
                .await()
                .map { it.first() },
        )
        assertEquals(
            15,
            rows.cursor
                .fetchAll(100)
                .await()
                .size,
        )
    }

    @Test
    fun `the last page and fetching all read to the end below the cap`() {
        val rows = query()
        val bySeven = rows.cursor.withPageSize(7)

        val last = bySeven.lastPage(100).await()

        assertEquals(3, last.index)
        assertEquals(ids(21..24), last.firsts())
        assertFalse(bySeven.capped)
        assertEquals(25, bySeven.knownRowCount)
        assertTrue(rows.cursor.closed)
        assertEquals(
            ids(0..24),
            rows.cursor
                .fetchAll(100)
                .await()
                .map { it.first() },
        )
        assertTrue(rows.cursor.reExecuted, "rows 11 to 20 were read for the last page but not kept")
    }

    @ParameterizedTest
    @CsvSource("MARIADB, true", "POSTGRESQL, false")
    fun `an unread MariaDB result is cancelled before it is closed`(
        kind: DatabaseKind,
        cancelled: Boolean,
    ) {
        val rows = query(kind = kind)
        db.log.clear()

        rows.cursor.close()
        sessionIdle()

        assertEquals(cancelled, db.log.indexOf("session: cancel") in 0..<db.log.indexOf("session: close result"))
    }
}
