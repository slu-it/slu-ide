package dev.sluide.httpclient.send

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.httpclient.auth.DigestAuth
import dev.sluide.httpclient.parse.HttpFileParser
import dev.sluide.httpclient.parse.HttpVersion
import dev.sluide.httpclient.parse.RequestTags
import dev.sluide.httpclient.vars.VariableResolver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestExecutorTest {
    private val server = TestServer()
    private val clients = HttpClients()

    /** The log lines of the background tasks of the requests, without their numbers and durations. */
    private val taskLines = CopyOnWriteArrayList<String>()
    private val tasks =
        BackgroundTasks.forTests { line ->
            taskLines += line.replace(Regex("task#\\d+"), "task").replace(Regex(" (after|in) \\d+(\\.\\d+)? s"), " $1")
        }
    private val executor = RequestExecutor(clients, tasks)

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
            RequestExecutor(
                clients,
                tasks,
                bodyLimit = 10,
            ).use { assertIs<HttpResult>(it.await(server.request("/long"))) }

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
        assertEquals(
            listOf("task started: GET 127.0.0.1", "task failed after: GET 127.0.0.1: ConnectException"),
            taskLines,
        )
    }

    @Test
    fun `a request is a background task named by its name or host that finishes also with an error status`() {
        server.handle("/missing") { server.respond(it, 404, "gone") }

        val named = server.request("/echo/hooks/T0K3N;jsessionid=ABC?token=secret&a=1", "POST", body = "body")
        assertEquals(200, assertIs<HttpResult>(executor.await(named, RequestTags(), "Post hook")).status)
        assertEquals(404, response(server.request("/missing;jsessionid=ABC?q=x")).status)

        assertEquals(
            listOf(
                "task started: POST Post hook",
                "task finished in: POST Post hook (200, N bytes)",
                "task started: GET 127.0.0.1",
                "task finished in: GET 127.0.0.1 (404, 4 bytes)",
            ),
            // The echo's size depends on the client's headers.
            taskLines.map { it.replace(Regex("200, \\d+ bytes"), "200, N bytes") },
        )
        assertTrue(
            taskLines.none { listOf("T0K3N", "jsessionid", "secret", "q=x", "missing").any(it::contains) },
            taskLines.toString(),
        )
    }

    @Test
    fun `the task name has no path, query or user info`() {
        val uri = URI("https://user:pass@api.example.com:8443/bot123:ABC/send;jsessionid=XYZ?key=secret#frag")

        assertEquals("GET api.example.com", RequestExecutor.taskName("GET", null, uri))
        assertEquals("GET api.example.com", RequestExecutor.taskName("GET", "  ", uri))
        assertEquals("DELETE Get rid of it", RequestExecutor.taskName("DELETE", " Get rid of it ", uri))
    }

    @Test
    fun `the task of a request cancels it and ends as cancelled`() {
        val reached = CountDownLatch(1)
        server.handle("/wait") {
            reached.countDown()
            server.released.await()
        }
        val result = CompletableFuture<ExchangeResult>()

        executor.run(server.request("/wait"), RequestTags(), result::complete)
        assertTrue(reached.await(10, TimeUnit.SECONDS))
        tasks.shown.single().cancel()

        assertTrue(assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS)).cancelled)
        assertEquals(
            listOf("task started: GET 127.0.0.1", "task cancelled", "task cancelled after: GET 127.0.0.1"),
            taskLines,
        )
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
                RequestExecutor(direct, tasks).use {
                    it.await(request)
                }
            }

        assertEquals("Unknown host: sluide.invalid", assertIs<HttpFailure>(failure).message)
    }

    @Test
    fun `reports a request that the closed executor interrupted`() {
        val reached = CountDownLatch(1)
        server.handle("/wait") {
            reached.countDown()
            server.released.await()
        }
        val result = CompletableFuture<ExchangeResult>()

        executor.run(server.request("/wait"), RequestTags(), result::complete)
        assertTrue(reached.await(10, TimeUnit.SECONDS))
        executor.close()

        val failure = assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS))
        assertEquals("Interrupted", failure.message)
        assertTrue(failure.cancelled)
    }

    @Test
    fun `describes errors by the most telling exception of the chain`() {
        val https = URI("https://example.com/x")
        val http = URI("http://localhost:8080/x")
        val timeout = Duration.ofSeconds(2)

        fun message(
            error: Throwable,
            uri: URI = https,
        ) = RequestExecutor.message(error, uri, timeout)

        assertEquals(
            "Connection timed out after 2 s (@connection-timeout)",
            message(RuntimeException(HttpConnectTimeoutException("x"))),
        )
        assertEquals(
            "Connection timed out after 1500 ms (@connection-timeout)",
            RequestExecutor.message(HttpConnectTimeoutException("x"), https, Duration.ofMillis(1500)),
        )
        assertEquals("Unknown host: example.com", message(RuntimeException(UnknownHostException("example.com"))))
        assertEquals("TLS handshake failed: bad cert", message(IOException(SSLException("bad cert"))))
        assertEquals("Connection refused: example.com:443", message(ConnectException("Connection refused")))
        assertEquals("Connection refused: localhost:8080", message(IOException(ConnectException()), http))
        assertEquals(
            "Cannot connect to localhost:8080: Network is unreachable",
            message(IOException(ConnectException("Network is unreachable")), http),
        )
        assertEquals("IllegalStateException: boom", message(RuntimeException(null, IllegalStateException("boom"))))
        assertEquals("IllegalStateException", message(IllegalStateException()))
    }

    @Test
    fun `hands results over through dispatch`() {
        val dispatched = CompletableFuture<Thread>()
        val result = CompletableFuture<ExchangeResult>()
        val dispatching =
            RequestExecutor(clients, tasks, dispatch = { runnable ->
                dispatched.complete(Thread.currentThread())
                runnable.run()
            })

        dispatching.run(server.request("/echo"), RequestTags(), result::complete)

        assertIs<HttpResult>(result.get(10, TimeUnit.SECONDS))
        assertTrue(dispatched.get().isVirtual)
        dispatching.close()
    }

    @Test
    fun `answers a Digest challenge once with the same body`() {
        val requests = CopyOnWriteArrayList<String?>()
        server.handle("/digest") { exchange ->
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            requests += authorization
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val params =
                Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))").findAll(authorization.orEmpty()).associate {
                    it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] }
                }

            fun sha(text: String) =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
            val ha1 = sha("user:test realm:secret")
            val ha2 = sha("${exchange.requestMethod}:${exchange.requestURI}")
            val expected = sha("$ha1:abc:${params["nc"]}:${params["cnonce"]}:auth:$ha2")
            if (params["response"] == expected && params["uri"] == exchange.requestURI.toString()) {
                server.respond(exchange, 200, "welcome $body")
            } else {
                server.respond(
                    exchange,
                    401,
                    "",
                    "WWW-Authenticate" to
                        "Digest realm=\"test realm\", qop=\"auth\", algorithm=SHA-256, nonce=\"abc\", opaque=\"o\"",
                )
            }
        }
        val file = HttpFileParser.parse("POST ${server.base}/digest?x=1\nAuthorization: Digest user secret\n\npayload")
        val request =
            RequestBuilder(
                Path.of("a.http"),
                VariableResolver(null, file.variables),
            ).build(file.requests.single())

        val result = response(request)

        assertEquals(200, result.status)
        assertEquals("welcome payload", result.text())
        assertEquals(2, requests.size)
        assertEquals(null, requests[0])
        assertTrue(result.notes.single().contains("SHA-256"), result.notes.toString())
    }

    @Test
    fun `answers a Digest challenge only once`() {
        val count = AtomicInteger()
        server.handle("/deny") { exchange ->
            count.incrementAndGet()
            server.respond(exchange, 401, "", "WWW-Authenticate" to "Digest realm=\"r\", qop=\"auth\", nonce=\"n\"")
        }
        val request =
            server.request("/deny").let {
                PreparedRequest(
                    it.uri,
                    it.method,
                    it.version,
                    it.headers,
                    it.body,
                    it.notes,
                    DigestAuth.Credentials("u", "p"),
                )
            }

        assertEquals(401, response(request).status)
        assertEquals(2, count.get())
    }

    @Test
    fun `notes 401 responses without a Digest challenge`() {
        server.handle("/basic") { server.respond(it, 401, "", "WWW-Authenticate" to "Basic realm=\"r\"") }
        val request =
            server.request("/basic").let {
                PreparedRequest(
                    it.uri,
                    it.method,
                    it.version,
                    it.headers,
                    it.body,
                    it.notes,
                    DigestAuth.Credentials("u", "p"),
                )
            }

        val result = response(request)

        assertEquals(401, result.status)
        assertEquals(1, result.notes.size)
    }

    @Test
    fun `gives exactly one result when preparing throws an error`() {
        val results = CopyOnWriteArrayList<ExchangeResult>()
        val done = CountDownLatch(1)

        executor.run(RequestTags(), {
            results += it
            done.countDown()
        }) { throw StackOverflowError("deep") }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        // A second result, if one were given, would follow the first; no event marks its absence.
        Thread.sleep(100)
        val failure = assertIs<HttpFailure>(results.single())
        assertEquals("Unexpected error: java.lang.StackOverflowError: deep", failure.message)
    }

    @Test
    fun `reports a cancel that surfaces as another error while preparing as cancelled`() {
        val preparing = CountDownLatch(1)
        val result = CompletableFuture<ExchangeResult>()
        val running =
            executor.run(RequestTags(), result::complete) {
                preparing.countDown()
                try {
                    CountDownLatch(1).await() // stands for slow preparation; the cancel interrupts it
                } catch (e: InterruptedException) {
                    throw java.nio.channels
                        .ClosedByInterruptException()
                        .initCause(e)
                }
                server.request("/echo")
            }
        assertTrue(preparing.await(5, TimeUnit.SECONDS))

        running.cancel()

        val failure = assertIs<HttpFailure>(result.get(5, TimeUnit.SECONDS))
        assertTrue(failure.cancelled)
        assertEquals("Cancelled", failure.message)
    }

    @Test
    fun `leaves no interrupt behind on a pooled thread`() {
        val pool =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        RequestExecutor(clients, tasks, pool).use { pooled ->
            val preparing = CountDownLatch(1)
            val first = CompletableFuture<ExchangeResult>()
            val running =
                pooled.run(RequestTags(), first::complete) {
                    preparing.countDown()
                    CountDownLatch(1).await() // stands for slow preparation; the cancel interrupts it
                    server.request("/echo")
                }
            assertTrue(preparing.await(5, TimeUnit.SECONDS))
            running.cancel()
            assertIs<HttpFailure>(first.get(5, TimeUnit.SECONDS))

            val interrupted = pool.submit<Boolean> { Thread.currentThread().isInterrupted }.get(5, TimeUnit.SECONDS)

            assertFalse(interrupted)
        }
        pool.shutdownNow()
    }
}
