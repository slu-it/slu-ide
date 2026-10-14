package dev.sluide.httpclient.parse

import dev.sluide.httpclient.parse.BodyPart.FileRef
import dev.sluide.httpclient.parse.BodyPart.Text
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpFileParserTest {
    private fun parse(vararg lines: String) = HttpFileParser.parse(lines.joinToString("\n"))

    private fun single(vararg lines: String): RequestBlock = parse(*lines).requests.single()

    @Test
    fun `parses a request line with method, URL and version`() {
        val request = single("POST https://example.com/api HTTP/1.1")

        assertEquals("POST", request.method)
        assertEquals("https://example.com/api", request.url)
        assertEquals(HttpVersion.HTTP_1_1, request.version)
        assertEquals(0, request.requestLine)
        assertEquals(0..0, request.lines)
    }

    @Test
    fun `defaults to GET without a version`() {
        val request = single("https://example.com/{{path}}")

        assertEquals("GET", request.method)
        assertEquals("https://example.com/{{path}}", request.url)
        assertNull(request.version)
    }

    @Test
    fun `knows all methods`() {
        for (method in listOf("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "PATCH", "OPTIONS", "TRACE")) {
            assertEquals(method, single("$method http://x").method)
        }
    }

    @Test
    fun `reports unsupported request types`() {
        val file = parse("GRAPHQL http://x/graphql", "", "{ a }")

        assertEquals("GRAPHQL", file.requests.single().method)
        assertEquals(listOf(ParseProblem(0, "GRAPHQL requests are not supported")), file.problems)
    }

    @Test
    fun `reads HTTP 2 and notes prior knowledge`() {
        assertEquals(HttpVersion.HTTP_2, single("GET http://x HTTP/2").version)

        val file = parse("GET http://x HTTP/2 (Prior Knowledge)")
        assertEquals(HttpVersion.HTTP_2, file.requests.single().version)
        assertEquals("http://x", file.requests.single().url)
        assertEquals(0, file.problems.single().line)
    }

    @Test
    fun `joins indented URL continuation lines`() {
        val request =
            single(
                "GET http://localhost:80/api",
                "    ?parameter1=value1",
                "    &parameter2=value2 HTTP/1.1",
                "Accept: text/plain",
            )

        assertEquals("http://localhost:80/api?parameter1=value1&parameter2=value2", request.url)
        assertEquals(HttpVersion.HTTP_1_1, request.version)
        assertEquals(listOf(Header("Accept", "text/plain", 3)), request.headers)
    }

    @Test
    fun `reads headers with comments in between and continuation lines`() {
        val request =
            single(
                "GET http://x",
                "Accept: application/json",
                "# a comment",
                "// another comment",
                "X-List: a,",
                "  b",
                "Authorization: Bearer {{token}}",
            )

        assertEquals(
            listOf(
                Header("Accept", "application/json", 1),
                Header("X-List", "a, b", 4),
                Header("Authorization", "Bearer {{token}}", 6),
            ),
            request.headers,
        )
    }

    @Test
    fun `reports lines among the headers that are no header`() {
        val file = parse("GET http://x", "not a header", "Accept: */*")

        assertEquals(listOf(Header("Accept", "*/*", 2)), file.requests.single().headers)
        assertEquals(1, file.problems.single().line)
    }

    @Test
    fun `reads the body after the blank line and trims blank lines around it`() {
        val request =
            single(
                "POST http://x",
                "Content-Type: application/json",
                "",
                "",
                "{",
                "  \"a\": 1,",
                "",
                "  # not a comment here",
                "}",
                "",
                "",
            )

        assertEquals(listOf(Text("{\n  \"a\": 1,\n\n  # not a comment here\n}")), request.body)
        assertEquals(0..8, request.lines)
    }

    @Test
    fun `keeps comment lines inside the body as text`() {
        val request = single("POST http://x", "", "# text", "// text")

        assertEquals(listOf(Text("# text\n// text")), request.body)
    }

    @Test
    fun `reads file references in the body`() {
        val request =
            single(
                "POST http://x",
                "Content-Type: multipart/form-data; boundary=B",
                "",
                "--B",
                "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"",
                "",
                "< ./a.txt",
                "--B--",
            )

        assertEquals(
            listOf(
                Text("--B\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\n"),
                FileRef("./a.txt", 6),
                Text("--B--"),
            ),
            request.body,
        )
    }

    @Test
    fun `takes a body that is only a file reference`() {
        assertEquals(listOf(FileRef("{{dir}}/body.json", 2)), single("POST http://x", "", "< {{dir}}/body.json").body)
    }

    @Test
    fun `reads VS Code file references with and without encoding`() {
        assertEquals(
            listOf(FileRef("./a.json", 2), FileRef("b.txt", 3)),
            single("POST http://x", "", "<@ ./a.json", "<@latin1 b.txt").body,
        )
    }

    @Test
    fun `keeps XML starting with an angle bracket as text`() {
        assertEquals(listOf(Text("<a>\n</a>")), single("POST http://x", "", "<a>", "</a>").body)
    }

    @Test
    fun `separates requests and names them`() {
        val file =
            parse(
                "GET http://one",
                "",
                "### Second request",
                "GET http://two",
                "###",
                "# @name third",
                "GET http://three",
                "### ignored title",
                "// @name = fourth",
                "GET http://four",
                "",
            )

        assertEquals(listOf(null, "Second request", "third", "fourth"), file.requests.map { it.name })
        assertEquals(listOf(0..0, 2..3, 4..6, 7..9), file.requests.map { it.lines })
        assertEquals(listOf(0, 3, 6, 9), file.requests.map { it.requestLine })
        assertTrue(file.problems.isEmpty())
    }

    @Test
    fun `starts the first request at the first line and skips segments without a request`() {
        val file =
            parse(
                "### only a title",
                "# a comment",
                "",
                "###",
                "",
                "GET http://x",
            )

        assertEquals(3..5, file.requests.single().lines)
    }

    @Test
    fun `reads variables outside requests`() {
        val file =
            parse(
                "@host = example.com",
                "@base=https://{{host}}/v1 ",
                "",
                "GET {{base}}/a",
                "",
                "###",
                "@token = abc",
                "GET {{base}}/b",
                "Authorization: Bearer {{token}}",
            )

        assertEquals(
            listOf(
                FileVariable("host", "example.com", 0),
                FileVariable("base", "https://{{host}}/v1", 1),
                FileVariable("token", "abc", 6),
            ),
            file.variables,
        )
        assertEquals(2, file.requests.size)
        assertEquals(0..3, file.requests[0].lines)
    }

    @Test
    fun `reports malformed variables`() {
        val file = parse("@nothing", "GET http://x")

        assertTrue(file.variables.isEmpty())
        assertEquals(0, file.problems.single().line)
    }

    @Test
    fun `reads tags before the request line`() {
        val request =
            single(
                "# @no-redirect",
                "# @no-cookie-jar",
                "# @no-log",
                "# @timeout 500 ms",
                "// @connection-timeout 2 m",
                "GET http://x",
            )

        assertEquals(
            RequestTags(
                noRedirect = true,
                noCookieJar = true,
                timeout = Duration.ofMillis(500),
                connectionTimeout = Duration.ofMinutes(2),
            ),
            request.tags,
        )
    }

    @Test
    fun `takes seconds as the default unit of timeouts`() {
        assertEquals(Duration.ofSeconds(30), single("# @timeout 30", "GET http://x").tags.timeout)
        assertEquals(Duration.ofSeconds(5), single("# @timeout 5s", "GET http://x").tags.timeout)
    }

    @Test
    fun `reports invalid timeouts`() {
        val file =
            parse("# @timeout soon", "# @connection-timeout 0", "# @timeout -5 s", "# @timeout 0 ms", "GET http://x")

        assertEquals(RequestTags(), file.requests.single().tags)
        assertEquals(listOf(0, 1, 2, 3), file.problems.map { it.line })
    }

    @Test
    fun `records scripts without running them`() {
        val request =
            single(
                "< {% request.variables.set('a', 1) %}",
                "< {%",
                "  const b = 2",
                "%}",
                "< ./pre.js",
                "POST http://x",
                "",
                "body",
                "",
                "> {%",
                "  client.test('ok', () => {})",
                "%}",
                "> ./handler.js",
                "<> 2024-01-01T000000.200.json",
            )

        assertEquals(
            listOf(
                ScriptBlock(ScriptBlock.Kind.PRE, 0..0),
                ScriptBlock(ScriptBlock.Kind.PRE, 1..3),
                ScriptBlock(ScriptBlock.Kind.PRE, 4..4),
                ScriptBlock(ScriptBlock.Kind.RESPONSE, 9..11),
                ScriptBlock(ScriptBlock.Kind.RESPONSE, 12..12),
            ),
            request.scripts,
        )
        assertEquals(5, request.requestLine)
        assertEquals(listOf(Text("body")), request.body)
        assertEquals(0..13, request.lines)
    }

    @Test
    fun `ends the headers at a response handler`() {
        val request = single("GET http://x", "Accept: */*", "> {% client.log(1) %}")

        assertEquals(listOf(ScriptBlock(ScriptBlock.Kind.RESPONSE, 2..2)), request.scripts)
        assertTrue(request.body.isEmpty())
    }

    @Test
    fun `does not split requests at separators inside scripts`() {
        val file =
            parse(
                "< {%",
                "### not a separator",
                "%}",
                "GET http://x",
                "",
                "> {% const a = 1",
                "###",
                "%}",
                "### next",
                "GET http://y",
            )

        assertEquals(listOf(0..7, 8..9), file.requests.map { it.lines })
        assertEquals(
            listOf(ScriptBlock(ScriptBlock.Kind.PRE, 0..2), ScriptBlock(ScriptBlock.Kind.RESPONSE, 5..7)),
            file.requests.first().scripts,
        )
        assertTrue(file.problems.isEmpty())
    }

    @Test
    fun `reports unclosed scripts`() {
        val file = parse("GET http://x", "", "> {%", "  client.log(1)")

        assertEquals(
            ScriptBlock(ScriptBlock.Kind.RESPONSE, 2..3),
            file.requests
                .single()
                .scripts
                .single(),
        )
        assertEquals(2, file.problems.single().line)
    }

    @Test
    fun `reports saving responses to files as unsupported`() {
        val file = parse("GET http://x", "", ">> out.json", "", "###", "GET http://y", "", ">>! out.json")

        assertEquals(listOf(2, 7), file.problems.map { it.line })
        assertTrue(file.requests.all { it.body.isEmpty() })
    }

    @Test
    fun `reports text after the response handler`() {
        val file = parse("GET http://x", "", "> ./a.js", "stray")

        assertEquals(3, file.problems.single().line)
    }

    @Test
    fun `reports a request without URL`() {
        val file = parse("GET")

        assertEquals("", file.requests.single().url)
        assertEquals(0, file.problems.single().line)
    }

    @Test
    fun `parses CRLF text`() {
        val file = HttpFileParser.parse("GET http://x\r\nAccept: */*\r\n\r\nbody\r\n")

        assertEquals(listOf(Header("Accept", "*/*", 1)), file.requests.single().headers)
        assertEquals(listOf(Text("body")), file.requests.single().body)
    }

    @Test
    fun `parses an empty file`() {
        assertEquals(HttpFile(emptyList(), emptyList(), emptyList(), listOf(LineKind.OTHER)), HttpFileParser.parse(""))
    }

    @Test
    fun `knows what each line is`() {
        val file =
            parse(
                "@host = http://x",
                "### First",
                "# @name first",
                "# a comment",
                "< {% request.variables.set('a', 1) %}",
                "POST {{host}}/a",
                "    ?b=c",
                "Content-Type: text/plain",
                "X-Long: one",
                "  two",
                "// header comment",
                "",
                "text",
                "< ./file.txt",
                "",
                "> {%",
                "  client.log('###')",
                "%}",
                "<> 2026-01-01.json",
                "# after",
            )

        assertEquals(
            listOf(
                LineKind.VARIABLE,
                LineKind.SEPARATOR,
                LineKind.TAG,
                LineKind.COMMENT,
                LineKind.SCRIPT,
                LineKind.REQUEST_LINE,
                LineKind.URL_CONTINUATION,
                LineKind.HEADER,
                LineKind.HEADER,
                LineKind.HEADER_CONTINUATION,
                LineKind.COMMENT,
                LineKind.OTHER,
                LineKind.BODY,
                LineKind.BODY_FILE,
                LineKind.BODY,
                LineKind.SCRIPT,
                LineKind.SCRIPT,
                LineKind.SCRIPT,
                LineKind.RESPONSE_REFERENCE,
                LineKind.COMMENT,
            ),
            file.lineKinds,
        )
    }
}
