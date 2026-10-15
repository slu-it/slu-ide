package dev.sluide.httpclient.ui

import dev.sluide.httpclient.parse.HttpVersion
import dev.sluide.httpclient.send.ExchangeResult
import dev.sluide.httpclient.send.HttpFailure
import dev.sluide.httpclient.send.HttpResult
import dev.sluide.httpclient.send.PreparedRequest

/** The kind of outcome of a request, coloured in the status line by its [styleClass]. */
internal enum class StatusKind(
    val styleClass: String,
) {
    INFORMATIONAL("http-status-informational"),
    SUCCESS("http-status-success"),
    REDIRECT("http-status-redirect"),
    CLIENT_ERROR("http-status-client-error"),
    SERVER_ERROR("http-status-server-error"),
    FAILURE("http-status-failure"),
    ;

    companion object {
        fun of(status: Int): StatusKind =
            when (status) {
                in 100..199 -> INFORMATIONAL
                in 200..299 -> SUCCESS
                in 300..399 -> REDIRECT
                in 400..499 -> CLIENT_ERROR
                else -> SERVER_ERROR
            }
    }
}

/** The status line of a response: [status] (e.g. `200 OK`) in the colour of [kind], then the [details]. */
internal data class StatusLine(
    val status: String,
    val kind: StatusKind,
    val details: List<String>,
)

/** The request as the Request tab shows it; [partial] if only the start of its body is shown. */
internal data class RequestText(
    val text: String,
    val partial: Boolean,
)

/** The texts the response pane shows of an [ExchangeResult]. */
internal object ResponseTexts {
    /** Longer bodies are cut for display: a text area holding them would block the UI. */
    const val DISPLAY_LIMIT = 5 * 1024 * 1024

    private const val MASK = "••••••"

    /** Headers whose value starts with an auth scheme, which stays visible. */
    private val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization")
    private val SECRET_SUFFIXES = listOf("-key", "-token", "-secret")
    private val SECRET_NAMES = setOf("apikey", "api-key", "x-api-key")

    fun statusLine(result: ExchangeResult): StatusLine =
        when (result) {
            is HttpResult -> {
                StatusLine(
                    listOfNotNull(result.status.toString(), result.reason).joinToString(" "),
                    StatusKind.of(result.status),
                    listOf(
                        ResponseFormatter.duration(result.elapsed),
                        ResponseFormatter.size(result.body.size.toLong()),
                        version(result.version),
                        result.finalUri.toString(),
                    ),
                )
            }

            is HttpFailure -> {
                StatusLine(
                    if (result.cancelled) "Cancelled" else "Failed",
                    StatusKind.FAILURE,
                    listOf(ResponseFormatter.duration(result.elapsed)),
                )
            }
        }

    /** One `Name: value` line per header, in the order received. */
    fun headers(result: HttpResult): String = result.headers.joinToString("\n") { (name, value) -> "$name: $value" }

    /**
     * The request as sent: request line, headers with secrets masked ([masked]), and the body as text (or a summary of
     * a binary one), as written, of which only the first [DISPLAY_LIMIT] bytes are shown. The request's secrets
     * ([PreparedRequest.secrets], e.g. OAuth2 tokens) are masked wherever they appear. A Digest answer sent after a
     * challenge is not part of it.
     */
    fun request(request: PreparedRequest): RequestText {
        val head =
            listOf("${request.method} ${request.uri} ${version(request.version)}") +
                request.headers.map { (name, value) -> "$name: ${masked(name, value)}" }
        val body = request.body
        val partial = body.size > DISPLAY_LIMIT
        val shown = if (partial) body.copyOf(utf8Boundary(body, DISPLAY_LIMIT)) else body
        val contentType = request.header("Content-Type")
        val bodyText =
            when {
                body.isEmpty() -> null
                else -> ResponseFormatter.format(shown, contentType, pretty = false).raw ?: binary(body, contentType)
            }
        val text = (head + listOfNotNull(bodyText?.let { "\n" + it })).joinToString("\n")
        return RequestText(
            request.secrets
                .filter {
                    it.isNotEmpty()
                }.fold(text) { t, secret -> t.replace(secret, MASK) },
            partial,
        )
    }

    private fun binary(
        body: ByteArray,
        contentType: String?,
    ): String {
        val type =
            MediaTypes
                .mediaType(contentType)
                .takeIf { it.isNotEmpty() }
                ?.let { ", $it" }
                .orEmpty()
        return "Binary body, ${ResponseFormatter.size(body.size.toLong())}$type"
    }

    /** At most [limit], moved back so that no UTF-8 character is cut. */
    private fun utf8Boundary(
        bytes: ByteArray,
        limit: Int,
    ): Int {
        var end = limit
        // Continuation bytes are 10xxxxxx.
        while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
        return end
    }

    /**
     * [value] of header [name] with its secrets masked: the credentials of `Authorization` and `Proxy-Authorization`
     * (the scheme stays), the values of `Cookie`, and the whole value of headers named like keys, tokens and secrets
     * (`X-Api-Key`, `X-Auth-Token`, `Client-Secret`, …).
     */
    fun masked(
        name: String,
        value: String,
    ): String {
        val lower = name.lowercase()
        return when {
            lower in CREDENTIAL_HEADERS -> {
                val scheme = value.trim().substringBefore(' ', missingDelimiterValue = "")
                if (scheme.isEmpty()) MASK else "$scheme $MASK"
            }

            lower == "cookie" -> {
                value.split(';').joinToString("; ") { cookie -> cookie.trim().substringBefore('=') + "=" + MASK }
            }

            lower in SECRET_NAMES || SECRET_SUFFIXES.any(lower::endsWith) -> {
                MASK
            }

            else -> {
                value
            }
        }
    }

    fun version(version: HttpVersion): String =
        when (version) {
            HttpVersion.HTTP_1_1 -> "HTTP/1.1"
            HttpVersion.HTTP_2 -> "HTTP/2"
        }
}
