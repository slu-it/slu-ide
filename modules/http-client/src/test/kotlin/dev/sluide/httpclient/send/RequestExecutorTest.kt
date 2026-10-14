package dev.sluide.httpclient.send

import dev.sluide.httpclient.parse.HttpFileParser
import dev.sluide.httpclient.parse.HttpVersion
import dev.sluide.httpclient.parse.RequestTags
import dev.sluide.httpclient.vars.VariableResolver
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestExecutorTest {
    private val server = TestServer()
    private val clients = HttpClients()
    private val executor = RequestExecutor(clients)

    @AfterTest
    fun stop() {
        executor.close()
        clients.close()
        server.close()
    }

    private fun response(
        request: PreparedRequest,
        tags: RequestTags = RequestTags(),
    ): HttpResult = assertIs<HttpResult>(executor.await(request, tags))

    private fun HttpResult.text() = body.toString(Charsets.UTF_8)

    @Test
    fun `sends method, headers and body`() {
        val result =
            response(
                server.request("/echo?a=1", "POST", listOf("Content-Type" to "text/plain", "X-Test" to "1"), "hello ä"),
            )

        assertEquals(200, result.status)
        assertEquals("OK", result.reason)
        assertEquals(HttpVersion.HTTP_1_1, result.version)
        assertEquals(URI("${server.base}/echo?a=1"), result.finalUri)
        val text = result.text()
        assertTrue(text.startsWith("POST\n"), text)
        assertTrue("content-type: text/plain" in text, text)
        assertTrue("x-test: 1" in text, text)
        assertTrue(text.endsWith("\n\nhello ä"), text)
        assertTrue(result.elapsed > Duration.ZERO)
        assertTrue(result.headers.any { it.first.equals("Content-Length", ignoreCase = true) })
    }

    @Test
    fun `sends a request built from a http file with a written Host header`() {
        val file = HttpFileParser.parse("GET ${server.base}/echo\nHost: custom.example\nUser-Agent: test")
        val request =
            RequestBuilder(
                Path.of("a.http"),
                VariableResolver(null, file.variables),
            ).build(file.requests.single())

        val text = response(request).text()

        assertTrue("host: custom.example" in text, text)
        assertTrue("user-agent: test" in text, text)
        assertTrue("accept-encoding: gzip, deflate" in text, text)
    }

    @Test
    fun `follows redirects unless told not to`() {
        server.handle("/r1") { server.respond(it, 302, "", "Location" to "/r2") }
        server.handle("/r2") { server.respond(it, 301, "", "Location" to "/final") }
        server.handle("/final") { server.respond(it, 200, "final") }

        val followed = response(server.request("/r1"))
        assertEquals(200, followed.status)
        assertEquals("final", followed.text())
        assertEquals(URI("${server.base}/final"), followed.finalUri)

        val notFollowed = response(server.request("/r1"), RequestTags(noRedirect = true))
        assertEquals(302, notFollowed.status)
        assertEquals("/r2", notFollowed.header("location"))
        assertEquals(URI("${server.base}/r1"), notFollowed.finalUri)
    }

    @Test
    fun `keeps cookies unless told not to`() {
        server.handle("/set") { server.respond(it, 200, "", "Set-Cookie" to "session=abc; Path=/") }

        response(server.request("/set"), RequestTags(noCookieJar = true))
        assertFalse("cookie:" in response(server.request("/echo")).text())

        response(server.request("/set"))
        assertTrue("cookie: session=abc" in response(server.request("/echo")).text())
        assertFalse("cookie:" in response(server.request("/echo"), RequestTags(noCookieJar = true)).text())

        clients.clearCookies()
        assertFalse("cookie:" in response(server.request("/echo")).text())
    }

    @Test
    fun `decodes gzip and deflate bodies`() {
        server.handle("/gzip") { exchange ->
            val bytes =
                ByteArrayOutputStream().also { out ->
                    GZIPOutputStream(out).use { it.write("zipped".toByteArray()) }
                }
            server.respond(exchange, 200, bytes.toByteArray(), "Content-Encoding" to "gzip")
        }
        server.handle("/deflate") { exchange ->
            val bytes =
                ByteArrayOutputStream().also { out ->
                    DeflaterOutputStream(out).use { it.write("deflated".toByteArray()) }
                }
            server.respond(exchange, 200, bytes.toByteArray(), "Content-Encoding" to "deflate")
        }

        assertEquals("zipped", response(server.request("/gzip")).text())
        assertEquals("deflated", response(server.request("/deflate")).text())
    }

    @Test
    fun `truncates long bodies`() {
        server.handle("/long") { server.respond(it, 200, "x".repeat(100_000)) }

        val result =
            RequestExecutor(clients, bodyLimit = 10).use { assertIs<HttpResult>(it.await(server.request("/long"))) }

        assertTrue(result.truncated)
        assertEquals("x".repeat(10), result.text())
        assertEquals(1, result.notes.size)
    }

    @Test
    fun `gives up after the timeout, also while the body is read`() {
        server.handle("/slow") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("start".toByteArray())
            exchange.responseBody.flush()
            server.released.await()
        }

        val result =
            assertIs<HttpFailure>(
                executor.await(server.request("/slow"), RequestTags(timeout = Duration.ofMillis(300))),
            )

        assertTrue("300 ms" in result.message, result.message)
        assertFalse(result.cancelled)
        assertTrue(result.elapsed >= Duration.ofMillis(300))
    }

    @Test
    fun `cancels a running request`() {
        val reached = CountDownLatch(1)
        server.handle("/wait") {
            reached.countDown()
            server.released.await()
        }
        val result = CompletableFuture<ExchangeResult>()

        val running = executor.run(server.request("/wait"), RequestTags(), result::complete)
        assertTrue(reached.await(10, TimeUnit.SECONDS))
        running.cancel()

        val failure = assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS))
        assertTrue(failure.cancelled, failure.message)
    }

    @Test
    fun `reports connection failures`() {
        val port = ServerSocket(0).use { it.localPort }
        val request =
            PreparedRequest(
                URI("http://127.0.0.1:$port/"),
                "GET",
                HttpVersion.HTTP_1_1,
                emptyList(),
                ByteArray(0),
                listOf("n"),
            )

        val failure = assertIs<HttpFailure>(executor.await(request))

        assertEquals("Connection refused: 127.0.0.1:$port", failure.message)
        assertFalse(failure.cancelled)
        assertEquals(listOf("n"), failure.notes)
    }

    @Test
    fun `reports unknown hosts`() {
        val request =
            PreparedRequest(
                URI("http://sluide.invalid/"),
                "GET",
                HttpVersion.HTTP_1_1,
                emptyList(),
                ByteArray(0),
                emptyList(),
            )

        val failure =
            HttpClients(proxy = HttpClient.Builder.NO_PROXY).use { direct ->
                RequestExecutor(direct).use {
                    it.await(request)
                }
            }

        assertEquals("Unknown host: sluide.invalid", assertIs<HttpFailure>(failure).message)
    }

    @Test
    fun `hands out no clients once closed`() {
        val closed = HttpClients().also { it.close() }

        assertFailsWith<IllegalStateException> {
            closed.client(
                HttpClients.ClientKey(true, true, Duration.ofSeconds(1)),
            )
        }
    }

    @Test
    fun `hands results over through dispatch`() {
        val dispatched = CompletableFuture<Thread>()
        val result = CompletableFuture<ExchangeResult>()
        val dispatching =
            RequestExecutor(clients, dispatch = { runnable ->
                dispatched.complete(Thread.currentThread())
                runnable.run()
            })

        dispatching.run(server.request("/echo"), RequestTags(), result::complete)

        assertIs<HttpResult>(result.get(10, TimeUnit.SECONDS))
        assertTrue(dispatched.get().isVirtual)
        dispatching.close()
    }
}
