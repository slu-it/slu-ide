package dev.sluide.databases.ui

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import java.sql.SQLException
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
