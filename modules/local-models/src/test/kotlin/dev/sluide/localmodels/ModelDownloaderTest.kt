package dev.sluide.localmodels

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ModelDownloaderTest {
    @TempDir
    lateinit var dir: Path

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val pool = Executors.newCachedThreadPool()
    private val model = ByteArray(300_000) { (it % 251).toByte() }
    private val other = ByteArray(200_000) { (it % 241).toByte() }
    private val shared = ByteArray(40_000) { (it % 13).toByte() }
    private val served = HashMap<String, ByteArray>()
    private val release = CountDownLatch(1)

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun file(
        path: String,
        bytes: ByteArray,
        served: String = path,
    ) = ModelFile(path, URI("http://127.0.0.1:${server.address.port}/$served"), bytes.size.toLong(), sha(bytes))

    private val modelFile by lazy { file("m.onnx", model) }
    private val sharedFile by lazy { file("v.bin", shared) }
    private val folder by lazy { dir.resolve("store") }
    private val http = ModelDownloader.defaultHttpClient()
    private val downloader = ModelDownloader(http)

    init {
        served["m.onnx"] = model
        served["o.onnx"] = other
        served["v.bin"] = shared
        server.executor = pool
        server.createContext("/") { exchange ->
            val name = exchange.requestURI.path.removePrefix("/")
            if (name == "redirected/m.onnx") {
                exchange.responseHeaders.add("Location", "/m.onnx")
                exchange.sendResponseHeaders(302, -1)
            } else if (name == "stalls.onnx") {
                // Headers and half of the body, then nothing until the test ends.
                exchange.sendResponseHeaders(200, model.size.toLong())
                exchange.responseBody.write(model, 0, model.size / 2)
                exchange.responseBody.flush()
                release.await(30, TimeUnit.SECONDS)
            } else {
                val body = served[name]
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        release.countDown()
        server.stop(0)
        pool.shutdownNow()
        http.shutdownNow()
    }

    private fun run(
        progress: MutableList<Double> = mutableListOf(),
        cancelled: () -> Boolean = { false },
    ) = downloader.download(listOf(modelFile, sharedFile), folder, { progress += it }, cancelled)

    private fun entries() = if (folder.exists()) folder.listDirectoryEntries() else emptyList()

    private fun bytesOf(path: String) = folder.resolve(path).readBytes().toList()

    @Test
    fun `downloads both files, moves them in place and reports growing progress up to 1`() {
        val progress = mutableListOf<Double>()
        run(progress)

        assertEquals(model.toList(), bytesOf("m.onnx"))
        assertEquals(shared.toList(), bytesOf("v.bin"))
        assertEquals(2, entries().size, "no part files left")
        assertEquals(progress.sorted(), progress)
        assertEquals(1.0, progress.last())
        assertEquals(progress.distinct(), progress, "at most once per percent")
    }

    @Test
    fun `a checksum mismatch deletes the part file and fails readably`() {
        served["m.onnx"] = ByteArray(300_000)
        val e = assertFailsWith<ModelDownloadException> { run() }
        assertEquals("Download of m.onnx failed: checksum mismatch", e.message)
        assertEquals(emptyList(), entries())
    }

    @Test
    fun `a missing file fails with the status`() {
        served.remove("m.onnx")
        val e = assertFailsWith<ModelDownloadException> { run() }
        assertEquals("Download of m.onnx failed: the server answered 404", e.message)
    }

    @Test
    fun `a server that cannot be reached is named by its host`() {
        val port = server.address.port
        server.stop(0)
        val unreachable = ModelFile("m.onnx", URI("http://127.0.0.1:$port/m.onnx"), 300_000, sha(model))
        val e =
            assertFailsWith<ModelDownloadException> { downloader.download(listOf(unreachable), folder, {}, { false }) }
        assertEquals("Download of m.onnx failed: cannot reach 127.0.0.1", e.message)
    }

    @Test
    fun `cancelling deletes the part file and leaves no target`() {
        var checks = 0
        assertFailsWith<CancellationException> { run(cancelled = { ++checks > 2 }) }
        assertEquals(emptyList(), entries())
    }

    @Test
    fun `redirects are followed by the default client and a path may name a subfolder`() {
        downloader.download(listOf(file("redirected/m.onnx", model)), folder, {}, { false })
        assertEquals(model.toList(), bytesOf("redirected/m.onnx"))
        assertEquals(emptyList(), folder.resolve("redirected").listDirectoryEntries("*.part"))
    }

    @Test
    fun `a stale part file stays and a file already there is not fetched again`() {
        Files.createDirectories(folder)
        folder.resolve("m.onnx.part").writeBytes(ByteArray(7))
        folder.resolve("v.bin").writeBytes(shared)
        served.remove("v.bin")
        val progress = mutableListOf<Double>()

        run(progress)

        assertEquals(model.toList(), bytesOf("m.onnx"))
        assertTrue(folder.resolve("m.onnx.part").exists())
        assertEquals(1.0, progress.last(), "progress runs over the missing bytes only")
    }

    @ParameterizedTest
    @ValueSource(strings = ["../m.onnx", "/m.onnx", "", "."])
    fun `a path that leaves the folder or names the folder itself is refused before anything is fetched`(path: String) {
        assertFailsWith<IllegalArgumentException> {
            downloader.download(listOf(file(path, model, "m.onnx")), folder, {}, { false })
        }
        assertFalse(dir.resolve("m.onnx").exists())
        assertFalse(folder.exists())
    }

    @Test
    fun `two downloads that both need a shared file run side by side and cancelling one leaves the other`() {
        val otherFile = file("o.onnx", other)
        // Both must have asked for the shared file before either gets it: their downloads overlap.
        val barrier = CyclicBarrier(2)
        val bothAsked = CountDownLatch(1)
        server.removeContext("/")
        server.createContext("/") { exchange ->
            val name = exchange.requestURI.path.removePrefix("/")
            if (name == "v.bin") {
                barrier.await(10, TimeUnit.SECONDS)
                bothAsked.countDown()
            }
            val body = checkNotNull(served[name])
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        val cancelFirst = AtomicBoolean()
        val first = pool.submit { downloader.download(listOf(modelFile, sharedFile), folder, {}, cancelFirst::get) }
        val second = pool.submit { downloader.download(listOf(otherFile, sharedFile), folder, {}, { false }) }
        // The first is cancelled once the requests for the shared file are both there.
        assertTrue(bothAsked.await(10, TimeUnit.SECONDS))
        cancelFirst.set(true)

        second.get(10, TimeUnit.SECONDS)
        runCatching { first.get(10, TimeUnit.SECONDS) }
        assertEquals(shared.toList(), bytesOf("v.bin"))
        assertEquals(other.toList(), bytesOf("o.onnx"))
        assertEquals(emptyList(), folder.listDirectoryEntries("*.part"))
    }

    @Test
    fun `cancelling returns promptly although the server sends nothing more`() {
        val half = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val result =
            pool.submit {
                downloader.download(
                    listOf(file("stalls.onnx", model)),
                    folder,
                    { if (it > 0.4) half.countDown() },
                    cancelled::get,
                )
            }
        assertTrue(half.await(10, TimeUnit.SECONDS), "half of the file arrived")

        cancelled.set(true)

        val error = assertFailsWith<ExecutionException> { result.get(5, TimeUnit.SECONDS) }
        assertTrue(error.cause is CancellationException, error.cause.toString())
        assertEquals(emptyList(), entries())
    }

    @Test
    fun `a download that gets no bytes for the stall time fails as stalled`() {
        val stalling = ModelDownloader(http, 400.milliseconds)
        val e =
            assertFailsWith<ModelDownloadException> {
                stalling.download(listOf(file("stalls.onnx", model)), folder, {}, { false })
            }
        assertEquals("Download of stalls.onnx stalled", e.message)
        assertEquals(emptyList(), entries())
        assertFalse(Thread.currentThread().isInterrupted, "the watchdog's interrupt does not leak")
    }

    @Test
    fun `closing shuts down only a client the downloader created`() {
        ModelDownloader(http).close()
        downloader.download(listOf(modelFile), folder, {}, { false })
        assertTrue(folder.resolve("m.onnx").exists(), "the caller's client still works")

        val owning = ModelDownloader()
        owning.download(listOf(sharedFile), folder, {}, { false })
        owning.close()
        Files.delete(folder.resolve("v.bin"))
        assertFailsWith<ModelDownloadException> { owning.download(listOf(sharedFile), folder, {}, { false }) }
        assertFalse(folder.resolve("v.bin").exists(), "its own client is shut down")
    }
}
