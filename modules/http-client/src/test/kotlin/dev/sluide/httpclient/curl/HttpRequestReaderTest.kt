package dev.sluide.httpclient.curl

import dev.sluide.converting.ReadResult
import dev.sluide.httpclient.parse.BodyPart
import dev.sluide.httpclient.parse.HttpFileParser
import dev.sluide.httpclient.parse.HttpVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [HttpRequestReader]: what a `.http` text becomes as [CurlRequest]s and the notes for what curl has no place for. */
internal class HttpRequestReaderTest {
    private fun read(vararg lines: String) = HttpRequestReader.read(lines.joinToString("\n")) as ReadResult.Read

    private fun request(vararg lines: String) = read(*lines).content.single()

    private fun data(vararg pieces: DataPiece) = RequestBody.Data(pieces.toList())

    @ParameterizedTest(name = "{1}: {0}")
    @MethodSource("detections")
    fun `detects requests by a method or an http URL on the first request line`(
        text: String,
        expected: Boolean,
    ) {
        assertEquals(expected, HttpRequestReader.detects(text), text)
    }

    @Test
    fun `a parse problem fails at the start of its line`() {
        // The problem is on the fifth line, which starts at 33.
        assertEquals(
            ReadResult.Failed("Expected a header (Name: value) or a blank line before the body", 33),
            HttpRequestReader.read("GET https://x\n\n###\nGET https://y\nnot a header\n"),
        )
    }

    @Test
    fun `finds where a line starts, a line after the last one at the last line`() {
        val text = "a\nbc\n\nd"

        assertEquals(listOf(0, 2, 5, 6), (0..3).map { HttpRequestReader.lineOffset(text, it) })
        // Behind the last line break the offset stays at the last line's start.
        assertEquals(6, HttpRequestReader.lineOffset(text, 4))
        assertEquals(6, HttpRequestReader.lineOffset(text, 9))
        assertEquals(1, HttpRequestReader.lineOffset("\nabc", 1))
        assertEquals(0, HttpRequestReader.lineOffset("", 3))
    }

    @Test
    fun `notes what curl has no place for with its count`() {
        val result =
            read(
                "@a = 1",
                "@b = 2",
                "< {% request.variables.set('a', 1) %}",
                "GET https://x",
                "> {% client.log(1) %}",
                "<> 1.json",
                "",
                "###",
                "< {% a() %}",
                "< ./pre.js",
                "GET https://y",
                "> {% b() %}",
                "> ./h.js",
                "<> 2.json",
                "<> 3.json",
            )

        assertEquals(
            listOf(
                "2 in-file variables dropped: a, b; their {{…}} stay as written",
                "3 pre-request scripts dropped",
                "3 response handlers dropped",
                "3 response references dropped",
            ),
            result.notes,
        )
    }

    @Test
    fun `notes a single script, handler, reference and variable in the singular`() {
        assertEquals(
            listOf(
                "1 in-file variable dropped: a; their {{…}} stay as written",
                "1 pre-request script dropped",
                "Response handler dropped",
                "1 response reference dropped",
            ),
            read("@a = 1", "< {% x() %}", "GET https://x", "> {% y() %}", "<> 1.json").notes,
        )
    }

    @Test
    fun `notes nothing for a plain request`() {
        assertEquals(emptyList(), read("GET https://x", "Accept: a").notes)
    }

    @Test
    fun `notes only the kinds a file has`() {
        assertEquals(listOf("1 pre-request script dropped"), read("< {% x() %}", "GET https://x").notes)
        assertEquals(listOf("Response handler dropped"), read("GET https://x", "> {% y() %}").notes)
        assertEquals(listOf("2 response references dropped"), read("GET https://x", "<> a.json", "<> b.json").notes)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authorizations")
    fun `reads credentials from a Basic or Digest header the client understands`(
        header: String,
        credentials: Credentials?,
    ) {
        val request = request("GET https://x", "Accept: a", header, "X: 1")

        assertEquals(credentials, request.credentials)
        val own = header.substringBefore(": ") to header.substringAfter(": ").trim()
        val expected =
            if (credentials ==
                null
            ) {
                listOf("Accept" to "a", own, "X" to "1")
            } else {
                listOf("Accept" to "a", "X" to "1")
            }
        assertEquals(expected, request.headers)
    }

    @Test
    fun `the last credentials header wins and other Authorization headers stay`() {
        val request =
            request("GET https://x", "Authorization: Basic a b", "Authorization: Bearer t", "authorization: Digest c d")

        assertEquals(Credentials("c", "d", digest = true), request.credentials)
        assertEquals(listOf("Authorization" to "Bearer t"), request.headers)
    }

    @Test
    fun `a Basic header of another name is no credentials`() {
        val request = request("GET https://x", "X-Authorization: Basic a b")

        assertEquals(null, request.credentials)
        assertEquals(listOf("X-Authorization" to "Basic a b"), request.headers)
    }

    @Test
    fun `reads the request line, tags, version and name`() {
        val request =
            request(
                "### Create it",
                "# @no-redirect",
                "# @timeout 2 s",
                "# @connection-timeout 500 ms",
                "DELETE https://x/a HTTP/2",
                "X: 1",
            )

        assertEquals(
            CurlRequest(
                url = "https://x/a",
                method = "DELETE",
                headers = listOf("X" to "1"),
                version = HttpVersion.HTTP_2,
                followRedirects = false,
                timeout = Duration.ofSeconds(2),
                connectTimeout = Duration.ofMillis(500),
                name = "Create it",
            ),
            request,
        )
        assertEquals(true, request("GET https://x").followRedirects)
        assertEquals(null, request("GET https://x").connectTimeout)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forms")
    fun `joins the lines of a form body as the client does`(
        name: String,
        body: List<String>,
        pieces: List<DataPiece>,
    ) {
        val request =
            request(
                "POST https://x",
                "Content-Type: Application/X-WWW-Form-Urlencoded ; charset=utf-8",
                "",
                *body.toTypedArray(),
            )

        assertEquals(RequestBody.Data(pieces), request.body, name)
    }

    @Test
    fun `a body of another type stays as written and several parts are noted`() {
        val one = read("POST https://x", "Content-Type: application/json", "", "a=1&", "b=2", "&c")
        val two = read("POST https://x", "Content-Type: application/json", "", "a=1&", "< ./f.txt", "&c")

        assertEquals(data(DataPiece.Text("a=1&\nb=2\n&c")), one.content.single().body)
        assertEquals(emptyList(), one.notes)
        assertEquals(
            data(DataPiece.Text("a=1&"), DataPiece.File("f.txt"), DataPiece.Text("&c")),
            two.content.single().body,
        )
        assertEquals(listOf("curl joins its data with &; the .http body joins its parts with line breaks"), two.notes)
        assertEquals(RequestBody.None, request("POST https://x").body)
    }

    @Test
    fun `a multipart body of fields becomes -F fields without its content type`() {
        val request =
            request(
                "POST https://x",
                "X: 1",
                "Content-Type: Multipart/Form-Data ; boundary=b",
                "",
                "--b",
                "Content-Disposition: form-data; name=\"f\"",
                "",
                "v",
                "--b",
                "Content-Disposition: form-data; name=\"g\"; filename=\"n.txt\"",
                "Content-Type: text/x-y",
                "",
                "< ./d/f.txt",
                "--b--",
            )

        assertEquals(
            RequestBody.Multipart(
                listOf(
                    FormPart("f", FormContent.Value("v")),
                    FormPart("g", FormContent.File("d/f.txt", "n.txt"), "text/x-y"),
                ),
            ),
            request.body,
        )
        assertEquals(listOf("X" to "1"), request.headers)
    }

    @Test
    fun `a multipart body that is no list of fields keeps its content type and is noted`() {
        val result = read("POST https://x", "Content-Type: multipart/form-data; boundary=b", "", "free text")
        val request = result.content.single()

        assertEquals(listOf("Content-Type" to "multipart/form-data; boundary=b"), request.headers)
        assertEquals(data(DataPiece.Text("free text")), request.body)
        assertEquals(listOf("The multipart body cannot be written as -F fields; sent as raw data"), result.notes)
    }

    @Test
    fun `only a multipart content type makes the body fields`() {
        val request =
            request(
                "POST https://x",
                "Content-Type: application/json; boundary=b",
                "",
                "--b",
                "Content-Disposition: form-data; name=\"f\"",
                "",
                "v",
                "--b--",
            )

        assertEquals(
            data(DataPiece.Text("--b\nContent-Disposition: form-data; name=\"f\"\n\nv\n--b--")),
            request.body,
        )
    }

    @Test
    fun `a form body that resolved to nothing stays an empty piece`() {
        // CurlCopy resolves {{variables}} before it reads a block, so a body can be empty text.
        val block =
            HttpFileParser
                .parse("POST https://x\nContent-Type: $FORM_TYPE\n\n{{a}}")
                .requests
                .single()
                .let { it.copy(body = listOf(BodyPart.Text(""))) }

        assertEquals(data(DataPiece.Text("")), HttpRequestReader.request(block, mutableListOf()).body)
    }

    @Test
    fun `a form content type without a body is no body`() {
        assertEquals(RequestBody.None, request("POST https://x", "Content-Type: $FORM_TYPE").body)
        assertTrue(request("POST https://x", "Content-Type: multipart/form-data; boundary=b").headers.isNotEmpty())
    }

    companion object {
        @JvmStatic
        fun detections() =
            listOf(
                Arguments.of("GET https://x", true),
                Arguments.of("# name\n\nGET https://x\n", true),
                Arguments.of("### name\nPOST /path\n", true),
                Arguments.of("https://x/a", true),
                Arguments.of("GET {{host}}/a", true),
                Arguments.of("{{host}}/a", false),
                Arguments.of("", false),
                Arguments.of("@a = 1", false),
                Arguments.of("# only a comment\n", false),
                Arguments.of("GET https://x\nnot a header\n", false),
            )

        @JvmStatic
        fun authorizations() =
            listOf(
                Arguments.of("Authorization: Basic user pass", Credentials("user", "pass")),
                Arguments.of("authorization:  basic   user   pa ss ", Credentials("user", "pa ss")),
                Arguments.of("Authorization: Basic user:pass", Credentials("user", "pass")),
                Arguments.of("Authorization: Basic user:", Credentials("user", "")),
                Arguments.of("Authorization: Basic a:b c", Credentials("a:b", "c")),
                Arguments.of("Authorization: Digest user pass", Credentials("user", "pass", digest = true)),
                Arguments.of("Authorization: Digest user:pass", Credentials("user", "pass", digest = true)),
                // An encoded value is a header like any other: it has neither a space nor a colon.
                Arguments.of("Authorization: Basic dXNlcjpwYXNz", null),
                Arguments.of("Authorization: Bearer token", null),
            )

        @JvmStatic
        fun forms() =
            listOf(
                Arguments.of("an & at the line end", listOf("a=1&", "b=2"), listOf(DataPiece.Text("a=1&b=2"))),
                Arguments.of("an & at the line start", listOf("a=1", "&b=2"), listOf(DataPiece.Text("a=1&b=2"))),
                Arguments.of("no &", listOf("a=1", "b=2"), listOf(DataPiece.Text("a=1\nb=2"))),
                Arguments.of(
                    "an indented line without &",
                    listOf("a=1", "  b=2 "),
                    listOf(DataPiece.Text("a=1\n  b=2 ")),
                ),
                Arguments.of(
                    "blanks around joined lines go",
                    listOf("  a=1  ", "  &  b=2 "),
                    listOf(DataPiece.Text("a=1&  b=2")),
                ),
                Arguments.of(
                    "curl adds the & between pieces",
                    listOf("a=1&", "< ./f.txt", "&b=2"),
                    listOf(DataPiece.Text("a=1"), DataPiece.File("f.txt"), DataPiece.Text("b=2")),
                ),
                Arguments.of(
                    "the first piece keeps a leading & and the last a trailing one",
                    listOf("&a=1&", "< ./f.txt", "&b=2&"),
                    listOf(DataPiece.Text("&a=1"), DataPiece.File("f.txt"), DataPiece.Text("b=2&")),
                ),
                Arguments.of("a lone & stays", listOf("&"), listOf(DataPiece.Text("&"))),
                Arguments.of(
                    "a piece that only joined to the next is dropped",
                    listOf("&", "< ./f.txt"),
                    listOf(DataPiece.File("f.txt")),
                ),
            )
    }
}
