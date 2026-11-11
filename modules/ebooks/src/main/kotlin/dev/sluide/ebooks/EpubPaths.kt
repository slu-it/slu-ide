package dev.sluide.ebooks

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Zip paths of an EPUB. Entry names are only keys, never file system paths. */
internal object EpubPaths {
    /** The folder of [path] without a trailing slash; empty for the zip root. */
    fun dirOf(path: String): String = path.substringBeforeLast('/', "")

    /** Joins [base] (a folder) and [relative] with `.` and `..` removed; a leading slash means the zip root. */
    fun normalise(
        base: String,
        relative: String,
    ): String {
        val parts = ArrayDeque<String>()
        val all = if (relative.startsWith("/")) relative.split('/') else (base.split('/') + relative.split('/'))
        for (part in all) {
            when (part) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    /** A reference as written in a document at folder [base]: the resolved zip path and the decoded fragment. */
    data class Ref(
        val path: String,
        val fragment: String?,
    )

    fun resolve(
        base: String,
        href: String,
    ): Ref {
        val cleaned = href.trim()
        val hash = cleaned.indexOf('#')
        val pathPart = (if (hash >= 0) cleaned.substring(0, hash) else cleaned).substringBefore('?')
        val fragment = if (hash >= 0) decode(cleaned.substring(hash + 1)) else null
        val path = if (pathPart.isEmpty()) "" else normalise(base, decode(pathPart))
        return Ref(path, fragment)
    }

    /** Percent-decoding that leaves `+` alone and malformed input as written. */
    fun decode(text: String): String =
        if ('%' !in text) {
            text
        } else {
            try {
                URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                text
            }
        }

    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /** The scheme of [href] in lower case, ignoring whitespace and control characters, or null for a relative one. */
    fun schemeOf(href: String): String? =
        scheme
            .find(href.filter { !it.isWhitespace() && !it.isISOControl() })
            ?.value
            ?.dropLast(1)
            ?.lowercase()

    /** Media type from the extension, for manifest items that declare none. */
    fun mediaTypeOf(path: String): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "css" -> "text/css"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "xhtml", "html", "htm" -> "application/xhtml+xml"
            "ncx" -> "application/x-dtbncx+xml"
            else -> "application/octet-stream"
        }
}
