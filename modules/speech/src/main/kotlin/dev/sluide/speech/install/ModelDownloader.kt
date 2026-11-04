package dev.sluide.speech.install

import dev.sluide.speech.engine.SpeechException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CancellationException

private val log = System.getLogger(ModelDownloader::class.java.name)

private const val BUFFER_SIZE = 64 * 1024

/** The release the files are published in. */
private const val RELEASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/"

/**
 * Downloads the Kokoro model files into a [ModelStore], only when asked to (never on startup). Blocking; call it off
 * the FX thread. Only file names are logged: the redirect URLs of GitHub carry signatures.
 */
class ModelDownloader internal constructor(
    private val store: ModelStore,
    private val http: HttpClient,
    private val baseUri: URI,
    private val files: (KokoroVariant) -> List<ModelFile>,
) {
    constructor(store: ModelStore, http: HttpClient, baseUri: URI = URI(RELEASE)) :
        this(store, http, baseUri, ::kokoroFiles)

    /**
     * Downloads what [v] is missing (the voices file too). [progress] gets 0..1 over all bytes, on the calling thread,
     * at most every 1 %.
     *
     * @throws CancellationException when [cancelled] returns true; nothing half-written is left.
     * @throws SpeechException on a network or checksum failure; its message is readable.
     */
    fun download(
        v: KokoroVariant,
        progress: (Double) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val missing = files(v).filter { !hasSize(store.root.resolve(it.name), it.bytes) }
        val total = missing.sumOf { it.bytes }
        var done = 0L
        var lastPercent = -1

        fun report(read: Long) {
            done += read
            val percent = if (total == 0L) 100 else (done * 100 / total).toInt()
            if (percent > lastPercent) {
                lastPercent = percent
                progress(percent / 100.0)
            }
        }
        report(0)
        try {
            for (file in missing) fetch(file, cancelled, ::report)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Download interrupted")
        }
    }

    private fun fetch(
        file: ModelFile,
        cancelled: () -> Boolean,
        read: (Long) -> Unit,
    ) {
        val target = store.root.resolve(file.name)
        val part = store.root.resolve(file.name + ".part")
        try {
            Files.createDirectories(store.root)
            log.log(System.Logger.Level.INFO, "Downloading ${file.name}")
            val request = HttpRequest.newBuilder(baseUri.resolve(file.name)).GET().build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { body ->
                if (response.statusCode() != 200) {
                    throw SpeechException(
                        "Download of ${file.name} failed: the server answered ${response.statusCode()}",
                    )
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                Files.newOutputStream(part).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelled()) throw CancellationException("Download of ${file.name} cancelled")
                        val n = body.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        written += n
                        read(n.toLong())
                    }
                }
                if (written != file.bytes || HexFormat.of().formatHex(digest.digest()) != file.sha256) {
                    throw SpeechException("Download of ${file.name} failed: checksum mismatch")
                }
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            throw SpeechException("Download of ${file.name} failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            Files.deleteIfExists(part)
        }
    }

    private fun hasSize(
        file: Path,
        bytes: Long,
    ) = Files.isRegularFile(file) && Files.size(file) == bytes
}
