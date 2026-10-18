package dev.sluide.fileviewers.markdown

import dev.sluide.fileviewers.image.imageTypes
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

    override fun fetch(uri: URI): FetchedImage? {
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
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
            response.body().use { stream -> read(response, uri, stream, remaining) }
        } catch (_: IOException) {
            null
        } catch (_: TimeoutException) {
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun read(
        response: HttpResponse<*>,
        uri: URI,
        stream: InputStream,
        remaining: () -> Duration,
    ): FetchedImage? {
        if (response.statusCode() != 200) return null
        val length = response.headers().firstValueAsLong("Content-Length")
        if (length.isPresent && length.asLong > maxBytes) return null
        val type = mediaType(response.headers().firstValue("Content-Type").orElse(null), uri) ?: return null
        // Closing the stream when the read does not finish in time ends the read.
        val read = CompletableFuture<ByteArray>()
        Thread.ofVirtual().name("sluide-image-download").start {
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
                return null
            }
        return if (bytes.size > maxBytes) null else FetchedImage(type, bytes)
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
