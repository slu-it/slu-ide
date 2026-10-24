package dev.sluide.httpclient.ui

import dev.sluide.httpclient.parse.HttpVersion
import dev.sluide.httpclient.send.HttpFailure
import dev.sluide.httpclient.send.HttpResult
import dev.sluide.httpclient.send.PreparedRequest
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResponseContentTest {
    private fun request(body: ByteArray = ByteArray(0)) =
        PreparedRequest(URI("https://example.com/"), "POST", HttpVersion.HTTP_1_1, emptyList(), body, emptyList())

    private fun result(
        body: String,
        contentType: String,
        notes: List<String> = emptyList(),
        request: PreparedRequest? = null,
    ) = HttpResult(
        200,
        "OK",
        HttpVersion.HTTP_1_1,
        URI("https://example.com/"),
        listOf("content-type" to contentType),
        body.toByteArray(),
        false,
        Duration.ofMillis(5),
        notes,
        request,
    )

    @Test
    fun `offers the raw body only when it differs from the shown one`() {
        val json = ResponseContent.of(result("""{"a":1}""", "application/json"))
        assertEquals("""{"a":1}""", json.rawBody)
        assertEquals("{\n  \"a\": 1\n}", json.body.trim().replace("\r\n", "\n"))

        val plain = ResponseContent.of(result("hello", "text/plain"))
        assertNull(plain.rawBody)
        assertEquals("hello", plain.body)
        assertNull(plain.error)
    }

    @Test
    fun `cuts a long body and says so`() {
        val body = "a".repeat(ResponseTexts.DISPLAY_LIMIT + 10)

        val content = ResponseContent.of(result(body, "text/plain", notes = listOf("first")))

        assertEquals(ResponseTexts.DISPLAY_LIMIT, content.body.length)
        assertEquals(listOf("first", "Showing the first 5 M characters of the body"), content.notes)
    }

    @Test
    fun `notes the request body shown in part after the other notes`() {
        val big = ByteArray(ResponseTexts.DISPLAY_LIMIT + 1) { 'a'.code.toByte() }
        val long = "a".repeat(ResponseTexts.DISPLAY_LIMIT + 1)

        val content = ResponseContent.of(result(long, "text/plain", request = request(big)))

        assertEquals(
            listOf("Showing the first 5 M characters of the body", "Request body shown in part"),
            content.notes,
        )
    }

    @Test
    fun `a failure has an error instead of a body`() {
        val failure = HttpFailure("Connection refused", false, Duration.ofMillis(5), listOf("note"), request())

        val content = ResponseContent.of(failure)

        assertEquals("Connection refused", content.error)
        assertEquals("", content.body)
        assertNull(content.rawBody)
        assertEquals("", content.headers)
        assertEquals(listOf("note"), content.notes)
        assertEquals("POST https://example.com/ HTTP/1.1", content.request.lines().first())
    }
}
