package dev.sluide.speech.install

import com.sun.net.httpserver.HttpServer
import dev.sluide.speech.engine.SpeechException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
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

class ModelDownloaderTest {
    @TempDir
    lateinit var dir: Path

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val pool = Executors.newCachedThreadPool()
    private val model = ByteArray(300_000) { (it % 251).toByte() }
    private val other = ByteArray(200_000) { (it % 241).toByte() }
    private val voices = ByteArray(40_000) { (it % 13).toByte() }
    private val served = HashMap<String, ByteArray>()
    private val release = CountDownLatch(1)

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private val modelFile = ModelFile("m.onnx", 300_000, sha(model))
    private val voicesFile = ModelFile("v.bin", 40_000, sha(voices))
    private val files = listOf(modelFile, voicesFile)
    private val store by lazy { ModelStore(dir.resolve("store")) }
    private val http = ModelDownloader.defaultHttpClient()
    private val base by lazy { URI("http://127.0.0.1:${server.address.port}/") }
    private val downloader by lazy { ModelDownloader(store, http, base) { files } }

    init {
        served["m.onnx"] = model
        served["o.onnx"] = other
        served["v.bin"] = voices
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
    }

    private fun run(
        progress: MutableList<Double> = mutableListOf(),
        cancelled: () -> Boolean = { false },
    ) = downloader.download(KokoroVariant.FP32, { progress += it }, cancelled)

    private fun entries() = if (store.root.exists()) store.root.listDirectoryEntries() else emptyList()

    @Test
    fun `downloads both files, moves them in place and reports growing progress up to 1`() {
        val progress = mutableListOf<Double>()
        run(progress)

        assertEquals(
            model.toList(),
            store.root
                .resolve("m.onnx")
                .readBytes()
                .toList(),
        )
        assertEquals(
            voices.toList(),
            store.root
                .resolve("v.bin")
                .readBytes()
                .toList(),
        )
        assertEquals(2, entries().size, "no part files left")
        assertEquals(progress.sorted(), progress)
        assertEquals(1.0, progress.last())
        assertEquals(progress.distinct(), progress, "at most once per percent")
    }

    @Test
    fun `a checksum mismatch deletes the part file and fails readably`() {
        served["m.onnx"] = ByteArray(300_000)
        val e = assertFailsWith<SpeechException> { run() }
        assertEquals("Download of m.onnx failed: checksum mismatch", e.message)
        assertEquals(emptyList(), entries())
    }

    @Test
    fun `a missing file fails with the status`() {
        served.remove("m.onnx")
        val e = assertFailsWith<SpeechException> { run() }
        assertTrue(e.message!!.contains("404"), e.message)
    }

    @Test
    fun `cancelling deletes the part file and leaves no target`() {
        var checks = 0
        assertFailsWith<CancellationException> { run(cancelled = { ++checks > 2 }) }
        assertEquals(emptyList(), entries())
    }

    @Test
    fun `redirects are followed by the default client`() {
        val redirecting =
            ModelDownloader(store, http, base) { listOf(ModelFile("redirected/m.onnx", 300_000, sha(model))) }
        Files.createDirectories(store.root.resolve("redirected"))
        redirecting.download(KokoroVariant.FP32, {}, { false })
        assertTrue(store.root.resolve("redirected/m.onnx").exists())
    }

    @Test
    fun `a stale part file stays and a file already there is not fetched again`() {
        Files.createDirectories(store.root)
        store.root.resolve("m.onnx.part").writeBytes(ByteArray(7))
        store.root.resolve("v.bin").writeBytes(voices)
        served.remove("v.bin")

        run()

        assertEquals(
            model.toList(),
            store.root
                .resolve("m.onnx")
                .readBytes()
                .toList(),
        )
    }

    @Test
    fun `two variants that both need the voices file download side by side and cancelling one leaves the other`() {
        val otherFile = ModelFile("o.onnx", 200_000, sha(other))
        val perVariant = { v: KokoroVariant ->
            listOf(
                if (v ==
                    KokoroVariant.FP32
                ) {
                    modelFile
                } else {
                    otherFile
                },
                voicesFile,
            )
        }
        val both = ModelDownloader(store, http, base, Duration.ofSeconds(30)) { perVariant(it) }
        // Both must have asked for the voices file before either gets it: their downloads overlap.
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
        val first =
            pool.submit {
                both.download(KokoroVariant.FP32, {}, { cancelFirst.get() })
            }
        val second = pool.submit { both.download(KokoroVariant.FP16, {}, { false }) }
        // The first is cancelled once the voices requests are both there.
        assertTrue(bothAsked.await(10, TimeUnit.SECONDS))
        cancelFirst.set(true)

        second.get(10, TimeUnit.SECONDS)
        runCatching { first.get(10, TimeUnit.SECONDS) }
        assertEquals(
            voices.toList(),
            store.root
                .resolve("v.bin")
                .readBytes()
                .toList(),
        )
        assertEquals(
            other.toList(),
            store.root
                .resolve("o.onnx")
                .readBytes()
                .toList(),
        )
        assertEquals(emptyList(), store.root.listDirectoryEntries("*.part"))
    }

    @Test
    fun `cancelling returns promptly although the server sends nothing more`() {
        val stalling =
            ModelDownloader(
                store,
                http,
                base,
                Duration.ofSeconds(30),
            ) { listOf(ModelFile("stalls.onnx", 300_000, sha(model))) }
        val half = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val result =
            pool.submit {
                stalling.download(KokoroVariant.FP32, { if (it > 0.4) half.countDown() }, cancelled::get)
            }
        assertTrue(half.await(10, TimeUnit.SECONDS), "half of the file arrived")

        cancelled.set(true)

        val error = assertFailsWith<java.util.concurrent.ExecutionException> { result.get(5, TimeUnit.SECONDS) }
        assertTrue(error.cause is CancellationException, error.cause.toString())
        assertEquals(emptyList(), entries())
    }

    @Test
    fun `a download that gets no bytes for the stall time fails as stalled`() {
        val stalling =
            ModelDownloader(
                store,
                http,
                base,
                Duration.ofMillis(400),
            ) { listOf(ModelFile("stalls.onnx", 300_000, sha(model))) }
        val e = assertFailsWith<SpeechException> { stalling.download(KokoroVariant.FP32, {}, { false }) }
        assertEquals("Download of stalls.onnx stalled", e.message)
        assertEquals(emptyList(), entries())
        assertFalse(Thread.currentThread().isInterrupted, "the watchdog's interrupt does not leak")
    }
}
