package dev.sluide.databases.session

import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import java.sql.SQLException
import java.sql.SQLTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlFailuresTest {
    private fun pgError(position: Int) =
        PSQLException(
            ServerErrorMessage("SERROR\u0000C42601\u0000Msyntax error at or near \"FORM\"\u0000P$position\u0000"),
        )

    @Test
    fun `PostgreSQL's 1-based position in characters becomes a 0-based offset into the SQL`() {
        val plain = "SELECT 1 FORM t"
        assertEquals('F', plain[assertNotNull(SqlFailures.position(pgError(10), plain))])
        // An emoji is one character for PostgreSQL and two chars in Java: FORM is character 12, Java index 12.
        val emoji = "SELECT '😀' FORM t"
        assertEquals(12, SqlFailures.position(pgError(12), emoji))
        assertEquals('F', emoji[12])
        assertNull(SqlFailures.position(pgError(99), "SELECT"))
        assertNull(SqlFailures.position(SQLException("no position"), "SELECT"))
    }

    @Test
    fun `a failure keeps message, SQLState and position`() {
        val failed = assertIs<StatementOutcome.Failed>(SqlFailures.outcome("SELECT 1 FORM t", 3, pgError(10), false, 0))

        assertEquals("42601", failed.sqlState)
        assertEquals(9, failed.position)
        assertTrue(failed.message.contains("syntax error"))
    }

    @Test
    fun `a cancel is Cancelled, unless a statement timeout is set and the user did not cancel`() {
        val pgCancel = SQLException("ERROR: canceling statement due to user request", "57014")
        val mariaKill = SQLException("Query execution was interrupted", "70100")

        assertIs<StatementOutcome.Cancelled>(
            SqlFailures.outcome("x", 1, pgCancel, userCancelled = true, timeoutSeconds = 5),
        )
        assertIs<StatementOutcome.Cancelled>(
            SqlFailures.outcome("x", 1, mariaKill, userCancelled = false, timeoutSeconds = 0),
        )
        val timedOut = assertIs<StatementOutcome.Failed>(SqlFailures.outcome("x", 1, pgCancel, false, 5))
        assertTrue(timedOut.message.startsWith("Statement timeout of 5 s exceeded"))
        assertIs<StatementOutcome.Failed>(SqlFailures.outcome("x", 1, SQLTimeoutException("timeout"), false, 5))
    }

    @Test
    fun `SQLState class 08 is a lost connection`() {
        assertTrue(SqlFailures.isConnectionLost(SQLException("reset", "08006")))
        assertEquals(false, SqlFailures.isConnectionLost(SQLException("syntax", "42601")))
    }

    @Test
    fun `the position is cut at the end of the SQL and refused beyond it or at zero`() {
        assertEquals(0, SqlFailures.position(pgError(1), "SELECT"))
        assertEquals(6, SqlFailures.position(pgError(7), "SELECT"), "just behind the last character")
        assertNull(SqlFailures.position(pgError(8), "SELECT"))
        assertNull(SqlFailures.position(pgError(0), "SELECT"))
        assertNull(SqlFailures.position(pgError(-3), "SELECT"))
        assertNull(SqlFailures.position(PSQLException("no server message", null), "SELECT"))
        assertEquals(2, SqlFailures.position(pgError(2), "😀 x"), "the emoji is one character")
    }

    @Test
    fun `the message of a failure is trimmed, and an empty or missing one is replaced by the exception's name`() {
        fun message(error: Throwable) =
            assertIs<StatementOutcome.Failed>(SqlFailures.outcome("x", 1, error, false, 0)).message

        assertEquals("boom", message(SQLException("  boom \n", "42000")))
        assertEquals("SQLException", message(SQLException("  ", "42000")))
        assertEquals("SQLException", message(SQLException(null as String?, "42000")))
        assertEquals("IllegalStateException", message(IllegalStateException()))
    }

    @Test
    fun `a failure that is no cancel keeps its position and SQLState also when a timeout is set`() {
        val failed = assertIs<StatementOutcome.Failed>(SqlFailures.outcome("SELECT 1 FORM t", 3, pgError(10), false, 5))

        assertEquals("42601", failed.sqlState)
        assertEquals(9, failed.position)
        assertFalse(failed.message.startsWith("Statement timeout"))
    }

    @Test
    fun `a failure that is not an SQLException has no SQLState`() {
        val failed =
            assertIs<StatementOutcome.Failed>(SqlFailures.outcome("x", 1, IllegalStateException("odd"), false, 5))

        assertNull(failed.sqlState)
        assertNull(failed.position)
        assertEquals("odd", failed.message)
    }

    @Test
    fun `a statement timeout names the seconds, keeps the SQLState and the message and has no position`() {
        val cancel = SQLException("canceling statement due to statement timeout", "57014")

        val failed = assertIs<StatementOutcome.Failed>(SqlFailures.outcome("SELECT pg_sleep(9)", 7, cancel, false, 1))

        assertEquals("Statement timeout of 1 s exceeded: canceling statement due to statement timeout", failed.message)
        assertEquals("57014", failed.sqlState)
        assertNull(failed.position)
        assertEquals(7, failed.millis)
        assertEquals("SELECT pg_sleep(9)", failed.sql)
    }

    @Test
    fun `a negative statement timeout counts as none`() {
        assertIs<StatementOutcome.Cancelled>(
            SqlFailures.outcome("x", 1, SQLException("c", "57014"), userCancelled = false, timeoutSeconds = -1),
        )
        val plain = assertIs<StatementOutcome.Failed>(SqlFailures.outcome("x", 1, SQLTimeoutException("t"), false, 0))
        assertEquals("t", plain.message)
    }

    @Test
    fun `a user cancel is Cancelled whatever the error was`() {
        val cancelled =
            assertIs<StatementOutcome.Cancelled>(
                SqlFailures.outcome("x", 4, IllegalStateException("closed"), userCancelled = true, timeoutSeconds = 0),
            )

        assertEquals(4, cancelled.millis)
        assertEquals("x", cancelled.sql)
    }

    @Test
    fun `a cancel is told by SQLState 57014, SQLState 70100 or the interrupt message, not by anything else`() {
        assertTrue(SqlFailures.isCancel(SQLException("cancelled", "57014")))
        assertTrue(SqlFailures.isCancel(SQLException("whatever", "70100")))
        assertTrue(SqlFailures.isCancel(SQLException("Query execution was interrupted", "HY000")))
        assertTrue(SqlFailures.isCancel(IllegalStateException("Query execution was interrupted (max_statement_time)")))
        assertEquals(false, SqlFailures.isCancel(SQLException("syntax", "42601")))
        assertEquals(false, SqlFailures.isCancel(SQLException("syntax", null as String?)))
        assertEquals(false, SqlFailures.isCancel(IllegalStateException("closed")))
        assertEquals(false, SqlFailures.isCancel(IllegalStateException()))
    }

    @Test
    fun `only an SQLState that starts with 08 is a lost connection`() {
        assertTrue(SqlFailures.isConnectionLost(SQLException("down", "08S01")))
        assertEquals(false, SqlFailures.isConnectionLost(SQLException("x", "42008")))
        assertEquals(false, SqlFailures.isConnectionLost(SQLException("x", null as String?)))
        assertEquals(false, SqlFailures.isConnectionLost(IllegalStateException("08006")))
    }
}
