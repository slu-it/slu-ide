package dev.sluide.httpclient.send

import dev.sluide.httpclient.auth.DigestAuth
import dev.sluide.httpclient.parse.HttpFileParser
import dev.sluide.httpclient.parse.HttpVersion
import dev.sluide.httpclient.vars.DynamicVariables
import dev.sluide.httpclient.vars.UnresolvedVariablesException
import dev.sluide.httpclient.vars.VariableResolver
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequestBuilderTest {
    @TempDir
    lateinit var folder: Path

    private fun build(vararg lines: String): PreparedRequest {
        val file = HttpFileParser.parse(lines.joinToString("\n"))
        val resolver = VariableResolver(null, file.variables, DynamicVariables(env = { null }))
        return RequestBuilder(folder.resolve("api/requests.http"), resolver).build(file.requests.single())
    }

    private fun write(
        relative: String,
        text: String,
    ) {
        folder.resolve(relative).also { it.parent.createDirectories() }.writeText(text)
    }

    @Test
    fun `builds a request with resolved variables and default headers`() {
        val request =
            build(
                "@host = example.com",
                "@id = 7",
                "PUT https://{{host}}/items/{{id}} HTTP/2",
                "Content-Type: application/json",
                "X-Id: {{id}}",
                "",
                "{\"id\": {{id}}, \"name\": \"ä\"}",
            )

        assertEquals(URI("https://example.com/items/7"), request.uri)
        assertEquals("PUT", request.method)
        assertEquals(HttpVersion.HTTP_2, request.version)
        assertEquals(
            listOf(
                "Content-Type" to "application/json",
                "X-Id" to "7",
                "User-Agent" to "Thoreau",
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate",
            ),
            request.headers,
        )
        assertEquals("{\"id\": 7, \"name\": \"ä\"}", String(request.body, Charsets.UTF_8))
        assertTrue(request.notes.isEmpty())
    }

    @Test
    fun `defaults to HTTP 1_1 and keeps written default headers`() {
        val request = build("GET http://x", "accept: text/html", "User-Agent: me")

        assertEquals(HttpVersion.HTTP_1_1, request.version)
        assertEquals(
            listOf("accept" to "text/html", "User-Agent" to "me", "Accept-Encoding" to "gzip, deflate"),
            request.headers,
        )
        assertEquals(0, request.body.size)
    }

    @Test
    fun `drops headers the client sets itself and keeps Host`() {
        val request =
            build(
                "GET http://x",
                "Content-Length: 5",
                "Connection: close",
                "Host: y",
                "Expect: 100",
                "Upgrade: h2c",
                "Transfer-Encoding: chunked",
                "TE: trailers",
                "Trailer: X",
                "Keep-Alive: 5",
                "Proxy-Connection: close",
            )

        assertEquals("y", request.header("host"))
        assertEquals(null, request.header("Connection"))
        assertEquals(null, request.header("TE"))
        assertEquals(9, request.notes.size)
    }

    @Test
    fun `encodes Basic credentials`() {
        assertEquals(
            "Basic dXNlcjpwYXNz",
            build("GET http://x", "Authorization: Basic user pass").header("Authorization"),
        )
        assertEquals(
            "Basic dXNlcjpwYXNz",
            build("@u = user", "GET http://x", "Authorization: Basic {{u}}:pass").header("Authorization"),
        )
    }

    @Test
    fun `lists all unresolved variables of a request`() {
        val error =
            assertFailsWith<UnresolvedVariablesException> {
                build("POST http://{{host}}/", "X-A: {{a}}", "", "{{b}} {{host}}", "< {{dir}}/file.json")
            }

        assertEquals(listOf("host", "a", "b", "dir"), error.names)
    }

    @Test
    fun `reads text body files relative to the http file and replaces their variables`() {
        write("api/body.json", "{\"id\": {{id}}}")

        val request = build("@id = 3", "POST http://x", "", "< ./body.json")

        assertEquals("{\"id\": 3}", String(request.body, Charsets.UTF_8))
        assertEquals(emptyList(), request.notes)
    }

    @Test
    fun `sends unresolved variables of body files as written with a note`() {
        write("api/template.txt", "{{id}} {{ missing }} {{\$nope}}")

        val request = build("@id = 3", "POST http://x", "", "<@ template.txt")

        assertEquals("3 {{ missing }} {{\$nope}}", String(request.body, Charsets.UTF_8))
        assertEquals(listOf("Unresolved variables in template.txt: missing, \$nope; sent as written"), request.notes)
    }

    @Test
    fun `reports body files that cannot be read`() {
        write("api/secret.json", "{}")
        val file = folder.resolve("api/secret.json")
        file.toFile().setReadable(false)
        assumeFalse(Files.isReadable(file), "the file stays readable (e.g. for root)")

        val error = assertFailsWith<RequestBuildException> { build("POST http://x", "", "< secret.json") }

        val message = error.message.orEmpty()
        assertTrue(message.startsWith("Body file secret.json cannot be read (line 3): "), message)
        assertTrue(message.endsWith("secret.json"), message)
    }

    @Test
    fun `sends binary body files as they are`() {
        val bytes = byteArrayOf(0, 1, 2, -1, -2, '{'.code.toByte(), '{'.code.toByte())
        folder.resolve("api").createDirectories()
        folder.resolve("api/image.bin").writeBytes(bytes)

        assertContentEquals(bytes, build("POST http://x", "", "< image.bin").body)
    }

    @Test
    fun `joins inline text and files with line breaks`() {
        write("data/part.txt", "file")

        assertEquals(
            "before\nfile\nafter",
            String(build("POST http://x", "", "before", "< ../data/part.txt", "after").body),
        )
    }

    @Test
    fun `reports missing body files`() {
        val error = assertFailsWith<RequestBuildException> { build("POST http://x", "", "< missing.json") }

        assertEquals("Body file not found: missing.json (line 3)", error.message)
    }

    @Test
    fun `lays out multipart bodies with CRLF and inserts files unchanged`() {
        write("api/a.txt", "line 1\nline 2 {{x}}")

        val request =
            build(
                "@name = n",
                "@x = 9",
                "POST http://x",
                "Content-Type: multipart/form-data; boundary=WebAppBoundary",
                "",
                "--WebAppBoundary",
                "Content-Disposition: form-data; name=\"{{name}}\"",
                "",
                "value",
                "--WebAppBoundary",
                "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"",
                "Content-Type: text/plain",
                "",
                "< ./a.txt",
                "--WebAppBoundary--",
            )

        assertEquals(
            "--WebAppBoundary\r\n" +
                "Content-Disposition: form-data; name=\"n\"\r\n" +
                "\r\n" +
                "value\r\n" +
                "--WebAppBoundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "line 1\nline 2 {{x}}\r\n" +
                "--WebAppBoundary--",
            String(request.body, Charsets.UTF_8),
        )
    }

    @Test
    fun `joins url-encoded form lines and sends values as written`() {
        val request =
            build(
                "@user = a b",
                "POST http://x",
                "Content-Type: application/x-www-form-urlencoded",
                "",
                "name={{user}}",
                "  &city=Berlin&",
                "zip=10115",
            )

        assertEquals("name=a b&city=Berlin&zip=10115", String(request.body))
    }

    @Test
    fun `notes scripts that are not run`() {
        val request = build("< {% request.variables.set('a', 1) %}", "GET http://x", "", "> {% client.log(1) %}")

        assertEquals(listOf("Scripts are not supported; 2 script(s) not run"), request.notes)
    }

    @Test
    fun `refuses unsupported request types`() {
        val error = assertFailsWith<RequestBuildException> { build("GRAPHQL http://x") }

        assertEquals("GRAPHQL requests are not supported", error.message)
    }

    @Test
    fun `takes Digest credentials out of the headers`() {
        val request = build("@pass = secret", "GET http://x", "Authorization: Digest user {{pass}}")

        assertEquals(null, request.header("Authorization"))
        assertEquals(DigestAuth.Credentials("user", "secret"), request.digest)
        assertEquals(null, build("GET http://x", "Authorization: Basic a b").digest)
    }

    @Test
    fun `sends a complete Digest answer as written`() {
        val answer = "Digest username=\"u\", realm=\"r\", nonce=\"n\", uri=\"/\", response=\"abc\""

        val request = build("GET http://x", "Authorization: $answer")

        assertEquals(answer, request.header("Authorization"))
        assertEquals(null, request.digest)
    }

    @Test
    fun `resolves variables in header names and values`() {
        val request = build("@n = X-Id", "@v = 7", "GET http://x", "{{n}}: {{v}}")

        assertEquals("7", request.header("X-Id"))
    }

    @Test
    fun `names the unresolved variables of the part that has them, wherever it is`() {
        fun names(vararg lines: String) = assertFailsWith<UnresolvedVariablesException> { build(*lines) }.names

        assertEquals(listOf("u"), names("GET {{u}}"))
        assertEquals(listOf("v"), names("GET http://x", "X-A: {{v}}"))
        assertEquals(listOf("n"), names("GET http://x", "{{n}}: a"))
        assertEquals(listOf("b"), names("POST http://x", "", "{{b}}"))
        assertEquals(
            listOf("m"),
            names("POST http://x", "Content-Type: multipart/form-data; boundary=B", "", "--B", "{{m}}", "--B--"),
        )
    }

    @Test
    fun `uses the last Host header, in any case, for a URL that starts with a slash`() {
        assertEquals(URI("http://b.example/p"), build("GET /p", "X-Other: o", "hOsT: a.example", "HOST: b.example").uri)
        assertEquals(URI("http://a.example/p"), build("GET /p", "X-Other: o", "host: a.example").uri)
    }

    @Test
    fun `takes Digest credentials from any header named Authorization and from no other`() {
        assertEquals(
            DigestAuth.Credentials("user", "pw"),
            build("GET http://x", "X-A: 1", "authorization: Digest user pw").digest,
        )
        val other = build("GET http://x", "X-Auth: Digest user pw")
        assertEquals(null, other.digest)
        assertEquals("Digest user pw", other.header("X-Auth"))
    }

    @Test
    fun `encodes Basic credentials of an Authorization header in any case, and of no other header`() {
        val request = build("GET http://x", "authorization: Basic user pass", "X-Auth: Basic user pass")

        assertEquals("Basic dXNlcjpwYXNz", request.header("authorization"))
        assertEquals("Basic user pass", request.header("X-Auth"))
    }

    @Test
    fun `sends a body file that has no text as it is, with its variables`() {
        folder.resolve("api").createDirectories()
        val bytes = "a{{id}}".toByteArray() + byteArrayOf(0)
        folder.resolve("api/nul.bin").writeBytes(bytes)

        val request = build("@id = 3", "POST http://x", "", "< nul.bin")

        assertContentEquals(bytes, request.body)
        assertEquals(emptyList(), request.notes)
    }

    @Test
    fun `reports a folder as a missing body file`() {
        folder.resolve("api/dir").createDirectories()

        val error = assertFailsWith<RequestBuildException> { build("POST http://x", "", "< dir") }

        assertEquals("Body file not found: dir (line 3)", error.message)
    }

    @Test
    fun `leaves the lines of a url-encoded form alone except where an ampersand joins them`() {
        fun form(vararg lines: String) =
            String(
                build("POST http://x", "Content-Type: application/x-www-form-urlencoded", "", *lines).body,
                Charsets.UTF_8,
            )

        assertEquals("a=1", form("  a=1  "))
        assertEquals("a=1\nb=2", form("a=1", "b=2"))
        assertEquals("a=1\nb=2  ", form("a=1", "b=2  "))
        assertEquals("a=1&b=2", form("a=1", "  &b=2"))
        assertEquals("a=1&b=2", form("a=1&", "  b=2  "))
        assertEquals("a=1\n\nb=2", form("a=1", "", "b=2"))
        assertEquals("a=1&&b=2", form("a=1&", "&b=2"))
        assertEquals("a=1&b=2\nc=3", form("a=1", "&b=2", "c=3"))
        assertEquals("a=1\nx&y", form("a=1", "x&y"))
    }
}
