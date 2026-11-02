package dev.sluide.databases.session

import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import java.sql.SQLException
import java.sql.SQLTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
