package dev.sluide.todos.markdown

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HttpImageFetcherTest {
    private lateinit var server: HttpServer
    private val release = CountDownLatch(1)

    @Volatile private var userAgent: String? = null

    private fun uri(path: String) = URI("http://127.0.0.1:${server.address.port}$path")

    private fun reply(
        exchange: HttpExchange,
        status: Int,
        body: ByteArray,
    ) {
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) } else exchange.close()
    }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/body") {
            userAgent = it.requestHeaders.getFirst("User-Agent")
            reply(it, 200, ByteArray(100) { i -> i.toByte() })
        }
        server.createContext("/moved") {
            it.responseHeaders.add("Location", "/body")
            reply(it, 302, ByteArray(0))
        }
        server.createContext("/missing") { reply(it, 404, "nope".toByteArray()) }
        server.createContext("/hang") {
            release.await()
            it.close()
        }
        server.createContext("/stall") {
            it.sendResponseHeaders(200, 0)
            it.responseBody.write(ByteArray(10))
            it.responseBody.flush()
            release.await()
            it.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    @Test
    fun `a normal body is returned whole with the user agent`() {
        assertContentEquals(ByteArray(100) { it.toByte() }, HttpImageFetcher().fetch(uri("/body")))
        assertEquals("Thoreau", userAgent)
    }

    @Test
    fun `a body over the limit is cut at the limit plus one`() {
        assertEquals(11, HttpImageFetcher(limit = 10).fetch(uri("/body"))?.size)
    }

    @Test
    fun `a body of exactly the limit is not cut`() {
        assertEquals(100, HttpImageFetcher(limit = 100).fetch(uri("/body"))?.size)
    }

    @Test
    fun `a redirect is followed`() {
        assertEquals(100, HttpImageFetcher().fetch(uri("/moved"))?.size)
    }

    @Test
    fun `a 404 is a failure`() {
        assertNull(HttpImageFetcher().fetch(uri("/missing")))
    }

    @Test
    fun `a server that never answers fails within the timeout`() {
        val start = System.nanoTime()
        assertNull(HttpImageFetcher(timeout = 300.milliseconds).fetch(uri("/hang")))
        assertTrue(System.nanoTime() - start < 5.seconds.inWholeNanoseconds)
    }

    @Test
    fun `a body that stalls after its headers fails within the timeout`() {
        val start = System.nanoTime()
        // Preemptive, so that a fetch without a deadline over the body fails the test instead of hanging it.
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertNull(HttpImageFetcher(timeout = 500.milliseconds).fetch(uri("/stall")))
        }
        assertTrue(System.nanoTime() - start < 3.seconds.inWholeNanoseconds, "the deadline covers the body")
    }

    @Test
    fun `a refused connection is a failure`() {
        val dead = uri("/body")
        server.stop(0)
        assertNull(HttpImageFetcher().fetch(dead))
    }
}
