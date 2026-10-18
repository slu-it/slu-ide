package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.envedit.FileSide.PRIVATE
import dev.sluide.httpclient.envedit.FileSide.PUBLIC
import tools.jackson.databind.JsonNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvDocumentTest {
    private fun text(value: String): JsonNode = EnvJson.nodes.stringNode(value)

    private fun path(vararg keys: String) = ValuePath.of(*keys)

    /** [text] without its indentation, with a trailing newline, as the files are written. */
    private fun json(text: String) = text.trimIndent() + "\n"

    @Test
    fun `writes a value where it lives and leaves the other file alone`() {
        val document = EnvDocument("""{"dev": {"host": "a", "port": 80}}""", """{"dev": {"port": 8080}}""")

        assertEquals(
            edit("""{ "dev": { "host": "b", "port": 80 } }""", null),
            document.setValue("dev", path("host"), text("b")),
        )
        assertEquals(
            edit(null, """{ "dev": { "port": 9 } }"""),
            document.setValue("dev", path("port"), EnvJson.nodes.numberNode(9)),
        )
    }

    @Test
    fun `writes a new value to the given file, else to the row's default one, creating what is missing`() {
        val document = EnvDocument("""{"dev": {}}""", null)

        assertEquals(
            edit("""{ "dev": { "db": { "user": "u" } } }""", null),
            document.setValue("dev", path("db", "user"), text("u")),
        )
        assertEquals(
            edit(null, """{ "dev": { "token": "t" } }"""),
            document.setValue("dev", path("token"), text("t"), PRIVATE),
        )
        assertEquals(
            edit(null, """{ "dev": { "Security": { "Auth": { "api": { "Client Secret": "s" } } } } }"""),
            document.setValue("dev", path("Security", "Auth", "api", "Client Secret"), text("s")),
        )
    }

    @Test
    fun `writes inside a list in the list's file`() {
        val document = EnvDocument("""{"dev": {"hosts": ["a", "b"]}}""", """{"dev": {"hosts": ["x"]}}""")

        assertEquals(
            edit(null, """{ "dev": { "hosts": [ "y" ] } }"""),
            document.setValue("dev", path("hosts").index(0), text("y")),
        )
        assertEquals(
            edit(null, """{ "dev": { "hosts": [ "x", "z" ] } }"""),
            document.setValue("dev", path("hosts").index(1), text("z")),
        )
    }

    @Test
    fun `refuses to replace objects or to write through values that are not objects`() {
        val document = EnvDocument("""{"dev": {"db": {"user": "u"}, "url": "x"}}""", null)

        assertFailsWith<IllegalArgumentException> { document.setValue("dev", path("db"), text("x")) }
        assertFailsWith<IllegalStateException> { document.setValue("dev", path("url", "host"), text("h")) }
        assertFailsWith<IllegalArgumentException> { document.setValue("nope", path("a"), text("h")) }
    }

    @Test
    fun `removes a value from both files and prunes objects the other file still has`() {
        val document =
            EnvDocument(
                """{"dev": {"db": {"user": "u"}, "port": 80}}""",
                """{"dev": {"db": {"password": "p"}, "port": 8080, "x": 1}}""",
            )

        assertEquals(
            edit(null, """{ "dev": { "port": 8080, "x": 1 } }"""),
            document.remove("dev", path("db", "password")),
        )
        assertEquals(
            edit("""{ "dev": { "port": 80 } }""", null),
            document.remove("dev", path("db", "user")),
        )
        assertEquals(
            edit(
                """{ "dev": { "db": { "user": "u" } } }""",
                """{ "dev": { "db": { "password": "p" }, "x": 1 } }""",
            ),
            document.remove("dev", path("port")),
        )
        assertEquals(EnvEdit(null, null), document.remove("dev", path("missing")))
    }

    @Test
    fun `keeps objects that would disappear from the merged view`() {
        assertEquals(
            edit("""{ "dev": { "db": {} } }""", null),
            EnvDocument("""{"dev": {"db": {"user": "u"}}}""", null).remove("dev", path("db", "user")),
        )
        assertEquals(
            edit(null, "{}"),
            EnvDocument("""{"dev": {"a": 1}}""", """{"dev": {"b": 2}}""").remove("dev", path("b")),
        )
        assertEquals(
            edit(null, """{ "local": {} }"""),
            EnvDocument(null, """{"local": {"b": 2}}""").remove("local", path("b")),
        )
        assertEquals(
            edit("""{ "dev": {} }""", null),
            EnvDocument("""{"dev": {"a": 1}}""", """{"dev": {"b": 2}}""").remove("dev", path("a")),
        )
    }

    @Test
    fun `removes a list entry and a whole row`() {
        val document =
            EnvDocument(
                """{"dev": {"hosts": ["a", "b"], "x": 1}, "prod": {"hosts": ["c"], "x": 2}}""",
                """{"prod": {"x": 3}}""",
            )

        assertEquals(
            edit("""{ "dev": { "hosts": [ "b" ], "x": 1 }, "prod": { "hosts": [ "c" ], "x": 2 } }""", null),
            document.remove("dev", path("hosts").index(0)),
        )
        assertEquals(
            edit("""{ "dev": { "hosts": [ "a", "b" ] }, "prod": { "hosts": [ "c" ] } }""", "{}"),
            document.removeRow(path("x")),
        )
    }

    @Test
    fun `moves a value between the files`() {
        val document = EnvDocument("""{"dev": {"db": {"user": "u", "pw": "p"}, "port": 80}}""", null)

        assertEquals(
            edit(
                """{ "dev": { "db": { "user": "u" }, "port": 80 } }""",
                """{ "dev": { "db": { "pw": "p" } } }""",
            ),
            document.setSide("dev", path("db", "pw"), PRIVATE),
        )
        assertEquals(EnvEdit(null, null), document.setSide("dev", path("port"), PUBLIC))

        val overridden = EnvDocument("""{"dev": {"port": 80, "a": 1}}""", """{"dev": {"port": 8080}, "prod": {}}""")
        assertEquals(
            edit("""{ "dev": { "port": 8080, "a": 1 } }""", """{ "prod": {} }"""),
            overridden.setSide("dev", path("port"), PUBLIC),
        )
    }

    @Test
    fun `moves a whole list and refuses to move merged objects and certificate settings`() {
        val document = EnvDocument("""{"dev": {"hosts": ["a"], "db": {"u": 1}}}""", """{"dev": {"x": 1}}""")

        assertEquals(
            edit(
                """{ "dev": { "db": { "u": 1 } } }""",
                """{ "dev": { "x": 1, "hosts": [ "a" ] } }""",
            ),
            document.setSide("dev", path("hosts").index(0), PRIVATE),
        )
        assertFailsWith<IllegalArgumentException> { document.setSide("dev", path("db"), PRIVATE) }
        assertFailsWith<IllegalArgumentException> { document.setSide("dev", path("SSLConfiguration", "a"), PUBLIC) }
    }

    @Test
    fun `converts the values of a row to another type`() {
        val document =
            EnvDocument(
                """{"dev": {"v": "30", "f": "TRUE", "n": 1e3}, "prod": {"v": "abc", "f": "maybe", "n": true}}""",
                """{"local": {"v": ""}}""",
            )

        assertTrue(document.isLossy(path("v"), ValueType.NUMBER))
        assertFalse(document.isLossy(path("v"), ValueType.TEXT))
        assertFalse(document.isLossy(path("n"), ValueType.TEXT))
        assertEquals(
            edit(
                """{ "dev": { "v": 30, "f": "TRUE", "n": 1e3 }, "prod": { "v": 0, "f": "maybe", "n": true } }""",
                """{ "local": { "v": 0 } }""",
            ),
            document.setType(path("v"), ValueType.NUMBER),
        )
        assertEquals(
            edit(
                """{ "dev": { "v": "30", "f": true, "n": 1e3 }, "prod": { "v": "abc", "f": false, "n": true } }""",
                null,
            ),
            document.setType(path("f"), ValueType.YES_NO),
        )
        assertEquals(
            edit(
                """{ "dev": { "v": "30", "f": "TRUE", "n": "1e3" }, """ +
                    """"prod": { "v": "abc", "f": "maybe", "n": "true" } }""",
                null,
            ),
            document.setType(path("n"), ValueType.TEXT),
        )
        assertFailsWith<IllegalArgumentException> { document.setType(path("v"), ValueType.MIXED) }
    }

    @Test
    fun `converts objects split across the files into one value of the default file`() {
        val document = EnvDocument("""{"dev": {"db": {"u": 1}, "x": 2}}""", """{"dev": {"db": {"p": 2}, "y": 3}}""")

        assertTrue(document.isLossy(path("db"), ValueType.LIST))
        assertFalse(EnvDocument("""{"dev": {"db": {}}}""", null).isLossy(path("db"), ValueType.LIST))
        assertEquals(
            edit("""{ "dev": { "db": [], "x": 2 } }""", """{ "dev": { "y": 3 } }"""),
            document.setType(path("db"), ValueType.LIST),
        )
    }

    @Test
    fun `adds a variable to every environment that has its parent`() {
        val document = EnvDocument("""{"dev": {"db": {}}, "prod": {}}""", """{"local": {}, "dev": {"db": {"p": 1}}}""")

        assertEquals(
            edit(
                """{ "dev": { "db": {}, "host": "" }, "prod": { "host": "" } }""",
                """{ "local": { "host": "" }, "dev": { "db": { "p": 1 } } }""",
            ),
            document.addVariable(ValuePath.ROOT, "host", ValueType.TEXT),
        )
        assertEquals(
            edit("""{ "dev": { "db": { "port": 0 } }, "prod": {} }""", null),
            document.addVariable(path("db"), "port", ValueType.NUMBER),
        )
        assertFailsWith<IllegalArgumentException> { document.addVariable(path("db"), "p", ValueType.TEXT) }
        assertFailsWith<IllegalArgumentException> { document.addVariable(ValuePath.ROOT, "Security", ValueType.OBJECT) }
    }

    @Test
    fun `appends an entry to a list in every environment that has it`() {
        val document = EnvDocument("""{"dev": {"l": [1]}, "prod": {}}""", """{"local": {"l": []}}""")

        assertEquals(
            edit("""{ "dev": { "l": [ 1, {} ] }, "prod": {} }""", """{ "local": { "l": [ {} ] } }"""),
            document.appendListEntry(path("l"), ValueType.OBJECT),
        )
    }

    @Test
    fun `adds, renames and removes environments`() {
        val document = EnvDocument("""{"dev": {"a": 1}, "prod": {}}""", """{"local": {}, "dev": {"b": 2}}""")

        assertEquals(
            edit("""{ "dev": { "a": 1 }, "prod": {}, "test": {} }""", null),
            document.addEnvironment("test"),
        )
        assertEquals(
            edit(
                """{ "development": { "a": 1 }, "prod": {} }""",
                """{ "local": {}, "development": { "b": 2 } }""",
            ),
            document.renameEnvironment("dev", "development"),
        )
        assertEquals(
            edit("""{ "prod": {} }""", """{ "local": {} }"""),
            document.removeEnvironment("dev"),
        )
        assertFailsWith<IllegalArgumentException> { document.addEnvironment("local") }
        assertFailsWith<IllegalArgumentException> { document.addEnvironment("\$shared") }
        assertFailsWith<IllegalArgumentException> { document.renameEnvironment("prod", "dev") }
        assertFailsWith<IllegalArgumentException> { document.removeEnvironment("nope") }
    }

    @Test
    fun `adds and removes an auth configuration`() {
        val document = EnvDocument("""{"dev": {"a": 1}, "prod": {}}""", null)

        val added = document.addAuth("api", "password", listOf("dev"))

        assertEquals(
            json(
                """
                {
                  "dev": {
                    "a": 1,
                    "Security": {
                      "Auth": {
                        "api": {
                          "Type": "OAuth2",
                          "Grant Type": "Password",
                          "Token URL": "",
                          "Client ID": "",
                          "Username": "",
                          "Scope": ""
                        }
                      }
                    }
                  },
                  "prod": {}
                }
                """,
            ),
            added.public,
        )
        assertEquals(
            file("""{ "dev": { "Security": { "Auth": { "api": { "Client Secret": "", "Password": "" } } } } }"""),
            added.private,
        )
        val withAuth = EnvDocument(added.public, added.private)
        assertFailsWith<IllegalArgumentException> { withAuth.addAuth("api", "Password", listOf("prod", "dev")) }
        assertFailsWith<IllegalArgumentException> { document.addAuth("x", "Implicit", listOf("prod")) }
        assertEquals(
            edit("""{ "dev": { "a": 1 }, "prod": {} }""", "{}"),
            withAuth.removeAuth("api"),
        )
        val clientCredentials = document.addAuth("api", "Client Credentials", listOf("prod"))
        assertEquals(
            file("""{ "prod": { "Security": { "Auth": { "api": { "Client Secret": "" } } } } }"""),
            clientCredentials.private,
        )
    }

    @Test
    fun `adds an existing auth configuration to another environment`() {
        val document =
            EnvDocument(
                """{"dev": {"Security": {"Auth": {"api": {"Type": "OAuth2"}}}}, "prod": {}}""",
                """{"dev": {"Security": {"Auth": {"api": {"Client Secret": "s"}}}}}""",
            )

        val edit = document.addAuth("api", "Client Credentials", listOf("prod"))

        assertEquals(
            file(
                """{ "dev": { "Security": { "Auth": { "api": { "Type": "OAuth2" } } } }, "prod": { "Security": { """ +
                    """"Auth": { "api": { "Type": "OAuth2", "Grant Type": "Client Credentials", "Token URL": "", """ +
                    """"Client ID": "", "Scope": "" } } } } }""",
            ),
            edit.public,
        )
        assertEquals(
            file(
                """{ "dev": { "Security": { "Auth": { "api": { "Client Secret": "s" } } } }, """ +
                    """"prod": { "Security": { "Auth": { "api": { "Client Secret": "" } } } } }""",
            ),
            edit.private,
        )
    }

    @Test
    fun `removes Security and SSLConfiguration once they are empty, but not an auth configuration`() {
        val document =
            EnvDocument(
                """{"dev": {"Security": {"Auth": {"api": {"Scope": ""}}}}}""",
                """{"dev": {"x": 1, "SSLConfiguration": {"verifyHostCertificate": true}}}""",
            )

        assertEquals(
            edit("""{ "dev": { "Security": { "Auth": { "api": {} } } } }""", null),
            document.remove("dev", path("Security", "Auth", "api", "Scope")),
        )
        assertEquals(
            edit(null, """{ "dev": { "x": 1 } }"""),
            document.removeRow(path("SSLConfiguration", "verifyHostCertificate")),
        )
        assertEquals(edit(null, """{ "dev": { "x": 1 } }"""), document.removeCertificate("dev"))
    }

    @Test
    fun `adds new values of an environment only the private file has to the private file`() {
        val document = EnvDocument("""{"dev": {}}""", """{"local": {"db": {}}}""")

        assertEquals(
            edit(null, """{ "local": { "db": {}, "host": "h" } }"""),
            document.setValue("local", path("host"), text("h")),
        )
        assertEquals(
            edit(null, """{ "local": { "db": { "user": "u" } } }"""),
            document.setValue("local", path("db", "user"), text("u")),
        )
        assertEquals(
            edit(null, """{ "local": { "db": {}, "cache": { "ttl": { "s": 1 } } } }"""),
            document.setValue("local", path("cache", "ttl", "s"), EnvJson.nodes.numberNode(1)),
        )
        assertEquals(
            edit("""{ "dev": {}, "local": { "Security": { "Auth": { "api": { "Token URL": "" } } } } }""", null),
            document.setValue("local", path("Security", "Auth", "api", "Token URL"), text("")),
        )
    }

    @Test
    fun `adds keys to auth configurations and certificate settings in the file the schema says`() {
        val document =
            EnvDocument(
                """{"dev": {"Security": {"Auth": {"api": {"Type": "OAuth2"}}}}}""",
                """{"dev": {"SSLConfiguration": {"clientCertificate": "c.pem"}}}""",
            )
        val api = path("Security", "Auth", "api")

        assertFailsWith<IllegalArgumentException> { document.addVariable(api, "Client Secret", ValueType.TEXT) }
        assertFailsWith<IllegalArgumentException> {
            document.addVariable(path("Security", "Auth"), "other", ValueType.OBJECT)
        }
        assertEquals(
            edit("""{ "dev": { "Security": { "Auth": { "api": { "Type": "OAuth2", "Audience": "" } } } } }""", null),
            document.addVariable(api, "Audience", ValueType.TEXT),
        )
        assertEquals(
            edit(null, """{ "dev": { "SSLConfiguration": { "clientCertificate": "c.pem", "x": false } } }"""),
            document.addVariable(path("SSLConfiguration"), "x", ValueType.YES_NO),
        )
    }

    @Test
    fun `converts an object one file holds in that file and refuses to convert certificate settings`() {
        val document = EnvDocument("""{"dev": {"a": 1}}""", """{"dev": {"db": {"p": 2}}}""")

        assertEquals(edit(null, """{ "dev": { "db": "" } }"""), document.setType(path("db"), ValueType.TEXT))
        val certificate = path("SSLConfiguration", "clientCertificate")
        assertFailsWith<IllegalArgumentException> { document.setType(certificate, ValueType.NUMBER) }
        assertFailsWith<IllegalArgumentException> { document.isLossy(certificate, ValueType.NUMBER) }
    }

    @Test
    fun `moves a private list that overrides a public one to the public file`() {
        val document = EnvDocument("""{"dev": {"hosts": ["a", "b"]}}""", """{"dev": {"hosts": ["x"]}}""")

        assertEquals(
            edit("""{ "dev": { "hosts": [ "x" ] } }""", "{}"),
            document.setSide("dev", path("hosts").index(0), PUBLIC),
        )
    }

    @Test
    fun `renames and removes an environment that is not an object`() {
        val document = EnvDocument("""{"dev": {}, "prod": 5}""", null)

        assertEquals(edit("""{ "dev": {}, "production": 5 }""", null), document.renameEnvironment("prod", "production"))
        assertEquals(edit("""{ "dev": {} }""", null), document.removeEnvironment("prod"))
    }

    @Test
    fun `adds a client certificate and switches its format`() {
        val document = EnvDocument("""{"dev": {}}""", null)

        val added = document.addCertificate(listOf("dev"))

        assertEquals(null, added.public)
        assertEquals(
            file("""{ "dev": { "SSLConfiguration": { "clientCertificate": "", "verifyHostCertificate": true } } }"""),
            added.private,
        )
        val withCertificate =
            EnvDocument("""{"dev": {}}""", """{"dev": {"SSLConfiguration": {"clientCertificate": "c.pem"}}}""")
        val certificate = path("SSLConfiguration", "clientCertificate")
        val withFormat = withCertificate.setCertificateFormat("dev", CertificatePart.CERTIFICATE, "P12")
        assertEquals(
            edit(
                null,
                """{ "dev": { "SSLConfiguration": { "clientCertificate": { "path": "c.pem", "format": "P12" } } } }""",
            ),
            withFormat,
        )
        assertEquals(withFormat, withCertificate.setValue("dev", certificate.key("format"), text("P12")))
        val objectForm = EnvDocument("""{"dev": {}}""", withFormat.private)
        assertEquals(
            edit(
                null,
                """{ "dev": { "SSLConfiguration": { "clientCertificate": { "path": "d.pem", "format": "P12" } } } }""",
            ),
            objectForm.setValue("dev", certificate, text("d.pem"), PUBLIC),
        )
        assertEquals(
            edit(null, """{ "dev": { "SSLConfiguration": { "clientCertificate": "c.pem" } } }"""),
            objectForm.setValue("dev", certificate.key("format"), text("")),
        )
        assertEquals(
            edit(null, """{ "dev": { "SSLConfiguration": { "clientCertificate": "d.pem" } } }"""),
            withCertificate.setValue("dev", certificate, text("d.pem")),
        )
        assertEquals(
            edit(
                null,
                """{ "dev": { "SSLConfiguration": { "clientCertificate": "c.pem", """ +
                    """"clientCertificateKey": { "path": "", "format": "PEM" } } } }""",
            ),
            withCertificate.setCertificateFormat("dev", CertificatePart.KEY, "PEM"),
        )
        assertEquals(
            edit(null, """{ "dev": { "SSLConfiguration": { "clientCertificate": "c.pem" } } }"""),
            objectForm.remove("dev", certificate.key("format")),
        )
    }

    @Test
    fun `keeps the spelling of numbers, escapes strings and leaves what it does not show untouched`() {
        val public =
            """
            {"${'$'}shared": {"x": [1, 2]}, "dev": {"a": 1.50, "b": 1e3, "c": -0.0, "d": 12345678901234567890,
            "e": 2E-3, "s": "é\"\n\t\\/", "Security": {"Other": 1}}, "prod": 5}
            """.trimIndent()

        val edit = EnvDocument(public, null).setValue("dev", path("z"), EnvJson.number("1.0e+2")!!)

        assertEquals(
            json(
                """
                {
                  "${'$'}shared": {
                    "x": [
                      1,
                      2
                    ]
                  },
                  "dev": {
                    "a": 1.50,
                    "b": 1e3,
                    "c": -0.0,
                    "d": 12345678901234567890,
                    "e": 2E-3,
                    "s": "é\"\n\t\\/",
                    "Security": {
                      "Other": 1
                    },
                    "z": 1.0e+2
                  },
                  "prod": 5
                }
                """,
            ),
            edit.public,
        )
    }

    @Test
    fun `reports no change when the files stay the same and refuses edits of broken or read-only files`() {
        val document = EnvDocument("""{"dev": {"a": "x"}, "prod": [1]}""", null)

        assertEquals(EnvEdit(null, null), document.setValue("dev", path("a"), text("x")))
        assertTrue(document.setValue("dev", path("a"), text("x")).isEmpty)
        assertFailsWith<IllegalArgumentException> { document.setValue("prod", path("a"), text("x")) }
        val broken = EnvDocument("""{"dev": {}}""", "{")
        assertFailsWith<IllegalStateException> { broken.setValue("dev", path("a"), text("x")) }
        assertFailsWith<IllegalStateException> { broken.addEnvironment("test") }
        assertFailsWith<IllegalStateException> { broken.isLossy(path("a"), ValueType.TEXT) }
    }

    /** [oneLine] JSON as a file is written: pretty-printed, so that expectations fit on one line. */
    private fun file(oneLine: String) = EnvJson.print(EnvJson.parse(oneLine)!!)

    /** The edit of the files [public] and [private], given as one-line JSON. */
    private fun edit(
        public: String?,
        private: String?,
    ) = EnvEdit(public?.let(::file), private?.let(::file))
}
