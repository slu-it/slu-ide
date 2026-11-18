package dev.sluide.databases.ui

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import org.postgresql.util.ServerErrorMessage
import java.net.ConnectException
import java.sql.SQLException
import java.sql.SQLInvalidAuthorizationSpecException
import java.sql.SQLNonTransientConnectionException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The text of a failed connect: no URL credentials, no password, no MariaDB connection prefix. */
class ConnectFailuresTest {
    private val config = ConnectionConfig("o", "orders", DatabaseKind.POSTGRESQL, host = "db", user = "app")

    @Test
    fun `a password the driver echoes outside the URL is masked`() {
        val error = SQLException("FATAL: password authentication failed: user app, password hunter22 rejected")

        assertEquals(
            "FATAL: password authentication failed: user app, password ******** rejected",
            ConnectFailures.message(CompletionException(error), config, "hunter22".toCharArray()),
        )
    }

    @Test
    fun `passwords shorter than 4 characters are not masked, so ordinary words keep their letters`() {
        val error = SQLException("(conn=12) Access denied for user 'app'")

        assertEquals("Access denied for user 'app'", ConnectFailures.message(error, config, "ss".toCharArray()))
    }

    @Test
    fun `the URL is shown without its credentials`() {
        val custom = config.copy(customUrl = "jdbc:postgresql://app:hunter22@db/orders?ssl=true")
        val error = SQLException("No suitable driver found for jdbc:postgresql://app:hunter22@db/orders?ssl=true")

        assertEquals(
            "No suitable driver found for jdbc:postgresql://db/orders",
            ConnectFailures.message(error, custom, null),
        )
    }

    // The shapes below are what the drivers on the classpath throw (pgjdbc 42.7.13, MariaDB Connector/J 3.5.10),
    // read with a probe program that connected to 127.0.0.1:1 and to nohost.invalid, and from
    // `javap -c org.mariadb.jdbc.export.ExceptionFactory` (28000 becomes SQLInvalidAuthorizationSpecException,
    // its message starts with "(conn=N) ").
    private fun pgRefused() =
        PSQLException(
            "Connection to 127.0.0.1:1 refused. Check that the hostname and port are correct and that the " +
                "postmaster is accepting TCP/IP connections.",
            PSQLState.CONNECTION_UNABLE_TO_CONNECT,
            ConnectException("Connection refused (connect failed)"),
        )

    private fun pgBadPassword() =
        PSQLException(
            ServerErrorMessage(
                "SFATAL\u0000VFATAL\u0000C28P01\u0000Mpassword authentication failed for user \"app\"\u0000",
            ),
        )

    private fun mariaDenied() =
        SQLInvalidAuthorizationSpecException(
            "(conn=12) Access denied for user 'app'@'10.0.0.5' (using password: YES)",
            "28000",
            1045,
        )

    /** [inner] under [levels] RuntimeExceptions named "outer". */
    private fun wrapped(
        levels: Int,
        inner: Throwable,
    ): Throwable = (1..levels).fold(inner) { cause, _ -> RuntimeException("outer", cause) }

    @Test
    fun `the message of a refused connection is the driver's own`() {
        assertEquals(
            "Connection to 127.0.0.1:1 refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.",
            ConnectFailures.message(CompletionException(pgRefused()), config, null),
        )
        assertEquals(
            "Socket fail to connect to 127.0.0.1:1. Connection refused (connect failed)",
            ConnectFailures.message(
                ExecutionException(
                    SQLNonTransientConnectionException(
                        "(conn=3) Socket fail to connect to 127.0.0.1:1. Connection refused (connect failed)",
                        "08000",
                    ),
                ),
                config,
                null,
            ),
        )
    }

    @Test
    fun `the first SQL exception of the cause chain gives the message, within ten levels`() {
        val real = SQLException("the real reason", "28000")

        assertEquals("the real reason", ConnectFailures.message(wrapped(1, real), config, null))
        assertEquals("the real reason", ConnectFailures.message(wrapped(9, real), config, null))
        assertEquals("outer", ConnectFailures.message(wrapped(10, real), config, null))
    }

    @Test
    fun `a message that is missing or blank is replaced by the name of the exception that was thrown`() {
        assertEquals("IllegalStateException", ConnectFailures.message(IllegalStateException(), config, null))
        assertEquals("IllegalStateException", ConnectFailures.message(IllegalStateException(" \n "), config, null))
        assertEquals("boom", ConnectFailures.message(IllegalStateException("  boom\n"), config, null))
        assertEquals(
            "RuntimeException",
            ConnectFailures.message(RuntimeException(null, SQLException(null as String?)), config, null),
        )
        assertEquals("SQLException", ConnectFailures.message(CompletionException(SQLException("  ")), config, null))
    }

    @Test
    fun `a wrapper without a cause is shown as it is, only completion wrappers are opened`() {
        assertEquals("lost", ConnectFailures.message(CompletionException("lost", null), config, null))
        assertEquals("lost", ConnectFailures.message(ExecutionException("lost", null), config, null))
        assertEquals(
            "inner",
            ConnectFailures.message(
                CompletionException(ExecutionException(IllegalStateException("inner"))),
                config,
                null,
            ),
        )
        assertEquals("outer", ConnectFailures.futureMessage(RuntimeException("outer", IllegalStateException("inner"))))
    }

    @ParameterizedTest
    @CsvSource("abc,false", "abcd,true", "abcde,true")
    fun `a password is masked from 4 characters on`(
        password: String,
        masked: Boolean,
    ) {
        val text =
            ConnectFailures.message(
                SQLException("rejected $password twice: $password"),
                config,
                password.toCharArray(),
            )

        assertEquals(if (masked) "rejected ******** twice: ********" else "rejected $password twice: $password", text)
    }

    @Test
    fun `without a password nothing is masked`() {
        assertEquals("rejected hunter22", ConnectFailures.message(SQLException("rejected hunter22"), config, null))
        assertEquals(
            "rejected hunter22",
            ConnectFailures.message(SQLException("rejected hunter22"), config, CharArray(0)),
        )
    }

    @Test
    fun `the generated URL in a message is shown as the card shows it`() {
        val error = SQLException("Cannot reach jdbc:postgresql://db:5432/orders now")

        assertEquals(
            "Cannot reach app@db:5432/orders now",
            ConnectFailures.message(error, config.copy(database = "orders"), null),
        )
    }

    @Test
    fun `a failed login is told by SQLState 28P01 or 28000, or by the MariaDB code 1045`() {
        assertTrue(ConnectFailures.isAuthFailure(pgBadPassword(), DatabaseKind.POSTGRESQL))
        assertTrue(ConnectFailures.isAuthFailure(SQLException("x", "28000"), DatabaseKind.POSTGRESQL))
        assertTrue(ConnectFailures.isAuthFailure(mariaDenied(), DatabaseKind.MARIADB))
        assertTrue(ConnectFailures.isAuthFailure(SQLException("x", "HY000", 1045), DatabaseKind.MARIADB))
        assertFalse(
            ConnectFailures.isAuthFailure(SQLException("x", "HY000", 1045), DatabaseKind.POSTGRESQL),
            "1045 is MariaDB's",
        )
        assertFalse(ConnectFailures.isAuthFailure(SQLException("x", "HY000", 1046), DatabaseKind.MARIADB))
        assertFalse(ConnectFailures.isAuthFailure(pgRefused(), DatabaseKind.POSTGRESQL))
        assertFalse(ConnectFailures.isAuthFailure(SQLException("x", "42501"), DatabaseKind.MARIADB))
        assertFalse(ConnectFailures.isAuthFailure(IllegalStateException("28000"), DatabaseKind.MARIADB))
    }

    @Test
    fun `a failed login is found under wrappers, within ten levels`() {
        assertTrue(
            ConnectFailures.isAuthFailure(
                CompletionException(ExecutionException(pgBadPassword())),
                DatabaseKind.POSTGRESQL,
            ),
        )
        assertTrue(ConnectFailures.isAuthFailure(wrapped(9, pgBadPassword()), DatabaseKind.POSTGRESQL))
        assertFalse(ConnectFailures.isAuthFailure(wrapped(10, pgBadPassword()), DatabaseKind.POSTGRESQL))
        assertTrue(ConnectFailures.isAuthFailure(wrapped(2, mariaDenied()), DatabaseKind.MARIADB))
        assertTrue(
            ConnectFailures.isAuthFailure(
                SQLException("a", "08001", RuntimeException("y", pgBadPassword())),
                DatabaseKind.POSTGRESQL,
            ),
        )
    }

    @Test
    fun `the message of a failed commit, rollback or mode switch is trimmed and falls back to the exception's name`() {
        assertEquals(
            "connection closed",
            ConnectFailures.futureMessage(CompletionException(SQLException(" connection closed "))),
        )
        assertEquals("SQLException", ConnectFailures.futureMessage(ExecutionException(SQLException(" "))))
        assertEquals("IllegalStateException", ConnectFailures.futureMessage(IllegalStateException()))
        assertEquals("lost", ConnectFailures.futureMessage(CompletionException("lost", null)))
        assertEquals("ExecutionException", ConnectFailures.futureMessage(ExecutionException(null as Throwable?)))
    }
}
