package dev.sluide.localmodels

import dev.sluide.backgroundtasks.NamedThreads
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
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
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val log = System.getLogger(ModelDownloader::class.java.name)

private const val BUFFER_SIZE = 64 * 1024

private val STALL_TIMEOUT = 30.seconds

/**
 * Downloads model files, only when asked to (never on startup, ADR 0125). Blocking; call it off the FX thread. Only
 * file paths are logged, never URLs: redirect URLs (GitHub's, Hugging Face's) carry signatures.
 *
 * Every download writes its own part file next to its target, so several downloads that share a file never touch each
 * other's data. A watchdog stops a download that gets no bytes for the stall timeout, and one that is cancelled while
 * it waits for the server.
 *
 * Open so that tests in other modules can fake the download.
 */
open class ModelDownloader private constructor(
    private val client: Lazy<HttpClient>,
    private val owned: Boolean,
    private val stallTimeout: Duration,
) : AutoCloseable {
    /** Downloads with a [defaultHttpClient], created with the first download and shut down by [close]. */
    constructor(stallTimeout: Duration = STALL_TIMEOUT) : this(lazy { defaultHttpClient() }, true, stallTimeout)

    /** Downloads with [http], which the caller owns. */
    constructor(http: HttpClient, stallTimeout: Duration = STALL_TIMEOUT) : this(lazyOf(http), false, stallTimeout)

    /**
     * Downloads the [files] that are not in [folder] with their size yet. [progress] gets 0..1 over the missing bytes,
     * on the calling thread, at most every 1 %.
     *
     * [cancelled] is also called from the stall watchdog's thread, so it must be thread-safe.
     *
     * @throws CancellationException when [cancelled] returns true; nothing half-written is left.
     * @throws ModelDownloadException on a network or checksum failure, or a stall; its message is readable.
     */
    open fun download(
        files: List<ModelFile>,
        folder: Path,
        progress: (Double) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val wanted = files.filter { !hasSize(target(folder, it), it.bytes) }
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
            // Another download may have fetched it meanwhile (a shared file).
            if (hasSize(target(folder, file), file.bytes)) {
                report(file.bytes)
            } else {
                fetch(file, target(folder, file), cancelled, ::report)
            }
        }
    }

    /** Shuts the client down if this downloader created it. */
    override fun close() {
        if (owned && client.isInitialized()) client.value.shutdownNow()
    }

    private fun fetch(
        file: ModelFile,
        target: Path,
        cancelled: () -> Boolean,
        read: (Long) -> Unit,
    ) {
        var part: Path? = null
        val watchdog = Watchdog(cancelled, Thread.currentThread(), stallTimeout)
        try {
            Files.createDirectories(target.parent)
            part = Files.createTempFile(target.parent, file.path.substringAfterLast('/') + "-", ".part")
            log.log(System.Logger.Level.INFO, "Downloading ${file.path}")
            val request =
                HttpRequest
                    .newBuilder(file.url)
                    .timeout(stallTimeout.toJavaDuration())
                    .GET()
                    .build()
            val response = client.value.send(request, HttpResponse.BodyHandlers.ofInputStream())
            watchdog.touch()
            response.body().use { body ->
                watchdog.body = body
                if (response.statusCode() != 200) {
                    throw ModelDownloadException(
                        "Download of ${file.path} failed: the server answered ${response.statusCode()}",
                    )
                }
                val digest = MessageDigest.getInstance(file.checksum.algorithm.javaName)
                var written = 0L
                Files.newOutputStream(part).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (cancelled()) throw CancellationException("Download of ${file.path} cancelled")
                        val n = body.read(buffer)
                        if (n < 0) break
                        watchdog.touch()
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        written += n
                        read(n.toLong())
                    }
                }
                if (written != file.bytes || !file.checksum.matches(digest.digest())) {
                    throw ModelDownloadException("Download of ${file.path} failed: checksum mismatch")
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
            // A status or checksum failure of our own, already readable.
            e is ModelDownloadException -> {
                e
            }

            cancelled() -> {
                CancellationException("Download of ${file.path} cancelled")
            }

            watchdog.stalled || (e is HttpTimeoutException && e !is HttpConnectTimeoutException) -> {
                ModelDownloadException("Download of ${file.path} stalled")
            }

            e is InterruptedException -> {
                Thread.currentThread().interrupt()
                CancellationException("Download of ${file.path} interrupted")
            }

            else -> {
                ModelDownloadException("Download of ${file.path} failed: ${describe(file, e)}", e)
            }
        }

    private fun describe(
        file: ModelFile,
        e: Exception,
    ): String =
        when (e) {
            is ConnectException, is UnknownHostException, is HttpConnectTimeoutException -> {
                "cannot reach ${file.url.host}"
            }

            else -> {
                e.message ?: e.javaClass.simpleName
            }
        }

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
        private val scheduler = NamedThreads.scheduled("model-download-watchdog")

        init {
            val tick = (stall.inWholeMilliseconds / 4).coerceIn(10, 250)
            scheduler.scheduleWithFixedDelay(::check, tick, tick, TimeUnit.MILLISECONDS)
        }

        fun touch() {
            last = System.nanoTime()
        }

        private fun check() {
            val idle = System.nanoTime() - last > stall.inWholeNanoseconds
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
        /** The client every download uses by default: follows redirects (release files come from asset hosts). */
        fun defaultHttpClient(): HttpClient =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build()

        private fun hasSize(
            file: Path,
            bytes: Long,
        ) = Files.isRegularFile(file) && Files.size(file) == bytes

        private fun target(
            folder: Path,
            file: ModelFile,
        ): Path = resolveIn(folder, file.path)
    }
}
