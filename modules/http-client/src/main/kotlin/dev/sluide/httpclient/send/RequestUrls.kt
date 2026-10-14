package dev.sluide.httpclient.send

import java.net.URI
import java.net.URISyntaxException

/** Turns the URL of a request, with its variables resolved, into a [URI]. */
internal object RequestUrls {
    private const val HEX = "0123456789ABCDEF"
    private const val UNSAFE = "\"<>\\^`{|}[]"
    private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://""")

    /**
     * [url] without fragment, with `http://` when it has no scheme (or with the [host] header when it starts with
     * `/`), and with spaces, non-ASCII and other characters not allowed in URIs percent-encoded in path and query.
     * Existing `%xx` escapes are kept.
     */
    fun toUri(
        url: String,
        host: String?,
    ): URI {
        val withoutFragment = url.trim().substringBefore('#')
        val absolute =
            when {
                withoutFragment.isEmpty() -> throw RequestBuildException("The request has no URL")

                SCHEME.containsMatchIn(withoutFragment) -> withoutFragment

                withoutFragment.startsWith("/") && host != null -> "http://${host.trim()}$withoutFragment"

                withoutFragment.startsWith(
                    "/",
                ) -> throw RequestBuildException("A URL starting with / needs a Host header")

                else -> "http://$withoutFragment"
            }
        val authorityStart = absolute.indexOf("://") + 3
        val pathStart = absolute.indexOfAny(charArrayOf('/', '?'), authorityStart).takeIf { it >= 0 } ?: absolute.length
        val uri =
            try {
                URI(absolute.substring(0, pathStart) + encode(absolute.substring(pathStart)))
            } catch (e: URISyntaxException) {
                throw RequestBuildException("Invalid URL $absolute: ${e.reason}")
            }
        if (uri.scheme.lowercase() !in setOf("http", "https")) {
            throw RequestBuildException("Only http and https URLs are supported: $absolute")
        }
        if (uri.host == null) throw RequestBuildException("The URL has no host: $absolute")
        return uri
    }

    internal fun encode(text: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '%' && index + 2 < text.length && isHex(text[index + 1]) && isHex(text[index + 2]) -> {
                    result.append(char)
                }

                char.code in 0x21..0x7e && char != '%' && char !in UNSAFE -> {
                    result.append(char)
                }

                else -> {
                    val end = if (char.isHighSurrogate() && index + 1 < text.length) index + 2 else index + 1
                    for (byte in text.substring(index, end).toByteArray(Charsets.UTF_8)) {
                        val value = byte.toInt() and 0xff
                        result.append('%').append(HEX[value shr 4]).append(HEX[value and 0x0f])
                    }
                    index = end - 1
                }
            }
            index++
        }
        return result.toString()
    }

    private fun isHex(char: Char) = char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'
}
