package dev.sluide.httpclient.ui

import dev.sluide.httpclient.parse.HttpVersion
import dev.sluide.httpclient.send.HttpFailure
import dev.sluide.httpclient.send.HttpResult
import dev.sluide.httpclient.send.PreparedRequest
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseTextsTest {
    private val request =
        PreparedRequest(
            URI("https://example.com/users"),
            "POST",
            HttpVersion.HTTP_1_1,
            listOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer secret-token",
                "proxy-authorization" to "secret",
            ),
            """{"a":1}""".toByteArray(),
            emptyList(),
        )

    private fun result(status: Int) =
        HttpResult(
            status,
            "Not Found".takeIf { status == 404 },
            HttpVersion.HTTP_2,
            URI("https://example.com/final"),
            listOf("content-type" to "text/plain", "set-cookie" to "a=1", "set-cookie" to "b=2"),
            "hello".toByteArray(),
            false,
            Duration.ofMillis(1250),
            emptyList(),
        )

    @Test
    fun `the status line has code, reason, time, size, version and final URL`() {
        assertEquals(
            StatusLine(
                "404 Not Found",
                StatusKind.CLIENT_ERROR,
                listOf("1.25 s", "5 B", "HTTP/2", "https://example.com/final"),
            ),
            ResponseTexts.statusLine(result(404)),
        )
        assertEquals("299", ResponseTexts.statusLine(result(299)).status)
        assertEquals(StatusKind.SUCCESS, ResponseTexts.statusLine(result(299)).kind)
        assertEquals(StatusKind.REDIRECT, StatusKind.of(302))
        assertEquals(StatusKind.SERVER_ERROR, StatusKind.of(503))
        assertEquals(StatusKind.INFORMATIONAL, StatusKind.of(101))
    }

    @Test
    fun `status codes are sorted into kinds at the edges of their ranges`() {
        val kinds =
            mapOf(
                99 to StatusKind.SERVER_ERROR,
                100 to StatusKind.INFORMATIONAL,
                199 to StatusKind.INFORMATIONAL,
                200 to StatusKind.SUCCESS,
                299 to StatusKind.SUCCESS,
                300 to StatusKind.REDIRECT,
                399 to StatusKind.REDIRECT,
                400 to StatusKind.CLIENT_ERROR,
                499 to StatusKind.CLIENT_ERROR,
                500 to StatusKind.SERVER_ERROR,
            )
        for ((status, kind) in kinds) assertEquals(kind, StatusKind.of(status), "$status")
    }

    @Test
    fun `failures and cancelled runs have no status code`() {
        val failed = HttpFailure("Connection refused", false, Duration.ofMillis(5), emptyList())
        val cancelled = HttpFailure("Cancelled", true, Duration.ofMillis(5), emptyList())

        assertEquals(StatusLine("Failed", StatusKind.FAILURE, listOf("5 ms")), ResponseTexts.statusLine(failed))
        assertEquals("Cancelled", ResponseTexts.statusLine(cancelled).status)
    }

    @Test
    fun `headers are listed as received`() {
        assertEquals("content-type: text/plain\nset-cookie: a=1\nset-cookie: b=2", ResponseTexts.headers(result(200)))
    }

    @Test
    fun `the request as sent masks credentials`() {
        assertEquals(
            """
            |POST https://example.com/users HTTP/1.1
            |Content-Type: application/json
            |Authorization: Bearer ••••••
            |proxy-authorization: ••••••
            |
            |{"a":1}
            """.trimMargin(),
            ResponseTexts.request(request).text,
        )
    }

    @Test
    fun `binary request bodies are summarised`() {
        val upload =
            PreparedRequest(
                URI("http://x/"),
                "PUT",
                HttpVersion.HTTP_1_1,
                listOf("Content-Type" to "image/png"),
                byteArrayOf(0, 1, 2),
                emptyList(),
            )

        assertEquals(
            "PUT http://x/ HTTP/1.1\nContent-Type: image/png\n\nBinary body, 3 B, image/png",
            ResponseTexts.request(upload).text,
        )
    }

    private fun upload(
        body: ByteArray,
        headers: List<Pair<String, String>> = emptyList(),
        secrets: List<String> = emptyList(),
    ) = PreparedRequest(URI("http://x/"), "PUT", HttpVersion.HTTP_1_1, headers, body, emptyList(), secrets = secrets)

    @Test
    fun `a request without a body ends after its headers`() {
        assertEquals(
            "PUT http://x/ HTTP/1.1\nAccept: */*",
            ResponseTexts
                .request(
                    upload(
                        ByteArray(0),
                        listOf("Accept" to "*/*"),
                    ),
                ).text,
        )
    }

    @Test
    fun `a binary body without a content type has no type in its summary`() {
        assertEquals(
            "PUT http://x/ HTTP/1.1\n\nBinary body, 3 B",
            ResponseTexts.request(upload(byteArrayOf(0, 1, 2))).text,
        )
    }

    @Test
    fun `an empty secret masks nothing`() {
        assertEquals(
            "PUT http://x/ HTTP/1.1\nAccept: abc",
            ResponseTexts.request(upload(ByteArray(0), listOf("Accept" to "abc"), listOf("", "zzz"))).text,
        )
    }

    @Test
    fun `a body of exactly the display limit is shown whole`() {
        val shown = ResponseTexts.request(upload("a".repeat(ResponseTexts.DISPLAY_LIMIT).toByteArray()))

        assertEquals(false, shown.partial)
        assertTrue(shown.text.endsWith("\n\n" + "a".repeat(ResponseTexts.DISPLAY_LIMIT)))
    }

    @Test
    fun `a long body of continuation bytes is cut down to nothing, not before its start`() {
        val body = ByteArray(ResponseTexts.DISPLAY_LIMIT + 1) { 0x80.toByte() }

        val shown = ResponseTexts.request(upload(body, listOf("Content-Type" to "text/plain")))

        assertTrue(shown.partial)
        assertTrue(shown.text.startsWith("PUT http://x/ HTTP/1.1\nContent-Type: text/plain"))
    }

    @Test
    fun `secrets are masked in the request as sent`() {
        val sent =
            PreparedRequest(
                URI("https://example.com/users?access_token=tok-123"),
                "POST",
                HttpVersion.HTTP_1_1,
                listOf(
                    "Cookie" to "session=abc; theme=dark",
                    "X-Api-Key" to "k",
                    "X-Auth-Token" to "t",
                    "Client-Secret" to "s",
                    "ApiKey" to "a",
                    "X-Request-Id" to "visible",
                ),
                """{"token": "tok-123"}""".toByteArray(),
                emptyList(),
                secrets = listOf("tok-123"),
            )

        assertEquals(
            """
            |POST https://example.com/users?access_token=•••••• HTTP/1.1
            |Cookie: session=••••••; theme=••••••
            |X-Api-Key: ••••••
            |X-Auth-Token: ••••••
            |Client-Secret: ••••••
            |ApiKey: ••••••
            |X-Request-Id: visible
            |
            |{"token": "••••••"}
            """.trimMargin(),
            ResponseTexts.request(sent).text,
        )
    }

    @Test
    fun `large request bodies are shown in part, as written`() {
        // A two-byte character right at the limit is not cut in half.
        val body = "a".repeat(ResponseTexts.DISPLAY_LIMIT - 1) + "é" + "b".repeat(10)
        val sent =
            PreparedRequest(
                URI("http://x/"),
                "PUT",
                HttpVersion.HTTP_1_1,
                listOf("Content-Type" to "application/json"),
                body.toByteArray(),
                emptyList(),
            )

        val shown = ResponseTexts.request(sent)

        assertTrue(shown.partial)
        assertTrue(shown.text.endsWith("\n\n" + "a".repeat(ResponseTexts.DISPLAY_LIMIT - 1)))
    }
}
