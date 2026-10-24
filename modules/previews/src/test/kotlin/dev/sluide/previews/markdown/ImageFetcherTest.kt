package dev.sluide.previews.markdown

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageFetcherTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private var started = false
    private val bytes = ByteArray(100) { it.toByte() }

    private val release = CountDownLatch(1)

    @AfterTest
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    private fun serve(
        path: String,
        status: Int = 200,
        type: String? = null,
        body: ByteArray = bytes,
        location: String? = null,
    ) {
        server.createContext(path) { exchange: HttpExchange ->
            type?.let { exchange.responseHeaders.add("Content-Type", it) }
            location?.let { exchange.responseHeaders.add("Location", it) }
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
    }

    private fun fetch(
        path: String,
        maxBytes: Long = 1000,
        timeout: Duration = Duration.ofSeconds(30),
    ): FetchedImage? {
        if (!started) server.start()
        started = true
        val uri = URI("http://127.0.0.1:${server.address.port}$path")
        return HttpImageFetcher(maxBytes, timeout).fetch(uri)
    }

    @Test
    fun `an image is returned with its type and bytes`() {
        serve("/a", type = "image/png; charset=x")
        val image = fetch("/a")
        assertEquals("image/png", image?.type)
        assertContentEquals(bytes, image?.bytes)
    }

    @Test
    fun `other status codes and other types give no image`() {
        serve("/missing", status = 404, type = "image/png")
        serve("/page", type = "text/html")
        assertNull(fetch("/missing"))
        assertNull(fetch("/page"))
    }

    @Test
    fun `without a content type the extension tells the type`() {
        serve("/b.png")
        assertEquals("image/png", fetch("/b.png")?.type)
    }

    @Test
    fun `an image over the limit gives none`() {
        serve("/big", type = "image/png")
        assertNull(fetch("/big", maxBytes = 99))
    }

    @Test
    fun `redirects are followed`() {
        serve("/go", status = 302, location = "/a", body = ByteArray(0))
        serve("/a", type = "image/gif")
        assertEquals("image/gif", fetch("/go")?.type)
    }

    @Test
    fun `an explicit type other than an image is not replaced by the extension`() {
        serve("/x.png", type = "text/html")
        serve("/y.png", type = "application/octet-stream")
        serve("/z.png", type = "image/<bad>")
        assertNull(fetch("/x.png"))
        assertEquals("image/png", fetch("/y.png")?.type)
        assertNull(fetch("/z.png"))
    }

    @Test
    fun `a streamed body over the limit gives none`() {
        server.createContext("/stream") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, 0)
            runCatching { exchange.responseBody.use { it.write(ByteArray(5000)) } }
            exchange.close()
        }
        assertNull(fetch("/stream", maxBytes = 1000))
    }

    @Test
    fun `other schemes give none`() {
        val fetcher = HttpImageFetcher()
        assertNull(fetcher.fetch(URI("file:///etc/hostname")))
        assertNull(fetcher.fetch(URI("ftp://example.test/a.png")))
    }

    @Test
    fun `a body that stalls after the headers gives none within the timeout`() {
        server.createContext("/stall") { exchange ->
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write(1)
            exchange.responseBody.flush()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        val start = System.nanoTime()
        assertNull(fetch("/stall", timeout = Duration.ofMillis(500)))
        assertTrue(Duration.ofNanos(System.nanoTime() - start) < Duration.ofSeconds(4))
    }
}
