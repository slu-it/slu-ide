package dev.sluide.databases.ui.dialog

import dev.sluide.databases.connect.CardColor
import dev.sluide.databases.connect.CommitMode
import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.connect.SslMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConnectionFormTest {
    private fun form(
        config: ConnectionConfig = ConnectionConfig("id1", "Local", DatabaseKind.POSTGRESQL),
        isNew: Boolean = true,
        names: Set<String> = emptySet(),
    ) = ConnectionForm.of(config, isNew, names)

    @Test
    fun `a default form is valid`() {
        assertNull(form().validation())
    }

    @ParameterizedTest
    @CsvSource(
        "'', localhost, 5432, Enter a name.",
        "'  ', localhost, 5432, Enter a name.",
        "Taken, localhost, 5432, A connection named 'Taken' exists.",
        "tAKEN, localhost, 5432, A connection named 'tAKEN' exists.",
        "Ok, '', 5432, Enter a host.",
        "Ok, localhost, 0, The port must be a number from 1 to 65535.",
        "Ok, localhost, 65536, The port must be a number from 1 to 65535.",
        "Ok, localhost, abc, The port must be a number from 1 to 65535.",
    )
    fun `the first problem is reported`(
        name: String,
        host: String,
        port: String,
        expected: String,
    ) {
        val form = form(names = setOf("Taken")).copy(name = name, host = host, portText = port)
        assertEquals(expected, form.validation())
    }

    @Test
    fun `an edited connection may keep its own name but not take another`() {
        val config = ConnectionConfig("id1", "Local", DatabaseKind.POSTGRESQL)
        val names = setOf("Local", "Other")
        assertNull(form(config, isNew = false, names = names).validation())
        assertEquals(
            "A connection named 'other' exists.",
            form(config, isNew = false, names = names).copy(name = "other").validation(),
        )
    }

    @Test
    fun `a custom URL needs no host but the prefix of the kind`() {
        val base = form().copy(host = "")
        assertNull(base.copy(customUrl = "jdbc:postgresql://x/y").validation())
        assertNull(base.copy(customUrl = "jdbc:postgresql://x/y", portText = "").validation(), "no port check either")
        assertEquals(
            "The URL must start with jdbc:postgresql:.",
            base.copy(customUrl = "jdbc:mariadb://x").validation(),
        )
        assertEquals(
            "The URL must start with jdbc:mariadb:.",
            base.withKind(DatabaseKind.MARIADB).copy(customUrl = "").validation(),
        )
    }

    @Test
    fun `driver property keys are non blank and unique`() {
        assertEquals("A driver property has no key.", form().copy(driverProperties = listOf(" " to "1")).validation())
        assertEquals(
            "A driver property key appears twice.",
            form().copy(driverProperties = listOf("a" to "1", "a" to "2")).validation(),
        )
        assertNull(form().copy(driverProperties = listOf("a" to "1", "b" to "1")).validation())
    }

    @Test
    fun `switching the kind moves a default port and keeps a custom one`() {
        val pg = form()
        assertEquals("3306", pg.withKind(DatabaseKind.MARIADB).portText)
        assertEquals(DatabaseKind.MARIADB, pg.withKind(DatabaseKind.MARIADB).kind)
        assertEquals("6000", pg.copy(portText = "6000").withKind(DatabaseKind.MARIADB).portText)
        assertEquals("5432", pg.withKind(DatabaseKind.MARIADB).withKind(DatabaseKind.POSTGRESQL).portText)
    }

    @Test
    fun `a full config survives the form`() {
        val config =
            ConnectionConfig(
                id = "id9",
                name = "Prod",
                kind = DatabaseKind.MARIADB,
                host = "db.example.com",
                port = 3307,
                database = "app",
                user = "me",
                readOnly = true,
                commitMode = CommitMode.MANUAL,
                sslMode = SslMode.VERIFY_FULL,
                caFile = "/etc/ca.pem",
                customUrl = "jdbc:mariadb://x/y",
                driverProperties = mapOf("a" to "1", "b" to "2"),
                color = CardColor.RED,
                showSystemSchemas = true,
            )
        assertEquals(config, form(config, isNew = false).toConfig())
    }

    @Test
    fun `an empty CA file becomes null and the custom URL is off with null`() {
        val config = form().copy(caFile = " ").toConfig()
        assertNull(config.caFile)
        assertNull(config.customUrl)
    }

    @Test
    fun `the generated URL ignores the custom URL`() {
        val form = form().copy(host = "h", portText = "1", database = "d", customUrl = "jdbc:postgresql://other/x")
        assertEquals("jdbc:postgresql://h:1/d", form.generatedUrl())
        assertEquals("jdbc:postgresql://other/x", form.toConfig().customUrl)
    }
}
