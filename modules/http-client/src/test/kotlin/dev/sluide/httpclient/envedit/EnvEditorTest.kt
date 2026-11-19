package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.envedit.FileSide.PRIVATE
import dev.sluide.httpclient.envedit.FileSide.PUBLIC
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Which file an [EnvEditor] edit goes to when values live in both of them, and what it leaves alone; driven through
 * [EnvDocument], which hands it the files.
 */
internal class EnvEditorTest {
    private fun path(vararg keys: String) = ValuePath.of(*keys)

    private fun text(value: String): JsonNode = EnvJson.nodes.stringNode(value)

    private fun file(oneLine: String) = EnvJson.print(EnvJson.parse(oneLine)!!)

    private fun edit(
        public: String?,
        private: String?,
    ) = EnvEdit(public?.let(::file), private?.let(::file))

    private val cert = path("SSLConfiguration", "clientCertificate")

    @Test
    fun `a new variable goes to the file of the nearest object on its way, the private file only if it alone has it`() {
        val document =
            EnvDocument(
                """{"dev": {"a": 1, "both": {"x": 1}}, "pub": {}}""",
                """{"dev": {"db": {"u": 1}, "both": {"y": 2}}, "local": {"db": {}}}""",
            )

        assertEquals(
            edit(null, """{ "dev": { "db": { "u": 1, "p": 2 }, "both": { "y": 2 } }, "local": { "db": {} } }"""),
            document.setValue("dev", path("db", "p"), EnvJson.nodes.numberNode(2)),
        )
        assertEquals(
            edit("""{ "dev": { "a": 1, "both": { "x": 1, "p": 2 } }, "pub": {} }""", null),
            document.setValue("dev", path("both", "p"), EnvJson.nodes.numberNode(2)),
        )
        assertEquals(
            edit("""{ "dev": { "a": 1, "both": { "x": 1 }, "x": { "y": { "z": 2 } } }, "pub": {} }""", null),
            document.setValue("dev", path("x", "y", "z"), EnvJson.nodes.numberNode(2)),
        )
        assertEquals(
            edit(
                null,
                """{ "dev": { "db": { "u": 1 }, "both": { "y": 2 } }, "local": { "db": {}, "x": { "y": 2 } } }""",
            ),
            document.setValue("local", path("x", "y"), EnvJson.nodes.numberNode(2)),
        )
    }

    @Test
    fun `a variable added inside an entry of a list goes to the file the list comes from`() {
        val document = EnvDocument("""{"dev": {"l": [{"a": 1}]}}""", """{"dev": {"l": [{"b": 2}]}}""")

        assertEquals(
            edit(null, """{ "dev": { "l": [ { "b": 2, "k": "" } ] } }"""),
            document.addVariable(path("l").index(0), "k", ValueType.TEXT),
        )
    }

    @Test
    fun `certificate settings are written to the private file even if the public file has them`() {
        val document = EnvDocument("""{"dev": {"SSLConfiguration": {"verifyHostCertificate": true}}}""", null)
        val verify = path("SSLConfiguration", "verifyHostCertificate")

        assertEquals(
            edit(null, """{ "dev": { "SSLConfiguration": { "verifyHostCertificate": false } } }"""),
            document.setValue("dev", verify, EnvJson.nodes.booleanNode(false)),
        )
    }

    @Test
    fun `removing a certificate setting touches the private file only`() {
        val setting =
            """{"dev": {"SSLConfiguration": {"verifyHostCertificate": true, "hasCertificatePassphrase": true}}}"""
        val verify = path("SSLConfiguration", "verifyHostCertificate")

        assertEquals(
            edit(
                null,
                """{ "dev": { "SSLConfiguration": { "hasCertificatePassphrase": true } } }""",
            ),
            EnvDocument(setting, setting).remove("dev", verify),
        )
    }

    @Test
    fun `certificate settings stay in the private file, which a move there leaves as it is`() {
        val document = EnvDocument("""{"dev": {}}""", """{"dev": {"SSLConfiguration": {"clientCertificate": "c"}}}""")

        assertTrue(document.setSide("dev", cert, PRIVATE).isEmpty)
    }

    @Test
    fun `removes a list entry from the file the list comes from and a whole list from both`() {
        val document = EnvDocument("""{"dev": {"l": [1, 2], "k": [3]}}""", """{"dev": {"l": [4, 5]}}""")

        assertEquals(edit(null, """{ "dev": { "l": [ 5 ] } }"""), document.remove("dev", path("l").index(0)))
        assertEquals(edit("""{ "dev": { "k": [ 3 ] } }""", "{}"), document.remove("dev", path("l")))
        assertEquals(edit("""{ "dev": { "l": [ 1, 2 ] } }""", null), document.remove("dev", path("k")))
        assertEquals(EnvEdit(null, null), document.remove("dev", path("nope")))
        assertEquals(EnvEdit(null, null), document.remove("dev", path("nope", "deeper")))
    }

    @Test
    fun `removes a list below merged objects from the file it comes from only when it is an entry of it`() {
        val document =
            EnvDocument("""{"dev": {"db": {"roles": [1, 2], "a": 1}}}""", """{"dev": {"db": {"roles": [3, 4]}}}""")
        val roles = path("db", "roles")

        assertEquals(
            edit("""{ "dev": { "db": { "a": 1 } } }""", "{}"),
            document.remove("dev", roles),
        )
        assertEquals(
            edit(null, """{ "dev": { "db": { "roles": [ 4 ] } } }"""),
            document.remove("dev", roles.index(0)),
        )
    }

    @Test
    fun `converts a value to a type in the file it is in, also where its row belongs to the other file`() {
        val secret = path("Security", "Auth", "api", "Client Secret")
        val inPublic = EnvDocument("""{"dev": {"Security": {"Auth": {"api": {"Client Secret": 5}}}}}""", null)
        val inPrivate = EnvDocument("""{"dev": {}}""", """{"dev": {"Security": {"Auth": {"api": {"Type": 5}}}}}""")

        assertEquals(
            edit("""{ "dev": { "Security": { "Auth": { "api": { "Client Secret": "5" } } } } }""", null),
            inPublic.setType(secret, ValueType.TEXT),
        )
        assertEquals(
            edit(null, """{ "dev": { "Security": { "Auth": { "api": { "Type": "5" } } } } }"""),
            inPrivate.setType(path("Security", "Auth", "api", "Type"), ValueType.TEXT),
        )
    }

    @Test
    fun `moves a value to the other file only when it is not there already`() {
        val document = EnvDocument("""{"dev": {"a": 1, "l": [1]}}""", """{"dev": {"b": 2}}""")

        assertTrue(document.setSide("dev", path("a"), PUBLIC).isEmpty)
        assertTrue(document.setSide("dev", path("b"), PRIVATE).isEmpty)
        assertEquals(
            edit("""{ "dev": { "l": [ 1 ] } }""", """{ "dev": { "b": 2, "a": 1 } }"""),
            document.setSide("dev", path("a"), PRIVATE),
        )
        assertEquals(
            edit("""{ "dev": { "a": 1, "l": [1], "b": 2 } }""", "{}"),
            document.setSide("dev", path("b"), PUBLIC),
        )
    }

    @Test
    fun `converting to the type a value has leaves objects split across the files where they are`() {
        val document = EnvDocument("""{"dev": {"db": {"u": 1}, "s": "1"}}""", """{"dev": {"db": {"p": 2}, "s": "2"}}""")

        assertTrue(document.setType(path("db"), ValueType.OBJECT).isEmpty)
        assertTrue(document.setType(path("s"), ValueType.TEXT).isEmpty)
        assertEquals(
            edit(null, """{ "dev": { "db": { "p": 2 }, "s": 2 } }"""),
            document.setType(path("s"), ValueType.NUMBER),
        )
    }

    @Test
    fun `converts a value in the file that holds it`() {
        val document =
            EnvDocument(
                """{"dev": {"pub": "1", "db": {"u": "x"}, "both": "1"}}""",
                """{"dev": {"priv": "2", "pdb": {"u": "x"}, "both": "2"}}""",
            )

        assertEquals(
            edit("""{ "dev": { "pub": 1, "db": { "u": "x" }, "both": "1" } }""", null),
            document.setType(path("pub"), ValueType.NUMBER),
        )
        assertEquals(
            edit(null, """{ "dev": { "priv": 2, "pdb": { "u": "x" }, "both": "2" } }"""),
            document.setType(path("priv"), ValueType.NUMBER),
        )
        assertEquals(
            edit("""{ "dev": { "pub": "1", "db": [], "both": "1" } }""", null),
            document.setType(path("db"), ValueType.LIST),
        )
        assertEquals(
            edit(null, """{ "dev": { "priv": "2", "pdb": [], "both": "2" } }"""),
            document.setType(path("pdb"), ValueType.LIST),
        )
        assertEquals(
            edit(null, """{ "dev": { "priv": "2", "pdb": { "u": "x" }, "both": 2 } }"""),
            document.setType(path("both"), ValueType.NUMBER),
        )
    }

    @Test
    fun `adding to something that is no object, or appending to something that is no list, changes nothing`() {
        val document = EnvDocument("""{"dev": {"t": "x", "o": {}, "l": [1]}}""", null)

        assertTrue(document.addVariable(path("t"), "k", ValueType.TEXT).isEmpty)
        assertTrue(document.addVariable(path("nope"), "k", ValueType.TEXT).isEmpty)
        assertTrue(document.appendListEntry(path("t"), ValueType.TEXT).isEmpty)
        assertTrue(document.appendListEntry(path("o"), ValueType.TEXT).isEmpty)
        assertTrue(document.appendListEntry(path("nope"), ValueType.TEXT).isEmpty)
        assertEquals(
            edit("""{ "dev": { "t": "x", "o": {}, "l": [ 1, 0 ] } }""", null),
            document.appendListEntry(path("l"), ValueType.NUMBER),
        )
    }

    @Test
    fun `an environment that is an object in one file and no object in the other is no part of edits of all`() {
        val document = EnvDocument("""{"dev": {"a": 1, "l": [1]}, "prod": {"a": 1, "l": [1]}}""", """{"prod": [1]}""")

        assertEquals(
            edit("""{ "dev": { "a": 1, "l": [ 1 ], "b": "" }, "prod": { "a": 1, "l": [ 1 ] } }""", null),
            document.addVariable(ValuePath.ROOT, "b", ValueType.TEXT),
        )
        assertEquals(
            edit("""{ "dev": { "a": 1, "l": [ 1, "" ] }, "prod": { "a": 1, "l": [ 1 ] } }""", null),
            document.appendListEntry(path("l"), ValueType.TEXT),
        )
        assertEquals(
            edit("""{ "dev": { "a": "1", "l": [ 1 ] }, "prod": { "a": 1, "l": [ 1 ] } }""", null),
            document.setType(path("a"), ValueType.TEXT),
        )
        assertEquals(
            edit("""{ "dev": { "l": [ 1 ] }, "prod": { "a": 1, "l": [ 1 ] } }""", null),
            document.removeRow(path("a")),
        )
    }

    @Test
    fun `an auth configuration cannot be added where Security Auth is no object`() {
        val document =
            EnvDocument("""{"dev": {"Security": {"Auth": 5}}, "other": {"Security": {"Auth": {"x": {}}}}}""", null)

        val error = assertFailsWith<IllegalStateException> { document.addAuth("api", "password", listOf("dev")) }

        assertTrue(error.message.orEmpty().endsWith("is not an object"), error.message)
        assertFailsWith<IllegalArgumentException> { document.addAuth("x", "password", listOf("other")) }
    }

    @Test
    fun `adds no certificate where there is one`() {
        val document =
            EnvDocument(
                """{"dev": {}, "prod": {}}""",
                """{"dev": {"SSLConfiguration": {"verifyHostCertificate": false}}}""",
            )

        assertEquals(
            edit(
                null,
                """
                { "dev": { "SSLConfiguration": { "verifyHostCertificate": false } },
                  "prod": { "SSLConfiguration": { "clientCertificate": "", "verifyHostCertificate": true } } }
                """.trimIndent(),
            ),
            document.addCertificate(listOf("dev", "prod")),
        )
        assertTrue(document.addCertificate(listOf("dev")).isEmpty)
    }

    @Test
    fun `a format turns a path text into the object form and back, keeping what else the object holds`() {
        val withExtra =
            EnvDocument(
                """{"dev": {}}""",
                """{"dev": {"SSLConfiguration": {"clientCertificate": {"path": "p", "format": "PEM", "x": 1}}}}""",
            )
        val onlyFormat =
            EnvDocument(
                """{"dev": {}}""",
                """{"dev": {"SSLConfiguration": {"clientCertificate": {"format": "PEM"}}}}""",
            )
        val plain = EnvDocument("""{"dev": {}}""", """{"dev": {"SSLConfiguration": {"clientCertificate": "p"}}}""")
        val certificate = CertificatePart.CERTIFICATE

        assertEquals(
            edit(null, """{ "dev": { "SSLConfiguration": { "clientCertificate": { "path": "p", "x": 1 } } } }"""),
            withExtra.setCertificateFormat("dev", certificate, null),
        )
        assertEquals(
            edit(
                null,
                """{ "dev": { "SSLConfiguration": { "clientCertificate": { "path": "p", "format": "DER", "x": 1 } } } }""",
            ),
            withExtra.setCertificateFormat("dev", certificate, "DER"),
        )
        assertEquals(
            edit(null, """{ "dev": { "SSLConfiguration": { "clientCertificate": "" } } }"""),
            onlyFormat.setCertificateFormat("dev", certificate, null),
        )
        assertTrue(plain.setCertificateFormat("dev", certificate, null).isEmpty)
        assertEquals(
            edit(null, """{ "dev": { "SSLConfiguration": { "clientCertificate": { "path": "p", "x": 1 } } } }"""),
            withExtra.setCertificateFormat("dev", certificate, "  "),
        )
        assertEquals(
            edit(
                null,
                """{ "dev": { "SSLConfiguration": { "clientCertificate": { "path": "p", "format": "P12" } } } }""",
            ),
            plain.setCertificateFormat("dev", certificate, " P12 "),
        )
    }

    @Test
    fun `a value of the format row is read as a format, null and blank texts meaning none`() {
        val document =
            EnvDocument(
                """{"dev": {}}""",
                """{"dev": {"SSLConfiguration": {"clientCertificate": {"path": "p", "format": "PEM"}}}}""",
            )
        val format = cert.key("format")

        assertEquals(
            edit(null, """{ "dev": { "SSLConfiguration": { "clientCertificate": "p" } } }"""),
            document.setValue("dev", format, EnvJson.nodes.nullNode()),
        )
        assertEquals(
            edit(null, """{ "dev": { "SSLConfiguration": { "clientCertificate": "p" } } }"""),
            document.setValue("dev", format, text("  ")),
        )
        assertEquals(
            edit(
                null,
                """{ "dev": { "SSLConfiguration": { "clientCertificate": { "path": "p", "format": "DER" } } } }""",
            ),
            document.setValue("dev", format, text(" DER ")),
        )
        assertEquals(
            edit(
                null,
                """{ "dev": { "SSLConfiguration": { "clientCertificate": { "path": "p", "format": "5" } } } }""",
            ),
            document.setValue("dev", format, EnvJson.nodes.numberNode(5)),
        )
    }
}
