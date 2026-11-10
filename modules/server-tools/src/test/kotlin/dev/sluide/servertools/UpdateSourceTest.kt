package dev.sluide.servertools

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The helpers update sources share: the request, web page links and the version order. */
class UpdateSourceTest {
    @Test
    fun `a fetch sends its headers as Thoreau and reads status, headers in lower case and body`() {
        val requests = mutableListOf<Pair<String?, String?>>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/latest") { exchange ->
            requests += exchange.requestHeaders.getFirst("Accept") to exchange.requestHeaders.getFirst("User-Agent")
            val body = "{}".toByteArray()
            exchange.responseHeaders.add("X-RateLimit-Remaining", "0")
            exchange.sendResponseHeaders(403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val uri = URI("http://127.0.0.1:${server.address.port}/latest")

            val answer = fetchText(uri, mapOf("Accept" to "application/json"))

            assertEquals(HttpAnswer(403, answer.headers, "{}"), answer)
            assertEquals("0", answer.headers["x-ratelimit-remaining"])
            assertEquals(listOf<Pair<String?, String?>>("application/json" to "Thoreau"), requests)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a web page is an https URI with a host`() {
        assertEquals(URI("https://example.com/notes"), webPage("https://example.com/notes"))
        assertNull(webPage("http://example.com/notes"))
        assertNull(webPage("https:///notes"))
        assertNull(webPage("not a uri"))
    }

    @ParameterizedTest
    @CsvSource(
        "0.0.99, 0.0.98, 1",
        "0.0.100, 0.0.99, 1",
        "0.1.0, 0.0.99, 1",
        "1.0, 0.99.99, 1",
        "0.1, 0.1.0, 0",
        "0.0.99, 0.0.99, 0",
        "0.1.0, 0.1.0-rc1, 1",
        "0.1.0-rc2, 0.1.0-rc1, 1",
        "10.0.0, 9.0.0, 1",
        "0.0.98, 0.0.99, -1",
        "12345678901234567890.0, 1.0, 1",
    )
    fun `versions compare by number per dot segment`(
        a: String,
        b: String,
        expected: Int,
    ) {
        assertEquals(expected, Integer.signum(compareVersions(a, b)))
        assertEquals(-expected, Integer.signum(compareVersions(b, a)))
    }
}
