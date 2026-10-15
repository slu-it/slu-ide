package dev.sluide.httpclient.send

import dev.sluide.httpclient.parse.HttpVersion
import java.net.URI
import java.time.Duration

/** The outcome of running a request: a response or a failure. */
internal sealed interface ExchangeResult {
    val elapsed: Duration

    /** What was changed, left out or done in addition (dropped headers, skipped scripts, truncation, …). */
    val notes: List<String>

    /** The request as it was sent, `null` if it could not be built (e.g. because of an unresolved variable). */
    val request: PreparedRequest?
}

/**
 * A response. [body] is decoded per `Content-Encoding` and [truncated] if it was longer than the limit; [reason] is
 * the standard phrase of [status] (the JDK client does not expose the one sent), null for unknown codes.
 */
internal class HttpResult(
    val status: Int,
    val reason: String?,
    val version: HttpVersion,
    val finalUri: URI,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
    val truncated: Boolean,
    override val elapsed: Duration,
    override val notes: List<String>,
    override val request: PreparedRequest? = null,
) : ExchangeResult {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

/** No response: [message] says why; [cancelled] if the user cancelled. */
internal data class HttpFailure(
    val message: String,
    val cancelled: Boolean,
    override val elapsed: Duration,
    override val notes: List<String>,
    override val request: PreparedRequest? = null,
) : ExchangeResult

/** Stops a running request. */
internal fun interface Cancellable {
    fun cancel()
}

internal object ReasonPhrases {
    private val phrases =
        mapOf(
            100 to "Continue",
            101 to "Switching Protocols",
            200 to "OK",
            201 to "Created",
            202 to "Accepted",
            203 to "Non-Authoritative Information",
            204 to "No Content",
            205 to "Reset Content",
            206 to "Partial Content",
            300 to "Multiple Choices",
            301 to "Moved Permanently",
            302 to "Found",
            303 to "See Other",
            304 to "Not Modified",
            307 to "Temporary Redirect",
            308 to "Permanent Redirect",
            400 to "Bad Request",
            401 to "Unauthorized",
            402 to "Payment Required",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            406 to "Not Acceptable",
            407 to "Proxy Authentication Required",
            408 to "Request Timeout",
            409 to "Conflict",
            410 to "Gone",
            411 to "Length Required",
            412 to "Precondition Failed",
            413 to "Content Too Large",
            414 to "URI Too Long",
            415 to "Unsupported Media Type",
            416 to "Range Not Satisfiable",
            417 to "Expectation Failed",
            421 to "Misdirected Request",
            422 to "Unprocessable Content",
            425 to "Too Early",
            426 to "Upgrade Required",
            428 to "Precondition Required",
            429 to "Too Many Requests",
            431 to "Request Header Fields Too Large",
            451 to "Unavailable For Legal Reasons",
            500 to "Internal Server Error",
            501 to "Not Implemented",
            502 to "Bad Gateway",
            503 to "Service Unavailable",
            504 to "Gateway Timeout",
            505 to "HTTP Version Not Supported",
        )

    fun of(status: Int): String? = phrases[status]
}
