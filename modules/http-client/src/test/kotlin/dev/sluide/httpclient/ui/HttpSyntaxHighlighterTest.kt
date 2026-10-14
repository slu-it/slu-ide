package dev.sluide.httpclient.ui

import dev.sluide.fileviewers.TokenStyle.ANNOTATION
import dev.sluide.fileviewers.TokenStyle.COMMENT
import dev.sluide.fileviewers.TokenStyle.KEYWORD
import dev.sluide.fileviewers.TokenStyle.NUMBER
import dev.sluide.fileviewers.TokenStyle.OPERATOR
import dev.sluide.fileviewers.TokenStyle.PROPERTY
import dev.sluide.fileviewers.TokenStyle.STRING
import dev.sluide.fileviewers.TokenStyle.TAG
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpSyntaxHighlighterTest {
    private val highlighter = HttpSyntaxHighlighter()

    private val sample =
        """
        |@host = https://example.com
        |@token = {{${'$'}random.uuid}}
        |
        |### Get user
        |# @name getUser
        |# @timeout 5 s
        |// just a comment
        |GET {{host}}/users/1?x=1 HTTP/1.1
        |Accept: application/json
        |Authorization: Bearer {{token}}
        |
        |###
        |POST {{host}}/users
        |    ?verbose=true
        |Content-Type: application/json
        |
        |{
        |  "name": "{{name}}",
        |  "age": 42
        |}
        |
        |> {%
        |  client.test("ok", function() {});
        |%}
        |
        |### Upload
        |POST {{host}}/upload
        |Content-Type: application/xml
        |
        |<a>1</a>
        |< ./data/{{file}}.xml
        |
        |### Plain
        |PUT {{host}}/text
        |Content-Type: text/plain
        |
        |# not a comment in a body
        |
        """.trimMargin()

    private val http = Highlighted(sample, highlighter)

    @Test
    fun `variable definitions and their uses are annotations`() {
        assertEquals(setOf(ANNOTATION), http.stylesOf("@host"))
        assertEquals(emptySet(), http.stylesOf("https://example.com"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{\$random.uuid}}"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{token}}"))
    }

    @Test
    fun `separators and comments are comments, request tags annotations`() {
        assertEquals(setOf(COMMENT), http.stylesOf("### Get user"))
        assertEquals(setOf(COMMENT), http.stylesOf("// just a comment"))
        assertEquals(setOf(COMMENT, ANNOTATION), http.stylesOf("# @name"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("@name"))
        assertEquals(emptySet(), http.stylesOf("getUser"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("@timeout"))
    }

    @Test
    fun `request lines have a method, a URL and a version`() {
        assertEquals(setOf(KEYWORD), http.stylesOf("GET"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{host}}"))
        assertEquals(setOf(STRING), http.stylesOf("/users/1?x=1"))
        assertEquals(setOf(KEYWORD), http.stylesOf("HTTP/1.1"))
        assertEquals(setOf(KEYWORD), http.stylesOf("POST"))
        assertEquals(setOf(STRING), http.stylesOf("?verbose=true"))
    }

    @Test
    fun `header names are properties, their values plain`() {
        assertEquals(setOf(PROPERTY), http.stylesOf("Accept"))
        assertEquals(emptySet(), http.stylesOf("application/json"))
        assertEquals(emptySet(), http.stylesOf("Bearer"))
        assertEquals(setOf(PROPERTY), http.stylesOf("Content-Type"))
    }

    @Test
    fun `bodies are highlighted by their content type, with variables on top`() {
        assertEquals(setOf(PROPERTY), http.stylesOf("\"name\""))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{name}}"))
        assertEquals(setOf(STRING, ANNOTATION), http.stylesOf("\"{{name}}\""))
        assertEquals(setOf(NUMBER), http.stylesOf("42"))
        assertTrue(TAG in http.stylesOf("<a>"))
        assertEquals(emptySet(), http.stylesOf("# not a comment in a body"))
    }

    @Test
    fun `file bodies have a marker and a path`() {
        assertEquals(setOf(OPERATOR), http.stylesOf("< "))
        assertEquals(setOf(STRING), http.stylesOf("./data/"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{file}}"))
        assertEquals(setOf(STRING), http.stylesOf(".xml"))
    }

    @Test
    fun `scripts are JavaScript between operators`() {
        assertEquals(setOf(OPERATOR), http.stylesOf("> {%"))
        assertEquals(setOf(KEYWORD), http.stylesOf("function"))
        assertEquals(setOf(STRING), http.stylesOf("\"ok\""))
        assertEquals(setOf(OPERATOR), http.stylesOf("%}"))
    }

    @Test
    fun `script files are strings`() {
        val http = Highlighted("< pre.js\nGET http://x\n\n> after.js\n", highlighter)

        assertEquals(setOf(OPERATOR), http.stylesOf("<"))
        assertEquals(setOf(STRING), http.stylesOf("pre.js"))
        assertEquals(setOf(STRING), http.stylesOf("after.js"))
    }

    @Test
    fun `a separator inside a script is script code`() {
        val http = Highlighted("GET http://x\n\n> {%\n  /* ### no separator */\n%}\nGET\n", highlighter)

        assertEquals(setOf(COMMENT), http.stylesOf("/* ### no separator */"))
        assertEquals(setOf(OPERATOR), http.stylesOf("%}"))
    }

    @Test
    fun `unknown tags are comments`() {
        val http = Highlighted("# @no-log\nGET http://x\n", highlighter)

        assertEquals(setOf(COMMENT), http.stylesOf("# @no-log"))
    }

    @Test
    fun `a URL without a method is a string`() {
        val http = Highlighted("https://example.com/{{path}}\n", highlighter)

        assertEquals(setOf(STRING), http.stylesOf("https://example.com/"))
        assertEquals(setOf(ANNOTATION), http.stylesOf("{{path}}"))
    }

    @Test
    fun `bodies of other content types stay plain`() {
        val http = Highlighted("POST http://x\nContent-Type: text/plain\n\n{\"a\": 1}\n", highlighter)

        assertEquals(emptySet(), http.stylesOf("{\"a\": 1}"))
    }

    @Test
    fun `CRLF line breaks keep the offsets`() {
        val http = Highlighted("GET http://x\r\nAccept: */*\r\n\r\n### next\r\n", highlighter)

        assertEquals(setOf(PROPERTY), http.stylesOf("Accept"))
        assertEquals(setOf(COMMENT), http.stylesOf("### next"))
        http.assertWellFormed()
    }

    @Test
    fun `scripts in a row end at their own close`() {
        val http = Highlighted("GET http://x\n\n> {% one %}\n> {%\n  two\n%}\n> {% three\n", highlighter)

        assertEquals(setOf(OPERATOR), http.stylesOf("%}", 0))
        assertEquals(setOf(OPERATOR), http.stylesOf("%}", 1))
        assertEquals(setOf(OPERATOR), http.stylesOf("> {%", 2))
        assertEquals(emptySet(), http.stylesOf("two"))
        http.assertWellFormed()
    }

    @Test
    fun `many scripts in a row take linear time`() {
        // Each script used to scan all script lines after it: about 3 s for 100 000 scripts, growing with the square;
        // now under a second for these.
        val scripts = 300_000
        val text = "GET http://x\n\n" + "> {%\n  client.log(1)\n%}\n".repeat(scripts)

        val started = System.nanoTime()
        val spans = HttpSyntaxHighlighter().highlight(text)
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertEquals(scripts, spans.count { it.style == OPERATOR && text.startsWith("%}", it.start) })
        assertTrue(elapsed < Duration.ofSeconds(10), "took $elapsed")
    }

    @Test
    fun `spans are well-formed`() = http.assertWellFormed()

    @Test
    fun `spans are well-formed while typing`() {
        for (length in 0..sample.length) Highlighted(sample.substring(0, length), highlighter).assertWellFormed()
    }
}
