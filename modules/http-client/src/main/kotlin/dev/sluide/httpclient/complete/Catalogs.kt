package dev.sluide.httpclient.complete

import dev.sluide.httpclient.parse.HttpFileParser

/** What `.http` completion offers by itself: methods, versions, header names and values, tags. */
internal object Catalogs {
    /** The common methods first, then the others the parser accepts. */
    val METHODS: List<String> =
        listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").let { common ->
            common + (HttpFileParser.METHODS - common.toSet())
        }

    val VERSIONS = listOf("HTTP/1.1", "HTTP/2")

    /** The tags the parser reads before a request line. */
    val TAGS: List<String> = HttpFileParser.TAGS.toList()

    /** Common request headers. */
    val HEADER_NAMES =
        listOf(
            "Accept",
            "Accept-Charset",
            "Accept-Encoding",
            "Accept-Language",
            "Access-Control-Request-Headers",
            "Access-Control-Request-Method",
            "Authorization",
            "Cache-Control",
            "Connection",
            "Content-Disposition",
            "Content-Encoding",
            "Content-Length",
            "Content-Type",
            "Cookie",
            "Date",
            "Expect",
            "Forwarded",
            "From",
            "Host",
            "If-Match",
            "If-Modified-Since",
            "If-None-Match",
            "If-Range",
            "If-Unmodified-Since",
            "Max-Forwards",
            "Origin",
            "Pragma",
            "Prefer",
            "Proxy-Authorization",
            "Range",
            "Referer",
            "TE",
            "Upgrade",
            "Upgrade-Insecure-Requests",
            "User-Agent",
            "Via",
            "X-Api-Key",
            "X-Correlation-ID",
            "X-Forwarded-For",
            "X-Forwarded-Host",
            "X-Forwarded-Proto",
            "X-Request-ID",
            "X-Requested-With",
        )

    private val MEDIA_TYPES =
        listOf(
            "application/json",
            "application/xml",
            "application/x-www-form-urlencoded",
            "multipart/form-data",
            "text/plain",
            "text/html",
            "text/xml",
            "text/csv",
            "application/octet-stream",
            "application/problem+json",
        )

    /** The values of well-known headers, by lowercase name. */
    private val HEADER_VALUES: Map<String, List<String>> =
        mapOf(
            "content-type" to MEDIA_TYPES,
            "accept" to listOf("*/*") + MEDIA_TYPES,
            "authorization" to listOf("Bearer ", "Basic ", "Digest "),
            "accept-encoding" to listOf("gzip", "deflate", "br", "identity"),
            "cache-control" to listOf("no-cache", "no-store", "max-age=0"),
            "connection" to listOf("keep-alive", "close"),
        )

    /** Headers whose value is a comma-separated list: each element is completed on its own. */
    private val LIST_HEADERS = setOf("accept", "accept-encoding", "cache-control", "connection")

    /** The values offered for the header [name] (any case); empty for the unknown ones. */
    fun valuesOf(name: String): List<String> = HEADER_VALUES[name.lowercase()].orEmpty()

    fun isListHeader(name: String) = name.lowercase() in LIST_HEADERS
}
