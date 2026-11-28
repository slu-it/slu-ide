package dev.sluide.httpclient.complete

import dev.sluide.httpclient.parse.HttpFileParser
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RequestCompletionTest {
    private val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "CONNECT", "TRACE")

    @ParameterizedTest
    @ValueSource(
        strings = [
            "|",
            "G|",
            "GE|",
            "# note\n@a = 1\n|",
            "# @name x\nG|",
            "GET /a\n###\n|",
            "GET /a\n### second\nPO|",
            "  PO|",
            "GET /a\r\n###\r\nG|",
            "GET /a\n\nbody\n###\n|\n",
        ],
    )
    fun `offers the methods in the first word of a request line`(marked: String) {
        val answer = answer(marked)

        assertEquals(methods, answer.labels)
        assertTrue(answer.replaced.trim().all { it.isLetter() }, answer.replaced)
    }

    @Test
    fun `anchors a method at the start of its word`() {
        assertEquals("GE", answer("GET /a\n###\nGE|").replaced)
        assertEquals("PO", answer("  PO|").replaced)
        assertEquals("", answer("# c\n|").replaced)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "# G|",
            "// |",
            "@a = G|",
            "GET |",
            "GET /a|",
            "GET /a HTTP/1.1 |",
            "/a |",
            "/a b |",
            "< {%\nG|",
            "GET /a\n  G|",
            "GET /a\nHost: x\n\nG|",
        ],
    )
    fun `offers nothing elsewhere in the lines of a request`(marked: String) {
        assertEquals(emptyList(), answer(marked).labels)
    }

    @Test
    fun `offers the versions in the third token`() {
        val answer = answer("GET /a |")

        assertEquals(listOf("HTTP/1.1", "HTTP/2"), answer.labels)
        assertEquals("", answer.replaced)
        assertEquals("HTTP/", answer("GET /a  HTTP/|").replaced)
        assertEquals(listOf("HTTP/1.1", "HTTP/2"), answer("GET /a  HTTP/|").labels)
        assertEquals(listOf("HTTP/1.1", "HTTP/2"), answer("POST {{h}}/x HTTP/1|").labels)
    }

    @Test
    fun `holds back versions that do not start with the fixed part`() {
        assertEquals(emptyList(), answer("GET /a X-|").labels)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /a\nContent-T|",
            "GET /a\r\nContent-T|",
            "GET /a\n  {{h}}\nContent-T|",
            "GET /a\nAccept: x\nContent-T|",
            "GET /a\n# c\nContent-T|",
            "GET /a\n###\nGET /b\nContent-T|",
        ],
    )
    fun `offers header names with the fixed part`(marked: String) {
        val answer = answer(marked)

        assertEquals(listOf("Content-Disposition", "Content-Encoding", "Content-Length", "Content-Type"), answer.labels)
        assertEquals("Content-T", answer.replaced)
        assertEquals("Content-Type: ", answer.item("Content-Type").insertText)
        assertEquals(null, answer.item("Content-Type").detail)
    }

    @Test
    fun `offers the header names in the first blank line after the request`() {
        val answer = answer("GET /a\nHost: x\n|")

        assertTrue("Content-Type" in answer.labels)
        assertEquals("", answer.replaced)
        assertTrue("Content-Type" in answer("GET /a\n|").labels)
        assertEquals(emptyList(), answer("GET /a\n\n|").labels)
        assertEquals(emptyList(), answer("GET /a\nHost: x\n\nCont|").labels)
    }

    @Test
    fun `inserts only the name when a colon follows`() {
        val answer = answer("GET /a\nCont|: x")

        assertEquals("Content-Type", answer.item("Content-Type").insertText)
        assertEquals("Content-Type: ", answer("GET /a\nCont|").item("Content-Type").insertText)
    }

    @Test
    fun `adds the header names of the file once, ignoring case`() {
        val marked = "GET /a\ncontent-type: x\nX-Custom: 1\n###\nGET /b\nx-custom: 2\nX-Other: 3\nX-|\n"

        val answer = answer(marked)

        assertEquals("in this file", answer.item("X-Custom").detail)
        assertEquals("in this file", answer.item("X-Other").detail)
        assertEquals(null, answer.item("X-Api-Key").detail)
        assertEquals(1, answer.labels.count { it.equals("X-Custom", ignoreCase = true) })
        assertEquals(1, answer("GET /a\ncontent-type: x\nCont|").labels.count { it.equals("Content-Type", true) })
        assertEquals(null, answer("GET /a\ncontent-type: x\nCont|").item("Content-Type").detail)
    }

    @Test
    fun `leaves out the header being edited and offers the curated names`() {
        val answer = answer("GET /a\nX-Mine|: 1")

        assertEquals(emptyList(), answer.labels.filter { it == "X-Mine" })
        val names = answer("GET /a\n|").labels
        assertTrue(names.size in 38..60, names.size.toString())
        for (name in listOf("Accept", "Authorization", "Content-Type", "Cookie", "User-Agent", "X-Request-ID")) {
            assertTrue(name in names, name)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["GET /a\n  Cont|", "GET /a\nCon tent|", "GET /a\n{{|", "GET /a\nX{|", "GET /a\nHost: x\n  Co|"],
    )
    fun `offers no header names where none belong`(marked: String) {
        assertEquals(emptyList(), answer(marked).labels)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = [
            "GET /a~Content-Type: application/|;application/;application/json,application/xml,application/problem+json",
            "GET /a~Content-Type:|;;application/json,text/plain,multipart/form-data",
            "GET /a~content-type:    t|;t;text/plain,text/html,text/xml,text/csv",
            "GET /a~Accept: |;;*/*,application/json",
            "GET /a~Accept: text/html, application/|;application/;application/json",
            "GET /a~Authorization: |;;Bearer ,Basic ,Digest ",
            "GET /a~Authorization: Bea|;Bea;Bearer ",
            "GET /a~Accept-Encoding: gzip, |;;gzip,deflate,br,identity",
            "GET /a~Cache-Control: no-|;no-;no-cache,no-store",
            "GET /a~Connection: k|;k;keep-alive",
        ],
    )
    fun `offers header values for known headers`(
        marked: String,
        replaced: String?,
        expected: String,
    ) {
        val answer = answer(marked.replace('~', '\n'))

        assertEquals(replaced ?: "", answer.replaced)
        assertTrue(
            answer.labels.map { it.trim() }.containsAll(expected.split(',').map { it.trim() }),
            answer.labels.toString(),
        )
        val fixed =
            replaced.orEmpty().let {
                it.substring(
                    0,
                    it.length - it.takeLastWhile { c -> c.isLetterOrDigit() || c == '_' }.length,
                )
            }
        assertTrue(answer.labels.all { it.startsWith(fixed, ignoreCase = true) }, answer.labels.toString())
    }

    @Test
    fun `offers the media types only below the fixed part`() {
        val labels = answer("GET /a\nContent-Type: text/|").labels
        assertTrue(labels.isNotEmpty() && labels.all { it.startsWith("text/") }, labels.toString())
        assertEquals(emptyList(), answer("GET /a\nX-Unknown: |").labels)
        assertEquals(emptyList(), answer("GET /a\nContent-Length: |").labels)
        assertEquals(emptyList(), answer("GET /a\nBad Name: |").labels)
        assertEquals(emptyList(), answer("GET /a\n: |").labels)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = [
            "# @|;;name,no-redirect,no-cookie-jar,timeout,connection-timeout",
            "// @|;;name,no-redirect",
            "#@n|;n;name,no-redirect,no-cookie-jar",
            "# @no-|;no-;no-redirect,no-cookie-jar",
            "GET /a~###~# @conn|;conn;connection-timeout",
            "@a = 1~# @|;;timeout",
        ],
    )
    fun `offers the request tags after an at sign in a comment before the request line`(
        marked: String,
        replaced: String?,
        expected: String,
    ) {
        val answer = answer(marked.replace('~', '\n'))

        assertEquals(replaced ?: "", answer.replaced)
        assertTrue(
            answer.labels.map { it.trim() }.containsAll(expected.split(',').map { it.trim() }),
            answer.labels.toString(),
        )
        assertEquals(5, answer("# @|").labels.size)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "# hello|",
            "# a @|",
            "# @name foo|",
            "// GET|",
            "GET /a\n# @|",
            "GET /a\n\nbody @|",
            "GET /a\n\n# @|",
            "### @|",
            "POST /a\n\n{|",
            "POST /a\n\n{\n  |",
            "< {%\n  client.global.set(\"a\", 1)|\n%}",
            "GET /a\n\n{}\n> {%\n  cli|\n%}",
            "GET /a\n\n{}\n<> prev|",
            "GET /a\n\n{}\n|",
        ],
    )
    fun `offers nothing in comments, scripts and bodies`(marked: String) {
        val answer = answer(marked)

        assertEquals(emptyList(), answer.labels)
        assertEquals(answer.caret, answer.anchor)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GET /a\nX: b\n|  more",
            "GET /a\n|# comment",
            "GET /a\n> {% x %}\nCont|",
            "GET /a\nX: 1\n<> previous\nCont|",
            "GET /a\nBad Name: v|",
            "GET /a\n: x|",
        ],
    )
    fun `offers nothing in continuations, comments, after scripts and for what is no header`(marked: String) {
        val answer = answer(marked)

        assertEquals(emptyList(), answer.labels)
        assertEquals(answer.caret, answer.anchor)
    }

    @Test
    fun `trims the header name before the colon`() {
        val answer = answer("GET /a\nContent-Type : app|")

        assertEquals("app", answer.replaced)
        assertTrue("application/json" in answer.labels)
    }

    @Test
    fun `offers the tags the parser reads`() {
        assertEquals(HttpFileParser.TAGS.toList(), answer("# @|").labels)
    }

    @Test
    fun `completes the elements of a list header but not of others`() {
        assertEquals(emptyList(), answer("GET /a\nAuthorization: Bearer a, |").labels)
        assertEquals(emptyList(), answer("GET /a\nContent-Type: x, application/|").labels)
        assertEquals("", answer("GET /a\nAccept: x, |").replaced)
    }

    @Test
    fun `offers a tag for a complete tag line and skips tabs before a value`() {
        assertEquals("no-", answer("# @no-redirect|").replaced.substring(0, 3))
        assertTrue("no-redirect" in answer("# @no-redirect|").labels)
        assertEquals("application/", answer("GET /a\nContent-Type:\t\tapplication/|").replaced)
        assertEquals("app", answer("GET /a\nAccept: x,\t app|").replaced)
    }

    @Test
    fun `answers nothing for a caret between a carriage return and its line feed`() {
        val answer = CompletionEngine { emptySet() }.complete("GET /a\r\nCont", 7) { NO_ENVIRONMENTS }

        assertEquals(emptyList(), answer.items)
        assertEquals(7, answer.anchor)
    }

    @Test
    fun `takes a caret out of range as the nearest end`() {
        val engine = CompletionEngine { emptySet() }

        assertEquals(6, engine.complete("GET /a", 99) { NO_ENVIRONMENTS }.anchor)
        assertEquals(
            listOf("HTTP/1.1", "HTTP/2"),
            engine.complete("GET /a ", 99) { NO_ENVIRONMENTS }.items.map { it.label },
        )
        assertEquals(methods, engine.complete("GET /a", -5) { NO_ENVIRONMENTS }.items.map { it.label })
    }

    @Test
    fun `reads the environments only for variables`() {
        val engine = CompletionEngine { emptySet() }

        engine.complete("GET /a\nCont", 11) { error("must not be read") }
        engine.complete("GE", 2) { error("must not be read") }
    }
}
