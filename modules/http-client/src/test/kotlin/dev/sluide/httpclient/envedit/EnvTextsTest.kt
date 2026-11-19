package dev.sluide.httpclient.envedit

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvTextsTest {
    private fun cell(
        json: String,
        side: FileSide? = FileSide.PUBLIC,
    ) = Cell(EnvJson.parse(json), side, false)

    @Test
    fun `shows values as written, containers by their size and undefined values as a dash`() {
        assertEquals("a b", EnvTexts.display(cell("\"a b\""), false))
        assertEquals("1e3", EnvTexts.display(cell("1e3"), false))
        assertEquals("true", EnvTexts.display(cell("true"), false))
        assertEquals("null", EnvTexts.display(cell("null"), false))
        assertEquals("{2}", EnvTexts.display(cell("""{"a": 1, "b": 2}"""), false))
        assertEquals("[3]", EnvTexts.display(cell("[1, 2, 3]"), false))
        assertEquals(EnvTexts.UNDEFINED, EnvTexts.display(Cell.UNDEFINED, false))
        assertEquals(EnvTexts.MASK, EnvTexts.display(cell("\"secret\""), true))
    }

    @Test
    fun `masks private texts and numbers while values are not shown, but not empty texts`() {
        assertTrue(EnvTexts.isMasked(cell("\"s\"", FileSide.PRIVATE), showValues = false))
        assertTrue(EnvTexts.isMasked(cell("42", FileSide.PRIVATE), showValues = false))
        assertFalse(EnvTexts.isMasked(cell("\"s\"", FileSide.PRIVATE), showValues = true))
        assertFalse(EnvTexts.isMasked(cell("\"s\"", FileSide.PUBLIC), showValues = false))
        assertFalse(EnvTexts.isMasked(cell("true", FileSide.PRIVATE), showValues = false))
        assertFalse(EnvTexts.isMasked(cell("\"\"", FileSide.PRIVATE), showValues = false))
        assertFalse(EnvTexts.isMasked(Cell.UNDEFINED, showValues = false))
    }

    @Test
    fun `edits texts and numbers as written`() {
        assertEquals("a", EnvTexts.editText(cell("\"a\"")))
        assertEquals("1.50", EnvTexts.editText(cell("1.50")))
        assertEquals("", EnvTexts.editText(Cell.UNDEFINED))
    }

    @Test
    fun `sums up an auth configuration by the first environment that has it`() {
        val document =
            EnvDocument(
                """
                {"a": {"x": 1},
                 "b": {"Security": {"Auth": {"api": {"Type": "OAuth2", "Grant Type": "Password"}}}},
                 "c": {"Security": {"Auth": {"api": {"Type": "OAuth2", "Grant Type": "Client Credentials"}}}}}
                """.trimIndent(),
                null,
            )
        val group = document.table.auth.single()

        assertEquals("OAuth2 · Password", EnvTexts.authSummary(group))
        assertEquals("Password", EnvTexts.grantType(group))
        assertEquals(setOf("b", "c"), EnvTexts.authEnvironments(group))
    }

    @Test
    fun `calls fields with choices a choice`() {
        val document = EnvDocument("""{"dev": {"Security": {"Auth": {"api": {"Grant Type": "Password"}}}}}""", null)
        val rows =
            document.table.auth
                .single()
                .rows
                .associateBy { it.label }

        assertEquals("Choice", EnvTexts.typeLabel(rows.getValue("Grant Type")))
        assertEquals("Text", EnvTexts.typeLabel(rows.getValue("Token URL")))
        assertEquals("Yes/No", EnvTexts.typeLabel(rows.getValue("Use ID Token")))
    }

    @Test
    fun `writes certificate files inside the private file's folder relative to it, others absolute`() {
        val folder = Path.of("/work/api").toAbsolutePath()

        assertEquals("certs/client.pem", EnvTexts.certificatePath(folder, folder.resolve("certs/client.pem")))
        assertEquals("client.pem", EnvTexts.certificatePath(folder, folder.resolve("./client.pem")))
        val outside = folder.resolveSibling("other/client.pem")
        assertEquals(outside.toString(), EnvTexts.certificatePath(folder, outside))
        assertEquals(outside.toString(), EnvTexts.certificatePath(folder, folder.resolve("../other/client.pem")))
    }

    @Test
    fun `chains edits into one of the files that changed`() {
        val document = EnvDocument("""{"a": {}, "b": {}}""", null)

        val edit =
            chain(
                document,
                listOf({ d: EnvDocument -> d.addCertificate(listOf("a")) }, { d -> d.addCertificate(listOf("b")) }),
            )

        assertEquals(null, edit.public)
        assertEquals(setOf("a", "b"), EnvTexts.certificateEnvironments(document.after(edit).table))
    }

    @ParameterizedTest
    @ValueSource(strings = ["true", "null", """{"a": 1}""", "[1]"])
    fun `starts an editor empty for values that are neither text nor number`(json: String) {
        assertEquals("", EnvTexts.editText(cell(json)))
    }

    @Test
    fun `tells no environments and the default grant type for an auth configuration without those fields`() {
        val document = EnvDocument("""{"dev": {"Security": {"Auth": {"api": {"Token URL": "https://t"}}}}}""", null)
        val group = document.table.auth.single()

        assertEquals("Client Credentials", EnvTexts.grantType(group))
        assertEquals("", EnvTexts.authSummary(group))
        assertEquals(emptySet(), EnvTexts.authEnvironments(AuthGroup("api", emptyList())))
        assertEquals("Client Credentials", EnvTexts.grantType(AuthGroup("api", emptyList())))
    }

    @Test
    fun `skips blank and non-text values when it looks for the first auth text`() {
        val document =
            EnvDocument(
                """
                {"a": {"Security": {"Auth": {"api": {"Type": "", "Grant Type": 5}}}},
                 "b": {"Security": {"Auth": {"api": {"Type": "  ", "Grant Type": null}}}},
                 "c": {"Security": {"Auth": {"api": {"Type": "OAuth2", "Grant Type": "Password"}}}}}
                """.trimIndent(),
                null,
            )
        val group = document.table.auth.single()

        assertEquals("OAuth2 · Password", EnvTexts.authSummary(group))
        assertEquals("Password", EnvTexts.grantType(group))
    }

    @Test
    fun `tells the environments of the client certificate, none without one`() {
        assertEquals(emptySet(), EnvTexts.certificateEnvironments(EnvDocument("""{"a": {}}""", null).table))
        val withCertificate =
            EnvDocument(
                """{"a": {}, "b": {}}""",
                null,
            ).let { it.after(it.addCertificate(listOf("b"))) }

        assertEquals(setOf("b"), EnvTexts.certificateEnvironments(withCertificate.table))
    }

    @Test
    fun `writes the folder itself absolute, not as an empty relative path`() {
        val folder = Path.of("/work/api").toAbsolutePath()

        assertEquals(folder.toString(), EnvTexts.certificatePath(folder, folder))
        assertEquals(folder.toString(), EnvTexts.certificatePath(folder, folder.resolve("certs/..")))
    }

    @Test
    fun `chains an edit of the public file and keeps the private file out of it`() {
        val document = EnvDocument("""{"a": {}}""", """{"a": {}}""")

        val edit =
            chain(document, listOf({ d: EnvDocument -> d.addVariable(ValuePath.ROOT, "x", ValueType.TEXT) }))

        assertEquals(null, edit.private)
        assertEquals(true, edit.public?.contains("\"x\""))
    }

    @Test
    fun `keeps the other file's text when an edit changes only one`() {
        val document = EnvDocument("""{"a": {}}""", """{"b": {}}""")

        val privateOnly = document.after(EnvEdit(public = null, private = """{"c": {}}"""))
        val publicOnly = document.after(EnvEdit(public = """{"d": {}}""", private = null))

        assertEquals("""{"a": {}}""", privateOnly.publicText)
        assertEquals("""{"c": {}}""", privateOnly.privateText)
        assertEquals("""{"d": {}}""", publicOnly.publicText)
        assertEquals("""{"b": {}}""", publicOnly.privateText)
    }
}
