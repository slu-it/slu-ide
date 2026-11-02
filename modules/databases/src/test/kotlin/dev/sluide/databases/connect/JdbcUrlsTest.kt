package dev.sluide.databases.connect

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class JdbcUrlsTest {
    private val pg = ConnectionConfig("1", "pg", DatabaseKind.POSTGRESQL, database = "shop", user = "app")
    private val maria = ConnectionConfig("2", "maria", DatabaseKind.MARIADB, database = "shop", user = "app")

    @Test
    fun `urls of both kinds with and without a database`() {
        assertEquals("jdbc:postgresql://localhost:5432/shop", JdbcUrls.url(pg))
        assertEquals("jdbc:mariadb://localhost:3306", JdbcUrls.url(maria))
        assertEquals("jdbc:postgresql://localhost:5432", JdbcUrls.url(pg.copy(database = "")))
        assertEquals("jdbc:mariadb://h:3307", JdbcUrls.url(maria.copy(database = "", host = "h", port = 3307)))
    }

    @Test
    fun `an IPv6 host is put in brackets and a custom url wins`() {
        assertEquals("jdbc:postgresql://[::1]:5432/shop", JdbcUrls.url(pg.copy(host = "::1")))
        assertEquals("jdbc:postgresql://[::1]:5432/shop", JdbcUrls.url(pg.copy(host = "[::1]")))
        assertEquals("jdbc:foo://x", JdbcUrls.url(pg.copy(customUrl = "jdbc:foo://x")))
        assertEquals("jdbc:postgresql://localhost:5432/shop", JdbcUrls.url(pg.copy(customUrl = " ")))
    }

    @ParameterizedTest
    @CsvSource("DISABLE,disable", "PREFER,prefer", "REQUIRE,require", "VERIFY_CA,verify-ca", "VERIFY_FULL,verify-full")
    fun `PostgreSQL ssl modes`(
        mode: SslMode,
        expected: String,
    ) {
        assertEquals(expected, JdbcUrls.properties(pg.copy(sslMode = mode), null).getProperty("sslmode"))
    }

    @ParameterizedTest
    @CsvSource("DISABLE,disable", "PREFER,disable", "REQUIRE,trust", "VERIFY_CA,verify-ca", "VERIFY_FULL,verify-full")
    fun `MariaDB ssl modes`(
        mode: SslMode,
        expected: String,
    ) {
        assertEquals(expected, JdbcUrls.properties(maria.copy(sslMode = mode), null).getProperty("sslMode"))
    }

    @Test
    fun `PostgreSQL properties`() {
        val props = JdbcUrls.properties(pg.copy(readOnly = true, caFile = "/ca.pem"), "pw".toCharArray())

        assertEquals("app", props.getProperty("user"))
        assertEquals("pw", props.getProperty("password"))
        assertEquals("/ca.pem", props.getProperty("sslrootcert"))
        assertEquals("true", props.getProperty("readOnly"))
        assertEquals("always", props.getProperty("readOnlyMode"))
        assertEquals("Thoreau", props.getProperty("ApplicationName"))
    }

    @Test
    fun `MariaDB properties`() {
        val props = JdbcUrls.properties(maria.copy(caFile = "/ca.pem"), null)

        assertEquals("/ca.pem", props.getProperty("serverSslCert"))
        assertEquals("program_name:Thoreau", props.getProperty("connectionAttributes"))
        assertEquals("false", props.getProperty("useAliasAsName"))
        assertEquals("false", props.getProperty("yearIsDateType"))
        assertEquals(
            "false",
            JdbcUrls
                .properties(maria.copy(driverProperties = mapOf("useAliasAsName" to "true")), null)
                .getProperty("useAliasAsName"),
        )
        assertNull(JdbcUrls.properties(pg, null).getProperty("useAliasAsName"))
        assertNull(props.getProperty("password"))
        assertNull(JdbcUrls.properties(maria.copy(readOnly = true), null).getProperty("readOnly"))
    }

    @Test
    fun `a MariaDB ssl key set by the user in other case wins`() {
        val props = JdbcUrls.properties(maria.copy(driverProperties = mapOf("SSLMODE" to "trust")), null)

        assertEquals("trust", props.getProperty("SSLMODE"))
        assertNull(props.getProperty("sslMode"))
    }

    @Test
    fun `the database name is percent-encoded`() {
        assertEquals("jdbc:postgresql://localhost:5432/a%2Bb%2541", JdbcUrls.url(pg.copy(database = "a+b%41")))
        assertEquals("jdbc:postgresql://localhost:5432/my%20db", JdbcUrls.url(pg.copy(database = "my db")))
    }

    @Test
    fun `MariaDB gets the database as a property, not in the url`() {
        val config = maria.copy(database = "my db?x")

        assertEquals("jdbc:mariadb://localhost:3306", JdbcUrls.url(config))
        assertEquals("my db?x", JdbcUrls.properties(config, null).getProperty("database"))
        assertEquals("app@localhost:3306/my db?x", JdbcUrls.display(config))
        assertNull(JdbcUrls.properties(maria.copy(database = ""), null).getProperty("database"))
        assertNull(JdbcUrls.properties(config.copy(customUrl = "jdbc:mariadb://h/d"), null).getProperty("database"))
    }

    @Test
    fun `a blank user, no password and not read-only add nothing`() {
        val props = JdbcUrls.properties(pg.copy(user = ""), null)

        assertNull(props.getProperty("user"))
        assertNull(props.getProperty("password"))
        assertNull(props.getProperty("readOnly"))
    }

    @Test
    fun `generated values win over driver properties except explicit ssl keys`() {
        val config =
            pg.copy(
                sslMode = SslMode.REQUIRE,
                driverProperties =
                    mapOf("user" to "other", "ApplicationName" to "x", "sslmode" to "allow", "tcpKeepAlive" to "true"),
            )

        val props = JdbcUrls.properties(config, null)

        assertEquals("app", props.getProperty("user"))
        assertEquals("Thoreau", props.getProperty("ApplicationName"))
        assertEquals("allow", props.getProperty("sslmode"))
        assertEquals("true", props.getProperty("tcpKeepAlive"))
    }

    @Test
    fun `display shows user host port and database`() {
        assertEquals("app@localhost:5432/shop", JdbcUrls.display(pg))
        assertEquals("localhost:3306", JdbcUrls.display(maria.copy(user = "", database = "")))
    }

    @Test
    fun `display of a custom url hides credentials and the query`() {
        val shown =
            JdbcUrls.display(pg.copy(customUrl = "jdbc:postgresql://user:secret@host:5432/db?password=x&ssl=true"))

        assertEquals("jdbc:postgresql://host:5432/db", shown)
        assertFalse("secret" in shown || "password" in shown)
        assertEquals(
            "jdbc:postgresql://host/db",
            JdbcUrls.display(pg.copy(customUrl = "jdbc:postgresql://u:pa/ss@host/db")),
        )
        assertEquals("jdbc:mariadb://h/db", JdbcUrls.display(maria.copy(customUrl = "jdbc:mariadb://u:pa?ss@h/db")))
        assertEquals("jdbc:mariadb://h/db", JdbcUrls.display(maria.copy(customUrl = "jdbc:mariadb://a@h/db?user=u")))
    }
}
