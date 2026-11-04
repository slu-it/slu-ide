package dev.sluide.speech.install

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.speech.engine.SpeechException
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

private val log = System.getLogger(ModelDownloader::class.java.name)

private const val BUFFER_SIZE = 64 * 1024

/** The release the files are published in. */
private const val RELEASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/"

private val STALL_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * Downloads the Kokoro model files into a [ModelStore], only when asked to (never on startup). Blocking; call it off
 * the FX thread. Only file names are logged: the redirect URLs of GitHub carry signatures.
 *
 * Every download writes its own part file, so several downloads (two variants share the voices file) never touch each
 * other's data. A watchdog stops a download that gets no bytes for [stallTimeout], and one that is cancelled while it
 * waits for the server.
 */
class ModelDownloader internal constructor(
    private val store: ModelStore,
    private val http: HttpClient,
    private val baseUri: URI,
    private val stallTimeout: Duration = STALL_TIMEOUT,
    private val files: (KokoroVariant) -> List<ModelFile>,
) {
    constructor(store: ModelStore, http: HttpClient, baseUri: URI = URI(RELEASE)) :
        this(store, http, baseUri, STALL_TIMEOUT, ::kokoroFiles)

    /**
     * Downloads what [v] is missing (the voices file too). [progress] gets 0..1 over all bytes, on the calling thread,
     * at most every 1 %.
     *
     * [cancelled] is also called from the stall watchdog's thread, so it must be thread-safe.
     *
     * @throws CancellationException when [cancelled] returns true; nothing half-written is left.
     * @throws SpeechException on a network or checksum failure, or a stall; its message is readable.
     */
    fun download(
        v: KokoroVariant,
        progress: (Double) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val wanted = files(v).filter { !hasSize(store.root.resolve(it.name), it.bytes) }
        val total = wanted.sumOf { it.bytes }
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
        for (file in wanted) {
            // Another download may have fetched it meanwhile (the voices file).
            if (hasSize(
                    store.root.resolve(file.name),
                    file.bytes,
                )
            ) {
                report(file.bytes)
            } else {
                fetch(file, cancelled, ::report)
            }
        }
    }

    private fun fetch(
        file: ModelFile,
        cancelled: () -> Boolean,
        read: (Long) -> Unit,
    ) {
        val target = store.root.resolve(file.name)
        var part: Path? = null
        val watchdog = Watchdog(cancelled, Thread.currentThread(), stallTimeout)
        try {
            Files.createDirectories(store.root)
            part = Files.createTempFile(store.root, file.name.substringAfterLast('/') + "-", ".part")
            log.log(System.Logger.Level.INFO, "Downloading ${file.name}")
            val request =
                HttpRequest
                    .newBuilder(baseUri.resolve(file.name))
                    .timeout(stallTimeout)
                    .GET()
                    .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            watchdog.touch()
            response.body().use { body ->
                watchdog.body = body
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
                        watchdog.touch()
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
            moveIntoPlace(part, target, file)
        } catch (e: IOException) {
            throw failure(file, e, cancelled, watchdog)
        } catch (e: InterruptedException) {
            throw failure(file, e, cancelled, watchdog)
        } finally {
            watchdog.close()
            part?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (e: IOException) {
                    log.log(System.Logger.Level.WARNING, "Cannot delete ${it.fileName}", e)
                }
            }
        }
    }

    /** The verified [part] replaces [target]; if that fails but another download put the same file there, fine. */
    private fun moveIntoPlace(
        part: Path,
        target: Path,
        file: ModelFile,
    ) {
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            if (!hasSize(target, file.bytes)) throw e
        }
    }

    private fun failure(
        file: ModelFile,
        e: Exception,
        cancelled: () -> Boolean,
        watchdog: Watchdog,
    ): Exception =
        when {
            cancelled() -> {
                CancellationException("Download of ${file.name} cancelled")
            }

            watchdog.stalled || (e is HttpTimeoutException && e !is HttpConnectTimeoutException) -> {
                SpeechException("Download of ${file.name} stalled")
            }

            e is InterruptedException -> {
                Thread.currentThread().interrupt()
                CancellationException("Download of ${file.name} interrupted")
            }

            else -> {
                SpeechException("Download of ${file.name} failed: ${describe(e)}", e)
            }
        }

    private fun describe(e: Exception): String =
        when (e) {
            is ConnectException, is UnknownHostException, is HttpConnectTimeoutException -> {
                "cannot reach ${baseUri.host}"
            }

            else -> {
                e.message ?: e.javaClass.simpleName
            }
        }

    private fun hasSize(
        file: Path,
        bytes: Long,
    ) = Files.isRegularFile(file) && Files.size(file) == bytes

    /**
     * Ends a read that blocks: when the download is cancelled or has had no bytes for [stall], it closes the body and
     * interrupts the reading thread.
     */
    private class Watchdog(
        private val cancelled: () -> Boolean,
        private val reader: Thread,
        private val stall: Duration,
    ) : AutoCloseable {
        @Volatile private var last = System.nanoTime()

        @Volatile var stalled = false
            private set

        @Volatile var body: InputStream? = null

        private var aborted = false
        private var done = false
        private val scheduler = NamedThreads.scheduled("kokoro-download-watchdog")

        init {
            val tick = (stall.toMillis() / 4).coerceIn(10, 250)
            scheduler.scheduleWithFixedDelay(::check, tick, tick, TimeUnit.MILLISECONDS)
        }

        fun touch() {
            last = System.nanoTime()
        }

        private fun check() {
            val idle = System.nanoTime() - last > stall.toNanos()
            val cancel = cancelled()
            synchronized(this) {
                if (done || aborted || !(idle || cancel)) return
                aborted = true
                stalled = idle && !cancel
            }
            // Outside the lock: closing may wait for the thread that is reading.
            try {
                body?.close()
            } catch (_: IOException) {
                // Closing is only to end the read.
            }
            synchronized(this) { if (!done) reader.interrupt() }
        }

        override fun close() {
            synchronized(this) {
                done = true
                scheduler.shutdownNow()
                // The interrupt was meant for the read, not for what the caller does next.
                if (aborted) Thread.interrupted()
            }
        }
    }

    companion object {
        /** The client every download uses: follows redirects (GitHub sends the files from its asset host). */
        fun defaultHttpClient(): HttpClient =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build()
    }
}
