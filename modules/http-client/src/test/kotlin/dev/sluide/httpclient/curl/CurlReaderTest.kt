package dev.sluide.httpclient.curl

import dev.sluide.converting.ReadResult
import dev.sluide.httpclient.parse.HttpVersion
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What [CurlReader] makes of the options of a command. The semantics are those of curl 8.14 (`man curl`), checked
 * against the real tool with a local server for: several `-d` joined with `&`, `--json` appended without separator
 * also after `-d`, `-G` appending to an existing query with `&`, `-H 'X;'` sending an empty header, `-H 'X:'` removing
 * one, `-m` refusing a negative number and an empty file name after `@` failing. `-b` given twice is joined with `; `
 * (RFC 6265 5.4), where curl sends `;`: no server-visible difference.
 */
class CurlReaderTest {
    private fun read(curl: String) = CurlReader.read(curl) as ReadResult.Read

    private fun request(curl: String) = read(curl).content.single()

    private fun notes(curl: String) = read(curl).notes

    private fun data(vararg pieces: DataPiece) = RequestBody.Data(pieces.toList())

    @Test
    fun `cuts timeouts to whole milliseconds and reads below one millisecond as none`() {
        // curl 8.14.1 against a local server: -m 0 and --connect-timeout 0 mean no timeout, -m 0.0001 still gets
        // the 0.3 s answer (0 ms, no timeout) and -m 0.001 times out (exit 28).
        for (value in listOf("0", "0.0001", "0.0009")) {
            assertNull(request("curl x -m $value").timeout, value)
            assertNull(request("curl x --connect-timeout $value").connectTimeout, value)
        }
        assertEquals(Duration.ofMillis(1), request("curl x -m 0.001").timeout)
        assertEquals(Duration.ofMillis(1500), request("curl x -m 1.5").timeout)
        assertEquals(Duration.ofMillis(1999), request("curl x --connect-timeout 1.9999").connectTimeout)
        // The last one wins, also a 0 after a number.
        assertNull(request("curl x -m 5 -m 0").timeout)
    }

    @Test
    fun `accepts the longest timeout`() {
        assertEquals(Duration.ofSeconds(1_000_000_000), request("curl x -m 1000000000").timeout)
    }

    @Test
    fun `maps the long names of the less common options`() {
        assertEquals(true, request("curl --location-trusted x").followRedirects)
        assertEquals(false, request("curl -L --no-location x").followRedirects)
        assertEquals(HttpVersion.HTTP_2, request("curl --http2-prior-knowledge x").version)
        assertEquals(Credentials("a", "b", false), request("curl --digest --basic -u a:b x").credentials)
        assertEquals(Credentials("a", "b", true), request("curl --basic --digest -u a:b x").credentials)
        assertEquals(data(DataPiece.Text("a")), request("curl --data-ascii a x").body)
        assertEquals(data(DataPiece.File("f", binary = false)), request("curl --data-ascii @f x").body)
    }

    @Test
    fun `reads the headers curl sends, removes or cannot read from a file`() {
        // The leading space of a header is trimmed on purpose: curl would send it as a folded continuation line.
        assertEquals(
            listOf("A" to "b;", "Empty" to "", "Spaced" to ""),
            request("curl -H 'A: b;' -H 'Empty;' -H ' Spaced ;' x").headers,
        )
        assertEquals(emptyList(), request("curl -H 'X-Gone:' -H 'X-Blank:   ' x").headers)
        assertEquals(
            listOf("Header X-Gone removed by curl; left out", "Header X-Blank removed by curl; left out"),
            notes("curl -H 'X-Gone:' -H ' X-Blank :   ' x"),
        )
        assertEquals(listOf("Ignored: -H @h.txt, -H novalue"), notes("curl -H @h.txt -H novalue x"))
        assertEquals(emptyList(), request("curl -H @h.txt -H novalue x").headers)
        // curl reads the headers of the file a:b here, so the colon does not make it a header.
        assertEquals(emptyList(), request("curl -H @a:b x").headers)
        assertEquals(listOf("Ignored: -H @a:b"), notes("curl -H @a:b x"))
    }

    @Test
    fun `joins cookies into one header and ignores a cookie file`() {
        // Joined with "; " (RFC 6265 5.4); curl sends ";" without a space, which no server tells apart. With the
        // cookie as the first header, which CurlMappingTest does not cover.
        assertEquals(listOf("Cookie" to "a=1; b=2"), request("curl -b a=1 -b b=2 x").headers)
        assertEquals(emptyList(), request("curl -b jar.txt x").headers)
        assertEquals(listOf("Cookie file jar.txt ignored"), notes("curl -b jar.txt x"))
    }

    @Test
    fun `reads data from files, from standard input and a lone at sign`() {
        assertEquals(data(DataPiece.Text("a=1")), request("curl -d a=1 -d @- x").body)
        assertEquals(
            listOf("Data from standard input dropped", "Data from standard input dropped"),
            notes("curl -d @- --data-binary @- x"),
        )
        assertEquals(RequestBody.None, request("curl -d @- x").body)
        assertEquals("GET", request("curl -d @- x").method)
        assertEquals(
            data(DataPiece.File("f", binary = false), DataPiece.Text("a&b")),
            request("curl -d @f -d a -d b x").body,
        )
    }

    @Test
    fun `reads --data-urlencode in each of its forms`() {
        // curl sends `a+b` for a space (also with -G); %20 is kept: in a form body both decode to a space.
        assertEquals(data(DataPiece.Text("a%20b")), request("curl --data-urlencode 'a b' x").body)
        assertEquals(data(DataPiece.Text("%40x")), request("curl --data-urlencode =@x x").body)
        assertEquals(data(DataPiece.File("a.txt")), request("curl --data-urlencode n@a.txt x").body)
        assertEquals(data(DataPiece.File("f=x")), request("curl --data-urlencode n@f=x x").body)
        assertEquals(
            listOf("--data-urlencode @f: the file is sent as it is, not URL-encoded"),
            notes("curl --data-urlencode @f x"),
        )
        assertEquals(
            listOf("--data-urlencode n@f=x: the file is sent as it is, without n=, not URL-encoded"),
            notes("curl --data-urlencode n@f=x x"),
        )
    }

    @Test
    fun `appends --json to the previous text without a separator`() {
        assertEquals(data(DataPiece.Text("ab")), request("curl --json a --json b x").body)
        assertEquals(data(DataPiece.Text("xy")), request("curl -d x --json y x").body)
        assertEquals(data(DataPiece.Text("x&y")), request("curl --json x -d y x").body)
        assertEquals(
            data(DataPiece.File("f"), DataPiece.Text("a")),
            request("curl --json @f --json a x").body,
        )
        assertEquals(
            data(DataPiece.Text("a"), DataPiece.File("f")),
            request("curl --json a --json @f x").body,
        )
        assertEquals(
            data(DataPiece.File("f", binary = false), DataPiece.Text("ab")),
            request("curl -d @f --json a --json b x").body,
        )
    }

    @Test
    fun `keeps the content type and accept header the user gave with --json`() {
        assertEquals(
            listOf("Content-Type" to "application/vnd.api+json", "accept" to "text/x"),
            request("curl -H 'Content-Type: application/vnd.api+json' -H 'accept: text/x' --json x u").headers,
        )
        assertEquals(
            listOf("Accept" to "text/x", "Content-Type" to "application/json"),
            request("curl -H 'Accept: text/x' --json x u").headers,
        )
    }

    @Test
    fun `puts the data of -G into the query and notes the files it cannot put there`() {
        assertEquals("https://x?a=1", request("curl -G -d a=1 https://x").url)
        assertEquals(RequestBody.None, request("curl -G -d a=1 https://x").body)
        assertEquals(emptyList(), notes("curl -G -d a=1 https://x"))
        val mixed = "curl -G -d @f -d a=1 -d @g https://x"
        assertEquals("https://x?a=1", request(mixed).url)
        assertEquals(listOf("-G: data files cannot go into the URL; dropped: f, g"), notes(mixed))
        assertEquals("https://x", request("curl -G -d @f https://x").url)
    }

    @Test
    fun `reads form fields with attributes`() {
        assertEquals(
            RequestBody.Multipart(listOf(FormPart("f", FormContent.Value("@x;type=y")))),
            request("curl --form-string 'f=@x;type=y' x").body,
        )
        assertEquals(
            RequestBody.Multipart(listOf(FormPart("n", FormContent.FileText("n.txt"), "text/plain"))),
            request("curl -F 'n=<n.txt;type=text/plain' x").body,
        )
        assertEquals(
            RequestBody.Multipart(listOf(FormPart("a", FormContent.Value("b"), "text/x"))),
            request("curl -F 'a=b;type=text/x' x").body,
        )
        assertEquals(
            RequestBody.Multipart(listOf(FormPart("a", FormContent.Value("b;c=d"), null))),
            request("curl -F 'a=b;c=d' x").body,
        )
    }

    @Test
    fun `reads user names with and without a password`() {
        assertEquals(Credentials("ada", "", false), request("curl -u ada x").credentials)
        assertEquals(
            listOf("-u ada has no password; curl asks for it, the request gets an empty one"),
            notes("curl -u ada x"),
        )
        assertEquals(emptyList(), notes("curl -u ada: x"))
        assertEquals(Credentials("ada", "p:q", false), request("curl -u ada:p:q x").credentials)
    }

    @Test
    fun `takes the first URL and notes the others, a lone dash is a URL`() {
        assertEquals("a", request("curl a b c").url)
        assertEquals(listOf("Further URLs ignored: b c"), notes("curl a b c"))
        assertEquals("-", request("curl -").url)
    }

    @Test
    fun `names the request after a comment unless it is blank`() {
        assertEquals("Users", request("# Users\ncurl x").name)
        assertNull(request("#   \ncurl x").name)
    }
}
