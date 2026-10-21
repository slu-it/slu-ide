package dev.sluide.previews.markdown

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.previews.image.imageTypes
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val log = System.getLogger(HttpImageFetcher::class.java.name)

/** An image downloaded for an export: its media type and bytes. */
internal class FetchedImage(
    val type: String,
    val bytes: ByteArray,
)

/** Downloads remote images; `null` if [uri] gives no image. Blocking, never on the FX thread. */
internal fun interface ImageFetcher {
    fun fetch(uri: URI): FetchedImage?
}

/**
 * Downloads images over HTTP(S): status 200, an image type, at most [maxBytes], the whole download within [timeout].
 * The client is only built when the first image is fetched.
 */
internal class HttpImageFetcher(
    private val maxBytes: Long = MarkdownRenderer.MAX_IMAGE_BYTES,
    private val timeout: Duration = Duration.ofSeconds(30),
) : ImageFetcher {
    private val client by lazy {
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    /** What a download found out, for the log. */
    private class Outcome {
        var status: Int? = null
        var reason: String? = null
    }

    override fun fetch(uri: URI): FetchedImage? {
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        // Host and path only: the query may hold tokens (ADR 0060).
        val where = "${uri.host}${uri.rawPath.orEmpty()}"
        log.log(System.Logger.Level.INFO, "Downloading image $where")
        val started = System.nanoTime()
        val outcome = Outcome()
        val image = download(uri, outcome)
        val millis = (System.nanoTime() - started) / 1_000_000
        if (image != null) {
            log.log(
                System.Logger.Level.INFO,
                "Downloaded image $where: status ${outcome.status}, ${image.bytes.size} bytes in $millis ms",
            )
        } else {
            log.log(System.Logger.Level.WARNING, "Image $where not downloaded after $millis ms: ${outcome.reason}")
        }
        return image
    }

    private fun download(
        uri: URI,
        outcome: Outcome,
    ): FetchedImage? {
        val deadline = System.nanoTime() + timeout.toNanos()
        val remaining = { Duration.ofNanos(maxOf(0L, deadline - System.nanoTime())) }
        return try {
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .GET()
                    .timeout(timeout)
                    .build()
            val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            val response =
                try {
                    future.get(remaining().toMillis(), TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    // A response that arrived after all is not read.
                    future.thenAccept { it.body().close() }
                    throw e
                }
            response.body().use { stream -> read(response, uri, stream, remaining, outcome) }
        } catch (e: IOException) {
            outcome.reason = e::class.simpleName
            null
        } catch (_: TimeoutException) {
            outcome.reason = "timed out"
            null
        } catch (e: ExecutionException) {
            outcome.reason = (e.cause ?: e)::class.simpleName
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            outcome.reason = "interrupted"
            null
        } catch (e: IllegalArgumentException) {
            outcome.reason = e::class.simpleName
            null
        }
    }

    private fun read(
        response: HttpResponse<*>,
        uri: URI,
        stream: InputStream,
        remaining: () -> Duration,
        outcome: Outcome,
    ): FetchedImage? {
        outcome.status = response.statusCode()
        if (response.statusCode() != 200) {
            outcome.reason = "status ${response.statusCode()}"
            return null
        }
        val length = response.headers().firstValueAsLong("Content-Length")
        if (length.isPresent && length.asLong > maxBytes) {
            outcome.reason = "larger than $maxBytes bytes"
            return null
        }
        val type = mediaType(response.headers().firstValue("Content-Type").orElse(null), uri)
        if (type == null) {
            outcome.reason = "not an image type"
            return null
        }
        // Closing the stream when the read does not finish in time ends the read.
        val read = CompletableFuture<ByteArray>()
        NamedThreads.virtual("sluide-image-download") {
            try {
                read.complete(stream.readNBytes(maxBytes.toInt() + 1))
            } catch (e: Throwable) {
                read.completeExceptionally(e)
            }
        }
        val bytes =
            try {
                read.get(remaining().toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                stream.close()
                outcome.reason = "reading timed out"
                return null
            }
        if (bytes.size > maxBytes) {
            outcome.reason = "larger than $maxBytes bytes"
            return null
        }
        return FetchedImage(type, bytes)
    }

    /** The image type the server declares; the file name's only if it declares none (or only bytes). */
    private fun mediaType(
        header: String?,
        uri: URI,
    ): String? {
        val declared = header?.substringBefore(';')?.trim()?.lowercase()
        if (declared != null && IMAGE_TYPE.matches(declared)) return declared
        if (declared != null && declared.isNotEmpty() && declared != "application/octet-stream") return null
        val extension =
            uri.path
                .orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('.', "")
                .lowercase()
        return imageTypes[extension]
    }

    private companion object {
        val IMAGE_TYPE = Regex("image/[a-z0-9.+-]+")
    }
}
