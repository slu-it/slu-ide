package dev.sluide.httpclient.send

import dev.sluide.httpclient.auth.DigestAuth
import dev.sluide.httpclient.parse.HttpVersion
import dev.sluide.httpclient.parse.RequestTags
import java.net.ConnectException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.UnresolvedAddressException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * Sends [PreparedRequest]s on [executor] (by default an own executor with virtual threads, stopped by [close]) and
 * hands exactly one [ExchangeResult] per request to `onResult` through [dispatch] (e.g. `Platform::runLater`).
 *
 * `@timeout` is a deadline for the whole exchange, body included; `@connection-timeout` limits connecting. Both
 * default to [DEFAULT_TIMEOUT]. Bodies are read up to [bodyLimit] bytes, then truncated. A `401` with a Digest
 * challenge to a request with [PreparedRequest.digest] credentials is answered once, with the same body.
 */
internal class RequestExecutor(
    private val clients: HttpClients,
    executor: Executor? = null,
    private val dispatch: (Runnable) -> Unit = Runnable::run,
    private val bodyLimit: Int = DEFAULT_BODY_LIMIT,
    private val digest: DigestAuth = DigestAuth(),
) : AutoCloseable {
    private val ownExecutor = if (executor == null) Executors.newVirtualThreadPerTaskExecutor() else null
    private val executor: Executor = executor ?: checkNotNull(ownExecutor)

    fun run(
        request: PreparedRequest,
        tags: RequestTags,
        onResult: (ExchangeResult) -> Unit,
    ): Cancellable = run(tags, onResult) { request }

    /**
     * Runs [prepare] on the executor, then sends the request it returns. [prepare] may block (e.g. for token requests)
     * and add notes; cancelling interrupts it. What it throws becomes the [HttpFailure], with the message as it is.
     * `@timeout` does not cover the preparation.
     */
    fun run(
        tags: RequestTags,
        onResult: (ExchangeResult) -> Unit,
        prepare: (notes: MutableList<String>) -> PreparedRequest,
    ): Cancellable {
        val exchange = Exchange(tags, prepare)
        executor.execute {
            val result =
                try {
                    exchange.run()
                } catch (e: Throwable) {
                    exchange.unexpected(e)
                }
            // A late cancel must not leave its interrupt to the next task of a pooled thread.
            Thread.interrupted()
            dispatch { onResult(result) }
        }
        return Cancellable(exchange::cancel)
    }

    /** Stops the own executor, aborting running requests; an executor passed in is left alone. */
    override fun close() {
        ownExecutor?.shutdownNow()
    }

    private inner class Exchange(
        private val tags: RequestTags,
        private val prepare: (MutableList<String>) -> PreparedRequest,
    ) {
        private val timeout = tags.timeout ?: DEFAULT_TIMEOUT
        private val connectTimeout = tags.connectionTimeout ?: DEFAULT_TIMEOUT

        /** When preparing, then when sending started: the deadline and the elapsed time leave preparation out. */
        @Volatile
        private var start = System.nanoTime()
        private val notes = mutableListOf<String>()
        private lateinit var request: PreparedRequest

        @Volatile
        private var cancelled = false

        /** The thread while it prepares; guarded by [preparingLock] so that [cancel] interrupts only preparation. */
        private var preparing: Thread? = null
        private val preparingLock = Any()

        @Volatile
        private var current: CompletableFuture<*>? = null

        fun cancel() {
            cancelled = true
            synchronized(preparingLock) { preparing?.interrupt() }
            current?.cancel(true)
        }

        fun run(): ExchangeResult {
            val prepareNotes = mutableListOf<String>()
            try {
                synchronized(preparingLock) { preparing = Thread.currentThread() }
                if (cancelled) return failure("Cancelled", cancelled = true)
                request = prepare(prepareNotes)
            } catch (_: InterruptedException) {
                notes += prepareNotes
                return interrupted()
            } catch (e: Exception) {
                notes += prepareNotes
                // A cancel may surface as another error, e.g. ClosedByInterruptException while reading a file.
                if (cancelled) return failure("Cancelled", cancelled = true)
                return failure(e.message ?: e::class.simpleName.orEmpty())
            } finally {
                synchronized(preparingLock) { preparing = null }
            }
            Thread.interrupted()
            if (cancelled) return failure("Cancelled", cancelled = true)
            notes += request.notes + prepareNotes
            start = System.nanoTime()
            return send()
        }

        /** A failure for an error no step expected (e.g. an [Error]), so that there is a result all the same. */
        fun unexpected(error: Throwable): HttpFailure =
            if (cancelled) failure("Cancelled", cancelled = true) else failure("Unexpected error: $error")

        private fun send(): ExchangeResult =
            try {
                val key =
                    HttpClients.ClientKey(
                        followRedirects = !tags.noRedirect,
                        cookieJar = !tags.noCookieJar,
                        connectTimeout = connectTimeout,
                        sslContext = request.sslContext,
                    )
                val client = clients.client(key)
                result(answerDigest(client, send(client, httpRequest())))
            } catch (_: CancellationException) {
                failure("Cancelled", cancelled = true)
            } catch (_: TimeoutException) {
                failure("No complete response within ${describe(timeout)} (@timeout)")
            } catch (_: InterruptedException) {
                interrupted()
            } catch (e: ExecutionException) {
                // The JDK aborts a cancelled exchange with an error of its own.
                if (cancelled) failure("Cancelled", cancelled = true) else failure(message(e.cause ?: e))
            } catch (e: Exception) {
                if (cancelled) failure("Cancelled", cancelled = true) else failure(message(e))
            }

        /** The request to [uri], with [authorization] added if given. */
        private fun httpRequest(
            uri: URI = request.uri,
            authorization: String? = null,
        ): HttpRequest {
            val body =
                if (request.body.isEmpty()) {
                    HttpRequest.BodyPublishers.noBody()
                } else {
                    HttpRequest.BodyPublishers.ofByteArray(request.body)
                }
            val version =
                when (request.version) {
                    HttpVersion.HTTP_1_1 -> HttpClient.Version.HTTP_1_1
                    HttpVersion.HTTP_2 -> HttpClient.Version.HTTP_2
                }
            val builder = HttpRequest.newBuilder(uri).method(request.method, body).version(version)
            request.headers.forEach { (name, value) -> builder.header(name, value) }
            authorization?.let { builder.header("Authorization", it) }
            return builder.build()
        }

        /** Sends the request again with the answer to the Digest challenge of [response], if there is one. */
        private fun answerDigest(
            client: HttpClient,
            response: HttpResponse<CappedBody>,
        ): HttpResponse<CappedBody> {
            val credentials = request.digest
            if (credentials == null || response.statusCode() != 401) return response
            val headers = response.headers().allValues("WWW-Authenticate")
            val challenge = DigestAuth.challenge(headers)
            val authorization = challenge?.let { digest.authorization(it, credentials, request.method, response.uri()) }
            if (challenge == null || authorization == null) {
                val problem = DigestAuth.challenges(headers).firstNotNullOfOrNull { it.problem }
                notes +=
                    problem?.let { "The Digest challenge cannot be answered: $it" }
                        ?: "The 401 response has no Digest challenge"
                return response
            }
            notes += "Answered the Digest challenge (${challenge.algorithm ?: "MD5"})"
            return send(client, httpRequest(response.uri(), authorization))
        }

        /** Sends [httpRequest] and waits until the body is read, at most until the deadline. */
        private fun send(
            client: HttpClient,
            httpRequest: HttpRequest,
        ): HttpResponse<CappedBody> {
            val future = client.sendAsync(httpRequest) { CappedBodySubscriber(bodyLimit) }
            current = future
            if (cancelled) future.cancel(true)
            val remaining = timeout.toNanos() - (System.nanoTime() - start)
            try {
                return future.get(remaining, TimeUnit.NANOSECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw e
            }
        }

        private fun result(response: HttpResponse<CappedBody>): HttpResult {
            val headers = response.headers().map().flatMap { (name, values) -> values.map { name to it } }
            val encoding = response.headers().firstValue("Content-Encoding").orElse(null)
            val decoded = BodyDecoding.decode(encoding, response.body().bytes, bodyLimit)
            decoded.note?.let(notes::add)
            val truncated = response.body().truncated || decoded.truncated
            if (truncated) notes += "The body is longer than ${bodyLimit / (1024 * 1024)} MB and was truncated"
            val version =
                when (response.version()) {
                    HttpClient.Version.HTTP_2 -> HttpVersion.HTTP_2
                    else -> HttpVersion.HTTP_1_1
                }
            return HttpResult(
                status = response.statusCode(),
                reason = ReasonPhrases.of(response.statusCode()),
                version = version,
                finalUri = response.uri(),
                headers = headers,
                body = decoded.bytes,
                truncated = truncated,
                elapsed = elapsed(),
                notes = notes.toList(),
            )
        }

        /** A readable message for [error], from the most telling exception of its cause chain. */
        private fun message(error: Throwable): String {
            val chain = generateSequence(error) { it.cause }.toList()
            val uri = request.uri
            val port = uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
            val ssl = chain.filterIsInstance<SSLException>().firstOrNull()
            val connect = chain.filterIsInstance<ConnectException>().firstOrNull { !it.message.isNullOrBlank() }
            return when {
                chain.any { it is HttpConnectTimeoutException } -> {
                    "Connection timed out after ${describe(connectTimeout)} (@connection-timeout)"
                }

                chain.any { it is UnresolvedAddressException || it is UnknownHostException } -> {
                    "Unknown host: ${uri.host}"
                }

                ssl != null -> {
                    "TLS handshake failed: ${ssl.message}"
                }

                chain.any { it is ConnectException } -> {
                    val reason = connect?.message
                    if (reason == null || "refused" in reason.lowercase()) {
                        "Connection refused: ${uri.host}:$port"
                    } else {
                        "Cannot connect to ${uri.host}:$port: $reason"
                    }
                }

                else -> {
                    val described = chain.firstOrNull { !it.message.isNullOrBlank() }
                    described?.let { "${it::class.simpleName}: ${it.message}" } ?: error::class.simpleName.orEmpty()
                }
            }
        }

        /** Interrupted by [cancel] or by [close]. */
        private fun interrupted(): HttpFailure =
            if (cancelled) failure("Cancelled", cancelled = true) else failure("Interrupted", cancelled = true)

        private fun failure(
            message: String,
            cancelled: Boolean = false,
        ) = HttpFailure(message, cancelled, elapsed(), notes.toList())

        private fun elapsed() = Duration.ofNanos(System.nanoTime() - start)
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(60)
        const val DEFAULT_BODY_LIMIT = 50 * 1024 * 1024

        private fun describe(duration: Duration) =
            if (duration.toMillis() % 1000 == 0L) "${duration.toSeconds()} s" else "${duration.toMillis()} ms"
    }
}
