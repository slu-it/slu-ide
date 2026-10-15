package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CaddyfileHighlightingTest {
    private val d = "$"

    private val caddy =
        Highlighted(
            """
            |# global options
            |{
            |    email admin@example.com
            |}
            |(common) {
            |    encode gzip
            |}
            |example.com, www.example.com {
            |    import common
            |    @api path /api/*
            |    reverse_proxy @api localhost:8080 {
            |        header_up Host {host} # keep the host
            |    }
            |    respond "Not found: {path}" 404
            |    root * {${d}SITE_ROOT}
            |    file_server
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.caddyfile,
        )

    @Test
    fun `site addresses and snippets are types`() {
        assertEquals(setOf(TokenStyle.TYPE), caddy.stylesOf("example.com, www.example.com"))
        assertEquals(setOf(TokenStyle.TYPE), caddy.stylesOf("(common)"))
        assertEquals(emptySet(), caddy.stylesOf("localhost:8080"), "an indented block is no site")
    }

    @Test
    fun `directives are keywords`() {
        for (directive in listOf("email", "encode", "import", "reverse_proxy", "header_up", "respond", "root")) {
            assertEquals(setOf(TokenStyle.KEYWORD), caddy.stylesOf(directive), directive)
        }
        assertEquals(setOf(TokenStyle.KEYWORD), caddy.stylesOf("file_server"))
        assertEquals(emptySet(), caddy.stylesOf("gzip"))
    }

    @Test
    fun `matchers and placeholders are annotations`() {
        assertEquals(setOf(TokenStyle.ANNOTATION), caddy.stylesOf("@api"))
        assertEquals(setOf(TokenStyle.ANNOTATION), caddy.stylesOf("@api", occurrence = 1))
        assertEquals(setOf(TokenStyle.ANNOTATION), caddy.stylesOf("{host}"))
        assertEquals(setOf(TokenStyle.ANNOTATION), caddy.stylesOf("{${d}SITE_ROOT}"))
        assertEquals(emptySet(), caddy.stylesOf("@example.com"), "an e-mail address has no matcher")
    }

    @Test
    fun `strings and comments`() {
        assertEquals(setOf(TokenStyle.STRING), caddy.stylesOf("\"Not found: {path}\""))
        assertEquals(setOf(TokenStyle.COMMENT), caddy.stylesOf("# global options"))
        assertEquals(setOf(TokenStyle.COMMENT), caddy.stylesOf("# keep the host"))
    }

    @Test
    fun `escaped quotes and backslashes in strings`() {
        val text = "    respond \"say \\\"hi\\\"\" 200\n    header X \"a\\\\\" {path}\n"
        val escaped = Highlighted(text, RSyntaxHighlighter.caddyfile)

        assertEquals(setOf(TokenStyle.STRING), escaped.stylesOf("\"say \\\"hi\\\"\""))
        assertEquals(emptySet(), escaped.stylesOf("200"))
        assertEquals(setOf(TokenStyle.STRING), escaped.stylesOf("\"a\\\\\""))
        assertEquals(setOf(TokenStyle.ANNOTATION), escaped.stylesOf("{path}"), "the string ended at the quote")
    }

    @Test
    fun `fenced Caddyfiles are highlighted`() =
        assertSame(RSyntaxHighlighter.caddyfile, RSyntaxHighlighter.forLanguage("caddyfile"))

    @Test
    fun `spans are well-formed`() = caddy.assertWellFormed()
}
