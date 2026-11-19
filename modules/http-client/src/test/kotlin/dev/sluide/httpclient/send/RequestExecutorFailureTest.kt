package dev.sluide.httpclient.send

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.httpclient.auth.DigestAuth
import dev.sluide.httpclient.auth.OAuth2Exception
import dev.sluide.httpclient.parse.HttpVersion
import dev.sluide.httpclient.parse.RequestTags
import dev.sluide.httpclient.vars.UnresolvedVariablesException
import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the executor ends exchanges that fail, are cancelled or time out, and what it notes and logs about them. */
class RequestExecutorFailureTest {
    private val server = TestServer()
    private val clients = HttpClients(proxy = java.net.http.HttpClient.Builder.NO_PROXY)
    private val taskLines = CopyOnWriteArrayList<String>()
    private val tasks = BackgroundTasks.forTests { taskLines += it.replace(Regex("task#\\d+"), "task") }
    private val executor = RequestExecutor(clients, tasks)

    @AfterTest
    fun stop() {
        executor.close()
        clients.close()
        server.close()
    }

    private fun HttpResult.text() = body.toString(Charsets.UTF_8)

    /** The `LEVEL message` lines the executor logs while [action] runs. */
    private fun logged(action: () -> Unit): List<String> =
        LogCapture(RequestExecutor::class.java.name).use { log ->
            action()
            log.lines.map { (level, message) -> "$level $message" }
        }

    private fun failing(error: () -> Throwable): HttpFailure {
        val result = CompletableFuture<ExchangeResult>()
        executor.run(RequestTags(), result::complete) { throw error() }
        return assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `tells why preparing failed, by the message or else the kind of error`() {
        assertEquals("boom", failing { IllegalStateException("boom") }.message)
        assertEquals("IllegalStateException", failing { IllegalStateException() }.message)
    }

    @Test
    fun `logs a failure of preparing by whether it was expected, never more than the kind`() {
        val expected =
            listOf(
                IOException("secret-token"),
                UnresolvedAddressException(),
                TimeoutException(),
                RequestBuildException("secret-token"),
                SslConfigException("secret-token"),
                UnresolvedVariablesException(listOf("secret-token")),
                RuntimeException("wrapper", IOException("secret-token")),
            )

        val lines = logged { expected.forEach { failing { it } } }

        assertEquals(
            expected.map { "WARNING A request not yet prepared failed: ${it::class.simpleName}" },
            lines,
        )
        val unexpected = logged { failing { IllegalStateException("secret-token") } }
        assertEquals(listOf("SEVERE A request not yet prepared failed unexpectedly"), unexpected)
        assertEquals(emptyList(), logged { failing { RuntimeException("x", OAuth2Exception("logged elsewhere")) } })
    }

    @Test
    fun `keeps the notes the preparing gathered when it fails`() {
        val result = CompletableFuture<ExchangeResult>()

        executor.run(RequestTags(), result::complete) { notes ->
            notes += "token acquired"
            throw IllegalStateException("then it failed")
        }

        val failure = assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS))
        assertEquals(listOf("token acquired"), failure.notes)
        assertNull(failure.request)
        assertTrue(failure.elapsed < Duration.ofMinutes(1), failure.elapsed.toString())
    }

    @Test
    fun `keeps the notes the preparing gathered when a cancel interrupts it`() {
        val preparing = CountDownLatch(1)
        val result = CompletableFuture<ExchangeResult>()
        val running =
            executor.run(RequestTags(), result::complete) { notes ->
                notes += "token requested"
                preparing.countDown()
                CountDownLatch(1).await()
                server.request("/echo")
            }
        assertTrue(preparing.await(10, TimeUnit.SECONDS))

        running.cancel()

        val failure = assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS))
        assertEquals("Cancelled", failure.message)
        assertEquals(listOf("token requested"), failure.notes)
    }

    @Test
    fun `a request cancelled before its turn is not prepared`() {
        val busy = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val prepared = AtomicInteger()
        RequestExecutor(clients, tasks, pool).use { queued ->
            pool.execute { busy.await() }
            val result = CompletableFuture<ExchangeResult>()
            val running =
                queued.run(RequestTags(), result::complete) {
                    prepared.incrementAndGet()
                    server.request("/echo")
                }

            try {
                running.cancel()
            } finally {
                busy.countDown()
            }

            val failure = assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS))
            assertEquals("Cancelled", failure.message)
            assertTrue(failure.cancelled)
            assertEquals(0, prepared.get())
        }
        pool.shutdownNow()
    }

    @Test
    fun `a request cancelled while preparing is not sent although the preparing finished`() {
        val sent = AtomicInteger()
        server.handle("/count") {
            sent.incrementAndGet()
            server.respond(it, 200, "")
        }
        val preparing = CountDownLatch(1)
        val result = CompletableFuture<ExchangeResult>()
        val running =
            executor.run(RequestTags(), result::complete) {
                preparing.countDown()
                try {
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    // swallowed: the preparing ends normally although it was cancelled
                }
                server.request("/count")
            }
        assertTrue(preparing.await(10, TimeUnit.SECONDS))

        running.cancel()

        val failure = assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS))
        assertEquals("Cancelled", failure.message)
        assertEquals(0, sent.get())
        assertEquals(emptyList(), taskLines)
    }

    @Test
    fun `an interrupt of the closed executor that preparing left set does not interrupt the sending`() {
        val preparing = CountDownLatch(1)
        val result = CompletableFuture<ExchangeResult>()
        val own = RequestExecutor(clients, tasks)
        own.run(RequestTags(), result::complete) {
            preparing.countDown()
            try {
                CountDownLatch(1).await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt() // preparing that ends with the flag set
            }
            server.request("/echo")
        }
        assertTrue(preparing.await(10, TimeUnit.SECONDS))

        own.close()

        assertEquals(200, assertIs<HttpResult>(result.get(10, TimeUnit.SECONDS)).status)
    }

    private fun badHeader() =
        PreparedRequest(
            URI("${server.base}/echo"),
            "GET",
            HttpVersion.HTTP_1_1,
            listOf("bad name" to "x"),
            ByteArray(0),
            emptyList(),
        )

    @Test
    fun `reports an error of building the request with its kind and ends the task as failed`() {
        val result = CompletableFuture<ExchangeResult>()
        executor.run(badHeader(), RequestTags(), result::complete)

        val failure = assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS))
        assertTrue(failure.message.startsWith("IllegalArgumentException: "), failure.message)
        assertFalse(failure.cancelled)
        assertEquals("GET 127.0.0.1", failure.request?.let { "${it.method} ${it.uri.host}" })
        assertEquals(2, taskLines.size, taskLines.toString())
        assertTrue(taskLines[1].startsWith("task failed after"), taskLines.toString())
        assertTrue(taskLines[1].endsWith(": GET 127.0.0.1"), taskLines.toString())
    }

    @Test
    fun `a cancel that comes with another error while sending is a cancel`() {
        val result = CompletableFuture<ExchangeResult>()
        val cancelling = CompletableFuture<Cancellable>()
        val own =
            RequestExecutor(
                clients,
                BackgroundTasks.forTests { line ->
                    // The task starts after preparing, before sending.
                    if ("started" in line) cancelling.get(10, TimeUnit.SECONDS).cancel()
                },
            )

        own.use {
            cancelling.complete(it.run(badHeader(), RequestTags(), result::complete))
            val failure = assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS))

            assertEquals("Cancelled", failure.message)
            assertTrue(failure.cancelled)
        }
    }

    @Test
    fun `a cancel before the request is sent ends it without a response`() {
        val result = CompletableFuture<ExchangeResult>()
        val cancelling = CompletableFuture<Cancellable>()
        val own =
            RequestExecutor(
                clients,
                BackgroundTasks.forTests { line ->
                    if ("started" in line) cancelling.get(10, TimeUnit.SECONDS).cancel()
                },
            )

        own.use {
            cancelling.complete(it.run(server.request("/count"), RequestTags(), result::complete))

            val failure = assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS))
            assertEquals("Cancelled", failure.message)
        }
    }

    @Test
    fun `ends the task as cancelled when the request is cancelled from outside it`() {
        val reached = CountDownLatch(1)
        server.handle("/wait") {
            reached.countDown()
            server.released.await()
        }
        val result = CompletableFuture<ExchangeResult>()

        val running = executor.run(server.request("/wait"), RequestTags(), result::complete)
        assertTrue(reached.await(10, TimeUnit.SECONDS))
        running.cancel()

        assertTrue(assertIs<HttpFailure>(result.get(10, TimeUnit.SECONDS)).cancelled)
        assertEquals(
            listOf("task started: GET 127.0.0.1", "task cancelled after: GET 127.0.0.1"),
            taskLines.map {
                it.replace(Regex(" (after|in) \\d+(\\.\\d+)? s"), " $1")
            },
        )
    }

    @Test
    fun `closes the connection when the deadline passes`() {
        val closed = CountDownLatch(1)
        server.handle("/drip") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                while (!server.released.await(20, TimeUnit.MILLISECONDS)) {
                    exchange.responseBody.write("x".toByteArray())
                    exchange.responseBody.flush()
                }
            } catch (_: IOException) {
                closed.countDown()
            }
        }

        val failure =
            assertIs<HttpFailure>(
                executor
                    .let { it.await(server.request("/drip"), RequestTags(timeout = Duration.ofMillis(300))) },
            )

        assertEquals("No complete response within 300 ms (@timeout)", failure.message)
        assertEquals(
            listOf("task started: GET 127.0.0.1", "task failed after: GET 127.0.0.1: TimeoutException"),
            taskLines.map { it.replace(Regex(" (after|in) \\d+(\\.\\d+)? s"), " $1") },
        )
        assertTrue(closed.await(10, TimeUnit.SECONDS), "the server saw the connection close")
    }

    @Test
    fun `the deadline covers the whole exchange, also the answer to a Digest challenge`() {
        server.handle("/slowdigest") { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") == null) {
                server.released.await(800, TimeUnit.MILLISECONDS)
                server.respond(
                    exchange,
                    401,
                    "",
                    "WWW-Authenticate" to CHALLENGE,
                )
            } else {
                server.released.await()
            }
        }
        val request = digestRequest("/slowdigest")

        val failure = assertIs<HttpFailure>(executor.await(request, RequestTags(timeout = Duration.ofMillis(1000))))

        assertEquals("No complete response within 1 s (@timeout)", failure.message)
        assertTrue(failure.elapsed >= Duration.ofMillis(1000), failure.elapsed.toString())
        assertTrue(failure.elapsed < Duration.ofMillis(1500), failure.elapsed.toString())
    }

    private fun digestRequest(path: String) =
        server.request(path).let {
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

    @Test
    fun `notes why a Digest challenge cannot be answered`() {
        server.handle("/odd") {
            server.respond(it, 401, "", "WWW-Authenticate" to "Digest realm=\"r\", nonce=\"n\", algorithm=FOO")
        }

        val result = assertIs<HttpResult>(executor.await(digestRequest("/odd")))

        assertEquals(401, result.status)
        assertEquals(listOf("The Digest challenge cannot be answered: algorithm FOO is not supported"), result.notes)
    }

    @Test
    fun `answers a Digest challenge without an algorithm as MD5 and sends it once`() {
        val calls = AtomicInteger()
        server.handle("/md5") { exchange ->
            calls.incrementAndGet()
            if (exchange.requestHeaders.getFirst("Authorization") == null) {
                server.respond(exchange, 401, "", "WWW-Authenticate" to CHALLENGE)
            } else {
                server.respond(exchange, 200, "in")
            }
        }

        val result = assertIs<HttpResult>(executor.await(digestRequest("/md5")))

        assertEquals("in", result.text())
        assertEquals(listOf("Answered the Digest challenge (MD5)"), result.notes)
        assertEquals(2, calls.get())
    }

    @Test
    fun `sends no second request without a challenge or without credentials`() {
        val calls = AtomicInteger()
        server.handle("/ok") {
            calls.incrementAndGet()
            server.respond(it, 200, "ok")
        }
        server.handle("/challenge") {
            calls.incrementAndGet()
            server.respond(
                it,
                401,
                "",
                "WWW-Authenticate" to CHALLENGE,
            )
        }

        val ok = assertIs<HttpResult>(executor.await(digestRequest("/ok")))
        val unchallenged = assertIs<HttpResult>(executor.await(server.request("/challenge")))

        assertEquals(200, ok.status)
        assertEquals(401, unchallenged.status)
        assertEquals(2, calls.get())
        assertEquals(emptyList(), ok.notes + unchallenged.notes)
    }

    @Test
    fun `notes an encoding it does not decode and a body that only its decoding makes too long`() {
        server.handle("/br") { server.respond(it, 200, "abc", "Content-Encoding" to "br") }
        server.handle("/bomb") {
            val zipped = ByteArrayOutputStream()
            GZIPOutputStream(zipped).use { it.write(ByteArray(3_000_000)) }
            server.respond(it, 200, zipped.toByteArray(), "Content-Encoding" to "gzip")
        }

        val br = assertIs<HttpResult>(executor.await(server.request("/br")))
        assertEquals(listOf("Content-Encoding br is not decoded"), br.notes)

        RequestExecutor(clients, tasks, bodyLimit = 1024 * 1024).use { limited ->
            val bomb = assertIs<HttpResult>(limited.await(server.request("/bomb")))
            assertTrue(bomb.truncated)
            assertEquals(1024 * 1024, bomb.body.size)
            assertEquals(listOf("The body is longer than 1 MB and was truncated"), bomb.notes)
        }
    }

    @Test
    fun `asks for HTTP 2 only when the request says so`() {
        val plain = assertIs<HttpResult>(executor.await(server.request("/echo"))).text()
        val http2 =
            assertIs<HttpResult>(
                executor.await(
                    server.request("/echo").let {
                        PreparedRequest(it.uri, it.method, HttpVersion.HTTP_2, it.headers, it.body, it.notes)
                    },
                ),
            ).text()

        assertFalse("upgrade: h2c" in plain, plain)
        assertTrue("upgrade: h2c" in http2, http2)
    }

    @Test
    fun `gives results and failures a plausible elapsed time`() {
        val result = assertIs<HttpResult>(executor.await(server.request("/echo")))
        val failure = assertIs<HttpFailure>(executor.await(badHeader()))

        assertTrue(result.elapsed > Duration.ZERO && result.elapsed < Duration.ofMinutes(1), result.elapsed.toString())
        assertTrue(failure.elapsed >= Duration.ZERO, failure.elapsed.toString())
        assertTrue(failure.elapsed < Duration.ofMinutes(1), failure.elapsed.toString())
    }

    @Test
    fun `an error no step expected gives a result and is logged as a failure of the request`() {
        val result = CompletableFuture<ExchangeResult>()
        val lines =
            logged {
                executor.run(RequestTags(), result::complete) { throw StackOverflowError("deep") }
                result.get(10, TimeUnit.SECONDS)
            }

        assertEquals(listOf("SEVERE A request not yet prepared failed unexpectedly"), lines)
    }

    @Test
    fun `an unexpected error after a cancel is only a cancel`() {
        val preparing = CountDownLatch(1)
        val result = CompletableFuture<ExchangeResult>()
        val lines =
            logged {
                val running =
                    executor.run(RequestTags(), result::complete) {
                        preparing.countDown()
                        try {
                            CountDownLatch(1).await()
                        } catch (_: InterruptedException) {
                            throw StackOverflowError("deep")
                        }
                        server.request("/echo")
                    }
                assertTrue(preparing.await(10, TimeUnit.SECONDS))
                running.cancel()
                result.get(10, TimeUnit.SECONDS)
            }

        assertEquals("Cancelled", assertIs<HttpFailure>(result.get()).message)
        assertEquals(emptyList(), lines)
    }

    @Test
    fun `limits connecting by the connection timeout of the request, not by the deadline`() {
        // A listener whose backlog is full drops further connection attempts on Linux, so that connecting hangs.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val fillers = mutableListOf<Socket>()
            try {
                var hanging = false
                repeat(10) {
                    if (!hanging) {
                        val socket = Socket()
                        try {
                            socket.connect(listener.localSocketAddress, 300)
                            fillers += socket
                        } catch (_: SocketTimeoutException) {
                            hanging = true
                        } catch (_: ConnectException) {
                            // Refused: this stack does not queue connections (may be the case on BSD stacks too).
                            assumeTrue(false, "this system refuses connections to a full backlog")
                        }
                    }
                }
                assumeTrue(hanging, "this system does not hang connection attempts to a full backlog")

                val request =
                    PreparedRequest(
                        URI("http://127.0.0.1:${listener.localPort}/"),
                        "GET",
                        HttpVersion.HTTP_1_1,
                        emptyList(),
                        ByteArray(0),
                        emptyList(),
                    )
                val failure =
                    assertIs<HttpFailure>(
                        executor.await(
                            request,
                            RequestTags(timeout = Duration.ofSeconds(8), connectionTimeout = Duration.ofMillis(400)),
                        ),
                    )

                assertEquals("Connection timed out after 400 ms (@connection-timeout)", failure.message)
            } finally {
                fillers.forEach { it.close() }
            }
        }
    }

    @Test
    fun `describes a connection that closes without an answer by the error of the exchange, not its wrapper`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val closer = Thread { repeat(2) { listener.accept().close() } }.apply { isDaemon = true }
            closer.start()
            val request =
                PreparedRequest(
                    URI("http://127.0.0.1:${listener.localPort}/"),
                    "GET",
                    HttpVersion.HTTP_1_1,
                    emptyList(),
                    ByteArray(0),
                    emptyList(),
                )

            val failure = assertIs<HttpFailure>(executor.await(request))

            assertTrue(failure.message.startsWith("IOException: "), failure.message)
            assertFalse("ExecutionException" in failure.message, failure.message)
        }
    }

    @Test
    fun `maps the versions of the JDK client to the two of the file format`() {
        assertEquals(HttpVersion.HTTP_2, RequestExecutor.versionOf(java.net.http.HttpClient.Version.HTTP_2))
        assertEquals(HttpVersion.HTTP_1_1, RequestExecutor.versionOf(java.net.http.HttpClient.Version.HTTP_1_1))
    }

    @Test
    fun `a failure to register the task is logged with the method and host only`() {
        val throwing =
            RequestExecutor(
                clients,
                BackgroundTasks.forTests { if ("started" in it) error("secret-token") },
            )
        val result = CompletableFuture<ExchangeResult>()

        val lines =
            logged {
                throwing.use {
                    it.run(server.request("/echo?q=secret-token"), RequestTags(), result::complete)
                    result.get(10, TimeUnit.SECONDS)
                }
            }

        assertEquals(listOf("SEVERE GET 127.0.0.1 failed unexpectedly"), lines)
        val failure = assertIs<HttpFailure>(result.get())
        assertTrue(failure.message.startsWith("Unexpected error: "), failure.message)
        assertEquals("/echo", failure.request?.uri?.path)
    }

    @Test
    fun `names an error without a class name by an empty text`() {
        val anonymous = object : RuntimeException() {}

        assertEquals("", failing { anonymous }.message)
    }

    private companion object {
        const val CHALLENGE = "Digest realm=\"r\", qop=\"auth\", nonce=\"n\""
    }
}
