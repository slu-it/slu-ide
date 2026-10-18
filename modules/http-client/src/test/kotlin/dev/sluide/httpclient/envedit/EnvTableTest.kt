package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.env.EnvironmentFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvTableTest {
    private fun table(
        public: String? = null,
        private: String? = null,
    ) = EnvDocument(public, private).table

    private fun List<Row>.row(label: String) = single { it.label == label }

    /** The cells of [row] as `value side` texts, `-` for undefined, `!` marking an override. */
    private fun cells(row: Row) =
        row.cells.mapValues { (_, cell) ->
            cell.value?.let { "$it ${cell.side}${if (cell.overridesPublic) "!" else ""}" } ?: "-"
        }

    @Test
    fun `merges the environments of both files with the file of each value`() {
        val table =
            table(
                public = """{"dev": {"host": "a", "port": 80}, "prod": {"host": "b"}}""",
                private = """{"local": {"host": "c"}, "dev": {"port": 8080, "token": "t"}}""",
            )

        assertNull(table.error)
        assertEquals(listOf("dev", "prod", "local"), table.environments)
        assertEquals(listOf("host", "port", "token"), table.variables.map { it.label })
        val host = table.variables.row("host")
        assertEquals(ValueType.TEXT, host.type)
        assertEquals(mapOf("dev" to "\"a\" PUBLIC", "prod" to "\"b\" PUBLIC", "local" to "\"c\" PRIVATE"), cells(host))
        val port = table.variables.row("port")
        assertEquals(ValueType.NUMBER, port.type)
        assertEquals(mapOf("dev" to "8080 PRIVATE!", "prod" to "-", "local" to "-"), cells(port))
        assertEquals(ValuePath.of("port"), port.path)
        assertEquals(FileSide.PUBLIC, port.defaultSide)
        assertFalse(port.sideFixed || port.readOnly)
        assertEquals(setOf("dev", "prod", "local"), port.applicable)
        assertTrue(table.problems.isEmpty())
        assertNull(table.certificate)
    }

    @Test
    fun `merges objects split across the files into one row`() {
        val table =
            table(
                public = """{"dev": {"db": {"user": "u", "options": {"ssl": true}}}}""",
                private = """{"dev": {"db": {"password": "p", "options": {"timeout": 5}}}}""",
            )

        val db = table.variables.single()
        assertEquals(ValueType.OBJECT, db.type)
        assertEquals(
            """{"user":"u","options":{"ssl":true,"timeout":5},"password":"p"}""",
            db.cells["dev"]?.value.toString(),
        )
        assertNull(db.cells["dev"]?.side)
        assertEquals(listOf("user", "options", "password"), db.children.map { it.label })
        assertEquals("\"u\" PUBLIC", cells(db.children.row("user"))["dev"])
        assertEquals("\"p\" PRIVATE", cells(db.children.row("password"))["dev"])
        val options = db.children.row("options")
        assertEquals(listOf("true PUBLIC", "5 PRIVATE"), options.children.map { cells(it).getValue("dev") })
        assertEquals(ValuePath.of("db", "options", "timeout"), options.children[1].path)
    }

    @Test
    fun `shows lists as rows by index, taken whole from one file`() {
        val table =
            table(
                public = """{"dev": {"hosts": ["a", "b"]}, "prod": {"hosts": ["c", "d"]}}""",
                private = """{"dev": {"hosts": ["x"]}}""",
            )

        val hosts = table.variables.single()
        assertEquals(ValueType.LIST, hosts.type)
        assertEquals(mapOf("dev" to "[\"x\"] PRIVATE!", "prod" to "[\"c\",\"d\"] PUBLIC"), cells(hosts))
        assertEquals(listOf("[0]", "[1]"), hosts.children.map { it.label })
        assertEquals(ValuePath.of("hosts").index(1), hosts.children[1].path)
        assertEquals(mapOf("dev" to "\"x\" PRIVATE!", "prod" to "\"c\" PUBLIC"), cells(hosts.children[0]))
        assertEquals(mapOf("dev" to "-", "prod" to "\"d\" PUBLIC"), cells(hosts.children[1]))
    }

    @Test
    fun `shows values of different types as a read-only mixed row`() {
        val table =
            table(
                public = """{"dev": {"port": 80, "db": {"a": 1}}, "prod": {"port": "80", "db": "none"}}""",
            )

        for (row in table.variables) {
            assertEquals(ValueType.MIXED, row.type)
            assertTrue(row.readOnly)
            assertTrue(row.children.isEmpty())
        }
        assertEquals("80 PUBLIC", cells(table.variables.row("port"))["dev"])
    }

    @Test
    fun `tells all value types apart`() {
        val row = table(public = """{"a": {"t": "x", "n": 1.5, "y": false, "z": null, "o": {}, "l": []}}""").variables

        assertEquals(
            listOf(
                ValueType.TEXT,
                ValueType.NUMBER,
                ValueType.YES_NO,
                ValueType.NULL,
                ValueType.OBJECT,
                ValueType.LIST,
            ),
            row.map { it.type },
        )
    }

    @Test
    fun `shows each auth configuration with all known fields`() {
        val table =
            table(
                public =
                    """
                    {
                      "dev": {"Security": {"Auth": {"api": {"Type": "OAuth2", "Grant Type": "password", "Extra": 1}}}},
                      "prod": {"Security": {"Auth": {"api": {"Type": "OAuth2", "Grant Type": "Client Credentials"}}}},
                      "local": {"host": "h"}
                    }
                    """.trimIndent(),
                private = """{"dev": {"Security": {"Auth": {"api": {"Password": "p"}, "other": {}}}}}""",
            )

        assertEquals(listOf("host"), table.variables.map { it.label })
        assertEquals(listOf("api", "other"), table.auth.map { it.id })
        val rows = table.auth.first().rows
        assertEquals(
            listOf(
                "Type",
                "Grant Type",
                "Token URL",
                "Client ID",
                "Client Secret",
                "Client Credentials",
                "Username",
                "Password",
                "Scope",
                "Use ID Token",
                "Acquire Automatically",
                "Custom Request Parameters",
                "Extra",
            ),
            rows.map { it.label },
        )
        assertEquals(ValuePath.of("Security", "Auth", "api", "Token URL"), rows.row("Token URL").path)
        assertEquals(mapOf("dev" to "-", "prod" to "-", "local" to "-"), cells(rows.row("Token URL")))
        assertEquals("\"p\" PRIVATE", cells(rows.row("Password"))["dev"])
        assertEquals(listOf("Client Credentials", "Password"), rows.row("Grant Type").choices)
        assertEquals(listOf("basic", "in body", "none"), rows.row("Client Credentials").choices)
        assertNull(rows.row("Scope").choices)
        assertEquals(ValueType.YES_NO, rows.row("Use ID Token").type)
        assertEquals(ValueType.OBJECT, rows.row("Custom Request Parameters").type)
        assertEquals(ValueType.NUMBER, rows.row("Extra").type)
        assertEquals(setOf("dev", "prod"), rows.row("Token URL").applicable)
        assertEquals(setOf("dev"), rows.row("Username").applicable)
        assertEquals(setOf("dev"), rows.row("Password").applicable)
        val private = rows.filter { it.defaultSide == FileSide.PRIVATE }.map { it.label }
        assertEquals(listOf("Client Secret", "Password"), private)
        assertTrue(rows.none { it.sideFixed })
        assertEquals(
            setOf("dev"),
            table.auth[1]
                .rows
                .row("Type")
                .applicable,
        )
    }

    @Test
    fun `shows custom request parameters as an object row`() {
        val params = """{"audience": "a", "resource": {"Value": "r", "Use": "In Token Request"}}"""
        val rows =
            table(public = """{"dev": {"Security": {"Auth": {"api": {"Custom Request Parameters": $params}}}}}""")
                .auth
                .single()
                .rows
        val parameters = rows.row("Custom Request Parameters")

        assertEquals(listOf("audience", "resource"), parameters.children.map { it.label })
        assertEquals(ValueType.OBJECT, parameters.children[1].type)
        assertEquals(listOf("Value", "Use"), parameters.children[1].children.map { it.label })
    }

    @Test
    fun `shows the client certificate settings of the private file`() {
        val table =
            table(
                public = """{"dev": {}, "prod": {}}""",
                private =
                    """
                    {"dev": {"SSLConfiguration": {
                      "clientCertificate": {"path": "c.pem", "format": "PEM"},
                      "clientCertificateKey": "k.pem",
                      "verifyHostCertificate": false
                    }}}
                    """.trimIndent(),
            )

        val rows = assertNotNull(table.certificate)
        assertEquals(
            listOf(
                "Certificate",
                "Certificate format",
                "Key",
                "Key format",
                "Has passphrase",
                "Verify host certificate",
            ),
            rows.map { it.label },
        )
        assertEquals(mapOf("dev" to "\"c.pem\" PRIVATE", "prod" to "-"), cells(rows.row("Certificate")))
        assertEquals("\"PEM\" PRIVATE", cells(rows.row("Certificate format"))["dev"])
        assertEquals("\"k.pem\" PRIVATE", cells(rows.row("Key"))["dev"])
        assertEquals("-", cells(rows.row("Key format"))["dev"])
        assertEquals("-", cells(rows.row("Has passphrase"))["dev"])
        assertEquals("false PRIVATE", cells(rows.row("Verify host certificate"))["dev"])
        assertEquals(listOf("", "PEM", "P12", "DER"), rows.row("Key format").choices)
        assertEquals(ValuePath.of("SSLConfiguration", "clientCertificate"), rows.row("Certificate").path)
        assertEquals(ValuePath.of("SSLConfiguration", "clientCertificateKey", "format"), rows.row("Key format").path)
        assertEquals(ValueType.YES_NO, rows.row("Has passphrase").type)
        for (row in rows) {
            assertTrue(row.sideFixed)
            assertFalse(row.readOnly)
            assertEquals(FileSide.PRIVATE, row.defaultSide)
            assertEquals(setOf("dev"), row.applicable)
        }
        assertTrue(table.variables.isEmpty())
        assertTrue(table.problems.isEmpty())
    }

    @Test
    fun `reports a public client certificate and keeps the rows editable for the private file`() {
        val table =
            table(
                public = """{"dev": {"SSLConfiguration": {"clientCertificate": "c.pem"}}}""",
                private = """{"dev": {"SSLConfiguration": {"clientCertificate": "p.pem"}}}""",
            )

        val rows = assertNotNull(table.certificate)
        assertTrue(rows.none { it.readOnly })
        assertEquals("\"p.pem\" PRIVATE", cells(rows.row("Certificate"))["dev"])
        assertEquals(
            listOf(
                "${EnvironmentFiles.PUBLIC}: SSLConfiguration of environment \"dev\" is only read from " +
                    EnvironmentFiles.PRIVATE,
            ),
            table.problems,
        )
    }

    @Test
    fun `reports what it does not show`() {
        val table =
            table(
                public = """{"${'$'}shared": {"a": 1}, "dev": {"Security": "x"}, "prod": 5}""",
                private = """{"dev": {"Security": {"Auth": {"api": []}}}, "local": {"SSLConfiguration": 1}}""",
            )

        assertEquals(listOf("dev", "prod", "local"), table.environments)
        assertEquals(setOf("prod"), table.readOnlyEnvironments)
        assertEquals(
            listOf(
                "\"${'$'}shared\" is not shown here and kept as it is",
                "${EnvironmentFiles.PUBLIC}: Security of environment \"dev\" must be an object",
                "${EnvironmentFiles.PRIVATE}: Auth \"api\" of environment \"dev\" must be an object",
                "${EnvironmentFiles.PUBLIC}: environment \"prod\" must be an object; it is shown read-only",
                "${EnvironmentFiles.PRIVATE}: SSLConfiguration of environment \"local\" must be an object",
            ),
            table.problems,
        )
        assertTrue(table.auth.isEmpty())
        assertEquals(
            Cell.UNDEFINED,
            table.certificate
                ?.first()
                ?.cells
                ?.get("prod"),
        )
    }

    @Test
    fun `fails on files that are not valid JSON or not an object`() {
        val invalid = table(public = """{"dev": {}}""", private = """{"dev": {"a": }""")

        assertTrue(invalid.error!!.startsWith("${EnvironmentFiles.PRIVATE}: Unexpected character"), invalid.error)
        assertTrue(invalid.environments.isEmpty() && invalid.variables.isEmpty())
        assertEquals("${EnvironmentFiles.PUBLIC}: expected an object of environments", table(public = "[1]").error)
        assertTrue(table(public = """{"dev": {"a": 1, "a": 2}}""").error!!.contains("Duplicate"))
        assertNotNull(table(public = """{"dev": {}} {}""").error)
        assertEquals(
            "${EnvironmentFiles.PUBLIC}: Number out of range: 1e9999999999",
            table(public = """{"dev": {"a": 1e9999999999}}""").error,
        )
    }

    @Test
    fun `reports keys it does not show inside Security and SSLConfiguration`() {
        val table =
            table(
                public = """{"dev": {"Security": {"Auth": {}, "Other": 1}}}""",
                private =
                    """
                    {"dev": {"SSLConfiguration": {
                      "clientCertificate": {"path": "c.pem", "password": "x"},
                      "clientCertificateKey": "k.pem",
                      "extra": true
                    }}}
                    """.trimIndent(),
            )

        assertEquals(
            listOf(
                "${EnvironmentFiles.PUBLIC}: \"Security.Other\" of environment \"dev\" " +
                    "is not shown here and kept as it is",
                "${EnvironmentFiles.PRIVATE}: \"SSLConfiguration.clientCertificate.password\" of environment \"dev\" " +
                    "is not shown here and kept as it is",
                "${EnvironmentFiles.PRIVATE}: \"SSLConfiguration.extra\" of environment \"dev\" " +
                    "is not shown here and kept as it is",
            ),
            table.problems,
        )
    }

    @Test
    fun `reads missing and blank files as empty`() {
        val table = table(public = null, private = " \n")

        assertNull(table.error)
        assertTrue(table.environments.isEmpty())
        assertEquals(listOf("dev"), table(private = """{"dev": {}}""").environments)
    }
}
