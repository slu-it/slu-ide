package dev.sluide.todos.markdown

import dev.sluide.imagelimits.ImageCheck
import dev.sluide.imagelimits.ImageLimits
import javafx.scene.image.Image
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

private val log = System.getLogger("dev.sluide.todos.markdown.ImageLoading")

/** The bytes of a Markdown image, or why they are not used. */
internal sealed interface ImageBytes {
    class Loaded(
        val bytes: ByteArray,
    ) : ImageBytes

    /** [reason] reads after "Image ", e.g. "too large to show (30 000 × 30 000 px)". */
    data class Refused(
        val reason: String,
    ) : ImageBytes
}

/** Fetches the body of a web image. */
internal fun interface ImageFetcher {
    /** At most the limit plus one byte of the body (one byte more than the limit means too large); null on failure. */
    fun fetch(uri: URI): ByteArray?
}

/**
 * Fetches with `java.net.http` (ADR 0175): redirects followed, the whole exchange (connect, headers and body) done
 * within [timeout], the body cut at [limit] + 1 bytes. The URL is never logged (`docs/safety.md`, Secrets). Blocks.
 *
 * The client is built on first use and reused for every fetch; its threads are daemons and it lives as long as the
 * fetcher, which for the app's default one is the process.
 */
internal class HttpImageFetcher(
    private val timeout: Duration = 10.seconds,
    private val limit: Int = ImageLimits.MAX_FILE_BYTES,
) : ImageFetcher {
    private val client: HttpClient by lazy {
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(timeout.toJavaDuration())
            .build()
    }

    override fun fetch(uri: URI): ByteArray? {
        var future: CompletableFuture<HttpResponse<ByteArray>>? = null
        return try {
            val request =
                HttpRequest
                    .newBuilder(uri)
                    .header("User-Agent", "Thoreau")
                    .timeout(timeout.toJavaDuration())
                    .GET()
                    .build()
            // BodySubscribers.limiting fails over its capacity; a truncated body is what tells "too large" here.
            val handler =
                HttpResponse.BodyHandler { info ->
                    if (info.statusCode() in 200..299) {
                        TruncatingBody(limit + 1)
                    } else {
                        TruncatingBody(0)
                    }
                }
            val sent = client.sendAsync(request, handler)
            future = sent
            val response = sent.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            if (response.statusCode() in 200..299) response.body() else null
        } catch (_: TimeoutException) {
            future?.cancel(true)
            log.log(System.Logger.Level.DEBUG, "A web image did not arrive in time")
            null
        } catch (_: InterruptedException) {
            future?.cancel(true)
            Thread.currentThread().interrupt()
            null
        } catch (e: ExecutionException) {
            log.log(System.Logger.Level.DEBUG, "A web image could not be fetched: ${e.cause?.javaClass?.simpleName}")
            null
        } catch (e: IllegalArgumentException) {
            // A source the client refuses (no host, unsupported scheme).
            log.log(System.Logger.Level.DEBUG, "A web image source was refused: ${e.javaClass.simpleName}")
            null
        }
    }
}

/** Collects a body up to [capacity] bytes, then cancels the rest: a body over the limit is cut, not failed. */
private class TruncatingBody(
    private val capacity: Int,
) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val collected = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null

    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        if (capacity <= 0) {
            // Nothing is wanted of this body (an error status): do not download it.
            subscription.cancel()
            result.complete(ByteArray(0))
            return
        }
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(item: List<ByteBuffer>) {
        for (buffer in item) {
            val take = minOf(buffer.remaining(), capacity - collected.size())
            val chunk = ByteArray(take)
            buffer.get(chunk)
            collected.write(chunk)
            buffer.position(buffer.limit())
        }
        if (collected.size() >= capacity) {
            subscription?.cancel()
            result.complete(collected.toByteArray())
        }
    }

    override fun onError(throwable: Throwable) {
        result.completeExceptionally(throwable)
    }

    override fun onComplete() {
        result.complete(collected.toByteArray())
    }
}

/**
 * Reads (a `file:` source) or fetches (http, https) the bytes of [source], at most [ImageLimits.MAX_FILE_BYTES], and
 * refuses what is over the pixel caps from its header, before anything is decoded. Blocks; off the FX thread.
 */
internal fun loadImageBytes(
    source: String,
    fetcher: ImageFetcher,
): ImageBytes {
    val bytes =
        try {
            val uri = URI(source)
            if (uri.scheme.equals("file", ignoreCase = true)) ImageLimits.readFile(Path.of(uri)) else fetcher.fetch(uri)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: URISyntaxException) {
            null
        } catch (_: FileSystemNotFoundException) {
            null
        }
    return when {
        bytes == null -> {
            ImageBytes.Refused("could not be loaded")
        }

        bytes.size > ImageLimits.MAX_FILE_BYTES -> {
            ImageBytes.Refused("larger than 20 MiB")
        }

        else -> {
            when (val check = ImageLimits.check(bytes)) {
                is ImageCheck.TooLarge -> {
                    ImageBytes.Refused("too large to show (${ImageLimits.sizeText(check.header, "px")})")
                }

                ImageCheck.Unreadable -> {
                    ImageBytes.Refused("could not be read")
                }

                is ImageCheck.Fits -> {
                    ImageBytes.Loaded(bytes)
                }
            }
        }
    }
}

/** A decoded image, or the [reason] there is none. */
internal class LoadedImage(
    val image: Image?,
    val reason: String?,
)

/** [loadImageBytes], then the decode of what fits; any throwable of the decoder is a refusal. Off the FX thread. */
internal fun loadImage(
    source: String,
    fetcher: ImageFetcher,
): LoadedImage =
    when (val loaded = loadImageBytes(source, fetcher)) {
        is ImageBytes.Refused -> {
            LoadedImage(null, loaded.reason)
        }

        is ImageBytes.Loaded -> {
            try {
                val image = Image(ByteArrayInputStream(loaded.bytes))
                if (image.isError) LoadedImage(null, "could not be read") else LoadedImage(image, null)
            } catch (e: Throwable) {
                // Also an OutOfMemoryError, an expected resource failure and so a warning (ADR 0060, as in Barcodes).
                // Only the class is logged, never the source (docs/safety.md, Secrets).
                val level = if (e is OutOfMemoryError) System.Logger.Level.WARNING else System.Logger.Level.ERROR
                log.log(level, "Decoding an image failed: ${e.javaClass.name}")
                LoadedImage(null, "could not be read")
            }
        }
    }
