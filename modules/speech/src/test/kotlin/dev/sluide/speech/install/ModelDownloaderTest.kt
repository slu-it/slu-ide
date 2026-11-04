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
import java.util.HexFormat
import java.util.concurrent.CancellationException
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
    private val model = ByteArray(300_000) { (it % 251).toByte() }
    private val voices = ByteArray(40_000) { (it % 13).toByte() }
    private val served = HashMap<String, ByteArray>()

    private fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private val files = listOf(ModelFile("m.onnx", 300_000, sha(model)), ModelFile("v.bin", 40_000, sha(voices)))
    private val store by lazy { ModelStore(dir.resolve("store")) }
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val base by lazy { URI("http://127.0.0.1:${server.address.port}/") }
    private val downloader by lazy { ModelDownloader(store, http, base) { files } }

    init {
        served["m.onnx"] = model
        served["v.bin"] = voices
        server.createContext("/") { exchange ->
            val name = exchange.requestURI.path.removePrefix("/")
            if (name == "redirected/m.onnx") {
                exchange.responseHeaders.add("Location", "/m.onnx")
                exchange.sendResponseHeaders(302, -1)
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
    fun stop() = server.stop(0)

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
    fun `redirects are followed`() {
        val redirecting =
            ModelDownloader(store, http, base) { listOf(ModelFile("redirected/m.onnx", 300_000, sha(model))) }
        Files.createDirectories(store.root.resolve("redirected"))
        redirecting.download(KokoroVariant.FP32, {}, { false })
        assertTrue(store.root.resolve("redirected/m.onnx").exists())
    }

    @Test
    fun `a leftover part file is overwritten and a file already there is not fetched again`() {
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
        assertFalse(store.root.resolve("m.onnx.part").exists())
    }
}
