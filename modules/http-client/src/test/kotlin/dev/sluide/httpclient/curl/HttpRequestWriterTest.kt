package dev.sluide.httpclient.curl

import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import dev.sluide.httpclient.parse.HttpVersion
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import java.util.Base64
import kotlin.test.assertEquals

/** [HttpRequestWriter]: the `.http` text of requests, which [HttpRequestReader] reads back. */
internal class HttpRequestWriterTest {
    // CurlRequest does not follow redirects by default, which is written as a tag the rows do not care about.
    private fun write(vararg requests: CurlRequest) =
        HttpRequestWriter.write(requests.map { it.copy(followRedirects = true) }) as WriteResult.Written

    private fun failure(vararg requests: CurlRequest) =
        (HttpRequestWriter.write(requests.toList()) as WriteResult.Failed).message

    private fun post(vararg pieces: DataPiece) =
        CurlRequest("https://x", "POST", body = RequestBody.Data(pieces.toList()))

    private fun text(vararg lines: String) = RequestBody.Data(lines.map { DataPiece.Text(it) })

    private fun body(vararg lines: String) = post(DataPiece.Text(lines.joinToString("\n")))

    @Test
    fun `writes no text for no request`() {
        assertEquals(WriteResult.Written("", emptyList()), HttpRequestWriter.write(emptyList()))
    }

    @Test
    fun `separates requests with ### lines, named ones with their name`() {
        val written =
            write(
                CurlRequest("a"),
                CurlRequest("b"),
                CurlRequest("c", name = "Third\none"),
                CurlRequest("d", method = "PUT"),
            )

        assertEquals("GET a\n\n###\nGET b\n\n### Third one\nGET c\n\n###\nPUT d\n", written.text)
        assertEquals("### Only\nGET a\n", write(CurlRequest("a", name = "Only")).text)
        assertEquals("GET a\n", write(CurlRequest("a")).text)
    }

    @Test
    fun `writes tags, request line, headers and body`() {
        val request =
            CurlRequest(
                url = "https://x/a",
                method = "POST",
                headers = listOf("A" to "1", "B" to "two words"),
                body = RequestBody.Data(listOf(DataPiece.Text("l1\nl2"))),
                version = HttpVersion.HTTP_2,
                followRedirects = false,
                timeout = Duration.ofSeconds(2),
                connectTimeout = Duration.ofMillis(1500),
            )

        assertEquals(
            "# @no-redirect\n# @timeout 2 s\n# @connection-timeout 1500 ms\nPOST https://x/a HTTP/2\nA: 1\nB: two words\n\nl1\nl2\n",
            (HttpRequestWriter.write(listOf(request)) as WriteResult.Written).text,
        )
        assertEquals(
            "GET x HTTP/1.1\n",
            write(CurlRequest("x", followRedirects = true, version = HttpVersion.HTTP_1_1)).text,
        )
    }

    @Test
    fun `insecure has no tag and is noted once`() {
        val insecure = CurlRequest("x", insecure = true)

        val written = write(insecure, insecure)

        assertEquals("GET x\n\n###\nGET x\n", written.text)
        assertEquals(1, written.notes.size)
        assertEquals(true, written.notes.single().startsWith("--insecure has no .http equivalent"))
        assertEquals(emptyList(), write(CurlRequest("x")).notes)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authorizations")
    fun `writes credentials as the client reads them`(
        credentials: Credentials,
        header: String,
    ) {
        val written = write(CurlRequest("x", headers = listOf("A" to "1"), credentials = credentials))

        assertEquals("GET x\nA: 1\nAuthorization: $header\n", written.text)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("multipartHeaders")
    fun `a multipart body gets its content type with a boundary`(
        headers: List<Pair<String, String>>,
        written: String,
    ) {
        val request =
            CurlRequest(
                "x",
                "POST",
                headers = headers,
                body = RequestBody.Multipart(listOf(FormPart("f", FormContent.Value("v")))),
            )

        assertEquals(
            "POST x\n$written\n--WebAppBoundary\nContent-Disposition: form-data; name=\"f\"\n\nv\n--WebAppBoundary--\n",
            write(request).text,
        )
    }

    @Test
    fun `writes the parts of a multipart body`() {
        val request =
            CurlRequest(
                "x",
                "POST",
                body =
                    RequestBody.Multipart(
                        listOf(
                            FormPart("a", FormContent.Value("l1\nl2"), "text/plain"),
                            FormPart("b", FormContent.FileText("f.txt")),
                            FormPart("c", FormContent.File("/d/f.bin"), "image/png"),
                            FormPart("d", FormContent.File("d/f.bin", "other.bin")),
                        ),
                    ),
            )

        assertEquals(
            listOf(
                "POST x",
                "Content-Type: multipart/form-data; boundary=WebAppBoundary",
                "",
                "--WebAppBoundary",
                "Content-Disposition: form-data; name=\"a\"",
                "Content-Type: text/plain",
                "",
                "l1",
                "l2",
                "--WebAppBoundary",
                "Content-Disposition: form-data; name=\"b\"",
                "",
                "< ./f.txt",
                "--WebAppBoundary",
                "Content-Disposition: form-data; name=\"c\"; filename=\"f.bin\"",
                "Content-Type: image/png",
                "",
                "< /d/f.bin",
                "--WebAppBoundary",
                "Content-Disposition: form-data; name=\"d\"; filename=\"other.bin\"",
                "",
                "< ./d/f.bin",
                "--WebAppBoundary--",
                "",
            ).joinToString("\n"),
            write(request).text,
        )
    }

    @Test
    fun `the file name of a path is what follows its last separator`() {
        assertEquals("f.txt", HttpRequestWriter.fileName("a/b/f.txt"))
        assertEquals("f.txt", HttpRequestWriter.fileName("a\\b\\f.txt"))
        assertEquals("f.txt", HttpRequestWriter.fileName("f.txt"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("filePaths")
    fun `writes a file as a line relative to the file where it is`(
        path: String,
        line: String,
    ) {
        assertEquals("POST https://x\n\n$line\n", write(post(DataPiece.File(path))).text)
    }

    @Test
    fun `data pieces with files, form joins and notes`() {
        val form = listOf("Content-Type" to "$FORM_TYPE ; charset=utf-8")
        val files =
            CurlRequest(
                "x",
                "POST",
                headers = form,
                body =
                    RequestBody.Data(
                        listOf(
                            DataPiece.Text("a=1"),
                            DataPiece.File("f.txt", binary = false),
                            DataPiece.File("g.txt"),
                            DataPiece.Text("b=2"),
                        ),
                    ),
            )

        val written = write(files)

        // A form's text pieces end in &, which the client joins; two files in a row get an & line between.
        assertEquals(
            "POST x\nContent-Type: $FORM_TYPE ; charset=utf-8\n\na=1&\n< ./f.txt\n&\n< ./g.txt\nb=2\n",
            written.text,
        )
        assertEquals(listOf("-d @f.txt sends the file without line breaks; .http sends it as it is"), written.notes)
    }

    @Test
    fun `a binary file and a single piece give no note, a file after text no & line`() {
        val written = write(post(DataPiece.File("f.bin")), post(DataPiece.Text("a"), DataPiece.File("f")))

        assertEquals("POST https://x\n\n< ./f.bin\n\n###\nPOST https://x\n\na\n< ./f\n", written.text)
        assertEquals(
            listOf("curl joins its data with &; the .http body joins its parts with line breaks"),
            written.notes,
        )
    }

    @Test
    fun `two files in a row of a body that is no form get no & line`() {
        assertEquals(
            "POST https://x\n\n< ./a\n< ./b\n",
            write(post(DataPiece.File("a"), DataPiece.File("b"))).text,
        )
    }

    @Test
    fun `pieces of a body that is no form are joined with line breaks, noted for several`() {
        val one = write(post(DataPiece.Text("a"), DataPiece.Text("b")))
        val none = write(post(DataPiece.Text("a")))

        assertEquals("POST https://x\n\na\nb\n", one.text)
        assertEquals(listOf("curl joins its data with &; the .http body joins its parts with line breaks"), one.notes)
        assertEquals(emptyList(), none.notes)
    }

    @Test
    fun `a form body of two pieces needs no note`() {
        val request =
            CurlRequest(
                "x",
                "POST",
                headers = listOf("Content-Type" to "application/x-www-form-urlencoded; charset=utf-8"),
                body = text("a=1", "b=2\nc=3"),
            )

        val written = write(request)

        assertEquals(
            "POST x\nContent-Type: application/x-www-form-urlencoded; charset=utf-8\n\na=1&\nb=2\nc=3\n",
            written.text,
        )
        assertEquals(emptyList(), written.notes)
    }

    @Test
    fun `a script start with its end on the line is text`() {
        // As the parser: a script that closes on its line does not open one (< {%} does, see the syntax rows).
        assertEquals("POST https://x\n\n<  {% x %}\n", write(body("<  {% x %}")).text)
    }

    @Test
    fun `counts the lines of earlier pieces and parts`() {
        assertEquals(
            "Line 4 of the body starts with '###', which a .http file reads as the start of another request",
            failure(post(DataPiece.Text("a\nb"), DataPiece.File("f"), DataPiece.Text("###"))),
        )
        val multipart =
            CurlRequest(
                "x",
                "POST",
                body =
                    RequestBody.Multipart(
                        listOf(
                            FormPart("a", FormContent.Value("1\n2")),
                            FormPart("b", FormContent.Value("ok"), "text/plain"),
                            FormPart("c", FormContent.Value("<>x")),
                        ),
                    ),
            )
        // 1: --b, 2: disposition, 3: blank, 4-5 value; --b, disposition, type, blank, ok (6-10); --b, disposition, blank (11-13).
        assertEquals(
            "Line 14 of the body starts with '<>', which a .http file reads as a reference to a previous response",
            failure(multipart),
        )
    }

    @Test
    fun `what is written reads back as the same requests`() {
        val requests =
            listOf(
                CurlRequest("https://x/a?b=1", name = "First", followRedirects = true),
                CurlRequest(
                    "https://x/b",
                    "POST",
                    headers = listOf("Accept" to "a"),
                    body = RequestBody.Data(listOf(DataPiece.Text("l1\nl2"))),
                    credentials = Credentials("u", "p:w"),
                    timeout = Duration.ofSeconds(3),
                    version = HttpVersion.HTTP_1_1,
                ),
                CurlRequest(
                    "https://x/c",
                    "PUT",
                    followRedirects = true,
                    body =
                        RequestBody.Multipart(
                            listOf(
                                FormPart("a", FormContent.Value("v"), "text/plain"),
                                FormPart("f", FormContent.File("d/f.txt", "o.txt")),
                            ),
                        ),
                    credentials = Credentials("u", "", digest = true),
                    insecure = false,
                    connectTimeout = Duration.ofMillis(250),
                ),
            )

        val written = (HttpRequestWriter.write(requests) as WriteResult.Written).text

        assertEquals(requests, (HttpRequestReader.read(written) as ReadResult.Read).content)
    }

    companion object {
        @JvmStatic
        fun authorizations() =
            listOf(
                Arguments.of(Credentials("user", "pass"), "Basic user pass"),
                Arguments.of(Credentials("user", "pa ss"), "Basic user pa ss"),
                Arguments.of(Credentials("user", ""), "Basic user:"),
                Arguments.of(Credentials("user", "pass", digest = true), "Digest user pass"),
                Arguments.of(Credentials("user", "", digest = true), "Digest user:"),
                Arguments.of(
                    Credentials("us er", "pass"),
                    "Basic " + Base64.getEncoder().encodeToString("us er:pass".toByteArray()),
                ),
                Arguments.of(
                    Credentials("us\ter", "", digest = true),
                    "Digest " + Base64.getEncoder().encodeToString("us\ter:".toByteArray()),
                ),
            )

        @JvmStatic
        fun multipartHeaders() =
            listOf(
                Arguments.of(
                    emptyList<Pair<String, String>>(),
                    "Content-Type: multipart/form-data; boundary=WebAppBoundary\n",
                ),
                Arguments.of(
                    listOf("Content-Type" to "multipart/form-data; boundary=b"),
                    "Content-Type: multipart/form-data; boundary=b\n",
                ),
                Arguments.of(
                    listOf("X" to "1", "content-type" to "multipart/mixed"),
                    "X: 1\nContent-Type: multipart/mixed; boundary=WebAppBoundary\n",
                ),
            )

        @JvmStatic
        fun filePaths() =
            listOf(
                Arguments.of("f.txt", "< ./f.txt"),
                Arguments.of("d/f.txt", "< ./d/f.txt"),
                Arguments.of(".hidden", "< ./.hidden"),
                Arguments.of("/abs/f.txt", "< /abs/f.txt"),
                Arguments.of("./f.txt", "< ./f.txt"),
                Arguments.of("../f.txt", "< ../f.txt"),
                Arguments.of("{{dir}}/f.txt", "< {{dir}}/f.txt"),
                Arguments.of("C:\\d\\f.txt", "< C:\\d\\f.txt"),
                Arguments.of("c:/d/f.txt", "< c:/d/f.txt"),
                Arguments.of("cd:/f.txt", "< ./cd:/f.txt"),
                Arguments.of("C:f.txt", "< ./C:f.txt"),
            )
    }
}
