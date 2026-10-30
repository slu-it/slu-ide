package dev.sluide.httpclient.curl

import dev.sluide.converting.ConversionResult
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import dev.sluide.converting.convert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration
import kotlin.test.assertEquals

class CurlMappingTest {
    private val family = RequestFamily()

    private fun toHttp(curl: String) = family.convert(curl, CurlFormat, HttpRequestFormat, OptionValues())

    private fun httpText(curl: String) = (toHttp(curl) as ConversionResult.Converted).text

    private fun toCurl(
        http: String,
        shell: Shell = Shell.POSIX,
    ) = family.convert(http, HttpRequestFormat, CurlFormat, OptionValues().with("shell", shell.label))

    private fun request(curl: String) =
        ((family.read(curl, CurlFormat, OptionValues()) as ReadResult.Read).content.single())

    @ParameterizedTest(name = "{0}")
    @MethodSource("flags")
    fun `maps curl options to a request`(
        curl: String,
        http: String,
    ) {
        assertEquals(http.trimIndent() + "\n", httpText(curl))
    }

    @Test
    fun `maps timeouts, insecure and the version into the model`() {
        val request = request("curl -m 2.5 --connect-timeout 3 -k --http2 -L x")

        assertEquals(Duration.ofMillis(2500), request.timeout)
        assertEquals(Duration.ofSeconds(3), request.connectTimeout)
        assertEquals(true, request.insecure)
        assertEquals(true, request.followRedirects)
    }

    @Test
    fun `short options may be clustered and take attached values`() {
        val request = request("curl -sSLkXPUT -HAccept:json x")

        assertEquals("PUT", request.method)
        assertEquals(listOf("Accept" to "json"), request.headers)
        assertEquals(true, request.followRedirects && request.insecure)
    }

    @Test
    fun `notes ignored options, dropped output options and the text after a pipe`() {
        val result = toHttp("curl -s -o out.json -v --foo -Z -x proxy:1 https://x | jq .") as ConversionResult.Converted

        assertEquals(
            listOf("Ignored after the command: | jq .", "Ignored: --foo, -Z, -x"),
            result.notes,
        )
        assertEquals("# @no-redirect\nGET https://x\n", result.text)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bodySyntax")
    fun `body lines that a http file reads as syntax fail`(
        curl: String,
        message: String,
    ) {
        assertEquals(ConversionResult.WriteFailed(message), toHttp(curl))
    }

    @Test
    fun `a decimal timeout in exponent notation is read as seconds`() {
        assertEquals(Duration.ofSeconds(1000), request("curl -m 1e3 x").timeout)
    }

    @Test
    fun `a URL-encoded file keeps no name, which is noted`() {
        val result = toHttp("curl -L --data-urlencode name@a.txt https://x") as ConversionResult.Converted

        assertEquals(
            listOf("--data-urlencode name@a.txt: the file is sent as it is, without name=, not URL-encoded"),
            result.notes,
        )
    }

    @Test
    fun `quotes what POSIX shells and PowerShell would expand or end a quote at`() {
        listOf("=cmd", "~/x", "#x", "a b", "").forEach {
            assertEquals("'$it'", CurlWriter.quote(it, Shell.POSIX))
        }
        assertEquals("a=b", CurlWriter.quote("a=b", Shell.POSIX))
        // PowerShell ends single quotes at the typographic ones too.
        val header = "X: a’; Start-Process calc; ’"
        assertEquals("'X: a’’; Start-Process calc; ’’'", CurlWriter.quote(header, Shell.POWERSHELL))
        val curl =
            (
                CurlWriter.write(
                    listOf(CurlRequest("https://x", headers = listOf("X" to header.removePrefix("X: ")))),
                    Shell.POWERSHELL,
                ) as WriteResult.Written
            ).text
        assertEquals(listOf("X" to header.removePrefix("X: ")), request(curl).headers)
    }

    @Test
    fun `a field value that -F would read as a file or attributes is written with --form-string`() {
        val request =
            CurlRequest(
                "https://x",
                method = "POST",
                body = RequestBody.Multipart(listOf(FormPart("f", FormContent.Value("a;b"), "text/plain"))),
            )

        val written = CurlWriter.write(listOf(request), Shell.POSIX) as WriteResult.Written

        assertEquals("curl https://x \\\n  --form-string 'f=a;b'\n", written.text)
        assertEquals(listOf("The type of field f is left out: --form-string cannot carry one"), written.notes)
    }

    @Test
    fun `insecure has no http equivalent and is noted`() {
        val result = toHttp("curl -k -L https://x") as ConversionResult.Converted

        assertEquals("GET https://x\n", result.text)
        assertEquals(1, result.notes.size)
        assertEquals(true, result.notes.single().startsWith("--insecure has no .http equivalent"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errors")
    fun `errors name the offset in the text`(
        curl: String,
        message: String,
        offset: Int,
    ) {
        assertEquals(ConversionResult.ReadFailed(message, offset), toHttp(curl))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shells")
    fun `writes requests as curl commands for each shell`(
        shell: String,
        curl: String,
    ) {
        val http =
            """
            ### Create
            POST https://example.com/a?b=1&c=2
            Content-Type: application/json
            Authorization: Basic user pa ss

            {"name": "it's 100%"}
            """.trimIndent()

        val written = toCurl(http, Shell.ofLabel(shell)) as ConversionResult.Converted
        assertEquals(curl.trimIndent() + "\n", written.text)
        // What is written reads back as the same request.
        val read = (family.read(written.text, CurlFormat, OptionValues()) as ReadResult.Read).content
        val original = (family.read(http, HttpRequestFormat, OptionValues()) as ReadResult.Read).content
        assertEquals(original, read)
    }

    @Test
    fun `http features without a curl equivalent are noted`() {
        val result =
            toCurl(
                """
                @host = example.com

                GET https://{{host}}/a
                > {% client.log("x") %}
                """.trimIndent(),
            ) as ConversionResult.Converted

        assertEquals("curl https://{{host}}/a \\\n  -L\n", result.text.replace("'", ""))
        assertEquals(
            listOf("1 in-file variable dropped: host; their {{…}} stay as written", "Response handler dropped"),
            result.notes,
        )
    }

    @Test
    fun `a multipart body that is no list of fields is sent as raw data`() {
        val result =
            toCurl(
                """
                POST https://x
                Content-Type: multipart/form-data; boundary=b

                --b
                X-Other: 1

                v
                --b--
                """.trimIndent(),
            ) as ConversionResult.Converted

        assertEquals(listOf("The multipart body cannot be written as -F fields; sent as raw data"), result.notes)
        assertEquals(true, "-d '--b\nX-Other: 1\n\nv\n--b--'" in result.text, result.text)
    }

    @Test
    fun `http parse problems fail at their line`() {
        // The problem is on line 5, which starts at 33.
        assertEquals(
            ConversionResult.ReadFailed("Expected a header (Name: value) or a blank line before the body", 33),
            toCurl("GET https://x\n\n###\nGET https://y\nnot a header\n"),
        )
    }

    companion object {
        @JvmStatic
        fun flags() =
            listOf(
                Arguments.of("curl https://x", "# @no-redirect\nGET https://x"),
                Arguments.of("curl -L --url https://x", "GET https://x"),
                Arguments.of("curl -I -L https://x", "HEAD https://x"),
                Arguments.of(
                    "curl -L -X PATCH -H 'Accept: a' -H 'X-Empty;' https://x",
                    "PATCH https://x\nAccept: a\nX-Empty: ",
                ),
                Arguments.of(
                    "curl -L -d a=1 -d b=2 https://x",
                    "POST https://x\nContent-Type: application/x-www-form-urlencoded\n\na=1&b=2",
                ),
                Arguments.of(
                    "curl -L -d a=1 -d @data.txt --data-binary @raw.bin https://x",
                    """
                    POST https://x
                    Content-Type: application/x-www-form-urlencoded

                    a=1&
                    < ./data.txt
                    &
                    < ./raw.bin
                    """,
                ),
                Arguments.of(
                    "curl -L --data-urlencode 'q=a b&c' --data-urlencode =x/y https://x",
                    "POST https://x\nContent-Type: application/x-www-form-urlencoded\n\nq=a%20b%26c&x%2Fy",
                ),
                Arguments.of(
                    "curl -L -G -d a=1 -d b=2 'https://x?z=0'",
                    "GET https://x?z=0&a=1&b=2",
                ),
                Arguments.of(
                    "curl -L --json '{\"a\":1}' https://x",
                    "POST https://x\nContent-Type: application/json\nAccept: application/json\n\n{\"a\":1}",
                ),
                Arguments.of(
                    "curl -L -H 'Content-Type: text/plain' --data-raw @literal https://x",
                    "POST https://x\nContent-Type: text/plain\n\n@literal",
                ),
                // A script closed on its line, and text after it, is body text to the .http parser.
                Arguments.of(
                    "curl -L -H 'Content-Type: text/plain' -d $'< {% x %}\\n<{%y%} z' https://x",
                    "POST https://x\nContent-Type: text/plain\n\n< {% x %}\n<{%y%} z",
                ),
                Arguments.of(
                    "curl -L -F name=Ada -F 'pic=@img/a.png;type=image/png' -F 'note=<n.txt' " +
                        "-F 'f=@b.bin;filename=x.bin' https://x",
                    """
                    POST https://x
                    Content-Type: multipart/form-data; boundary=WebAppBoundary

                    --WebAppBoundary
                    Content-Disposition: form-data; name="name"

                    Ada
                    --WebAppBoundary
                    Content-Disposition: form-data; name="pic"; filename="a.png"
                    Content-Type: image/png

                    < ./img/a.png
                    --WebAppBoundary
                    Content-Disposition: form-data; name="note"

                    < ./n.txt
                    --WebAppBoundary
                    Content-Disposition: form-data; name="f"; filename="x.bin"

                    < ./b.bin
                    --WebAppBoundary--
                    """,
                ),
                Arguments.of(
                    "curl -L -u ada:s3cret https://x",
                    "GET https://x\nAuthorization: Basic ada s3cret",
                ),
                Arguments.of(
                    "curl -L --digest -u ada:s3cret https://x",
                    "GET https://x\nAuthorization: Digest ada s3cret",
                ),
                Arguments.of(
                    "curl -L --oauth2-bearer t0k -b a=1 -b b=2 -A agent/1 -e https://ref https://x",
                    "GET https://x\nAuthorization: Bearer t0k\nCookie: a=1; b=2\nUser-Agent: agent/1\nReferer: https://ref",
                ),
                Arguments.of(
                    "curl -L -T up.bin https://x",
                    "PUT https://x\n\n< ./up.bin",
                ),
                Arguments.of(
                    "curl -L --http2 -m 30 --connect-timeout 0.5 https://x",
                    "# @timeout 30 s\n# @connection-timeout 500 ms\nGET https://x HTTP/2",
                ),
                Arguments.of(
                    "curl -L --http1.1 --compressed https://x",
                    "GET https://x HTTP/1.1",
                ),
                Arguments.of(
                    "# Users\ncurl -L https://x/users\n\n$ curl -L https://x/items",
                    "### Users\nGET https://x/users\n\n###\nGET https://x/items",
                ),
                Arguments.of(
                    "curl.exe -L \"https://x\" `\n  -d 'a=1'",
                    "POST https://x\nContent-Type: application/x-www-form-urlencoded\n\na=1",
                ),
                Arguments.of(
                    "curl -L ^\"https://x^\" ^\n  -H ^\"A: b^\"",
                    "GET https://x\nA: b",
                ),
            )

        @JvmStatic
        fun bodySyntax() =
            listOf(
                Arguments.of(
                    "curl --data-raw '< /home/me/.ssh/id_rsa' https://x",
                    "Line 1 of the body starts with '<', which a .http file reads as a file to include",
                ),
                // Unclosed, it hides the ### lines after it.
                Arguments.of(
                    "curl -d $'a\\n< {% x' https://x",
                    "Line 2 of the body starts with '< {%' without '%}', which a .http file reads as the start of a script",
                ),
                Arguments.of(
                    "curl -d $'a\\n###\\nb' https://x",
                    "Line 2 of the body starts with '###', which a .http file reads as the start of another request",
                ),
                Arguments.of(
                    "curl -d '> {% client.exit() %}' https://x",
                    "Line 1 of the body starts with '>', which a .http file reads as a response handler",
                ),
                Arguments.of(
                    "curl -d '>>! out.json' https://x",
                    "Line 1 of the body starts with '>', which a .http file reads as a response handler",
                ),
                Arguments.of(
                    "curl -d '<> 2026-10-30.200.json' https://x",
                    "Line 1 of the body starts with '<>', which a .http file reads as a reference to a previous response",
                ),
                // The field's lines follow its boundary, disposition and blank line.
                Arguments.of(
                    "curl --form-string $'f=ok\\n< x' https://x",
                    "Line 5 of the body starts with '<', which a .http file reads as a file to include",
                ),
                Arguments.of(
                    "curl https://x\n\ncurl -d '###' https://y",
                    "Request 2: Line 1 of the body starts with '###', which a .http file reads as the start of " +
                        "another request",
                ),
            )

        @JvmStatic
        fun errors() =
            listOf(
                Arguments.of("curl -s", "The command has no URL", 0),
                Arguments.of("curl x -H", "-H needs a value", 7),
                Arguments.of("curl x --data", "--data needs a value", 7),
                Arguments.of("curl x -m soon", "--max-time needs a number of seconds", 10),
                Arguments.of("curl x -m 1e30", "--max-time takes at most 1000000000 seconds", 10),
                Arguments.of("curl x -d $'\\U00110000'", "\\U00110000 is no Unicode character", 12),
                Arguments.of("wget x", "Expected a curl command, not wget", 0),
                Arguments.of("curl x -d 'a", "Unclosed ' quote", 10),
                Arguments.of("curl x -d a -F b=1", "-F cannot be combined with -d or --json", 12),
            )

        @JvmStatic
        fun shells() =
            listOf(
                Arguments.of(
                    Shell.POSIX.label,
                    """
                    # Create
                    curl 'https://example.com/a?b=1&c=2' \
                      -L \
                      -u 'user:pa ss' \
                      -H 'Content-Type: application/json' \
                      -d '{"name": "it'\''s 100%"}'
                    """,
                ),
                Arguments.of(
                    Shell.CMD.label,
                    """
                    REM Create
                    curl "https://example.com/a?b=1&c=2" ^
                      -L ^
                      -u "user:pa ss" ^
                      -H "Content-Type: application/json" ^
                      -d ^"{\^"name\^": \^"it's 100^%\^"}^"
                    """,
                ),
                Arguments.of(
                    Shell.POWERSHELL.label,
                    """
                    # Create
                    curl.exe 'https://example.com/a?b=1&c=2' `
                      -L `
                      -u 'user:pa ss' `
                      -H 'Content-Type: application/json' `
                      -d '{"name": "it''s 100%"}'
                    """,
                ),
            )
    }
}
