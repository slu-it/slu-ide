package dev.sluide.languageservers

import java.io.ByteArrayOutputStream
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Converts between paths and the `file://` URIs of LSP. Thoreau sends what [Path.toUri] makes of the absolute,
 * normalized path; servers may answer with other encodings of the same file (`%C3%A4` or `ä`, `file:/`,
 * `file://localhost/`), so URIs from a server are compared after [normalize].
 */
object LspUris {
    /** The URI of [path], absolute and normalized, without a trailing slash for folders. */
    fun of(path: Path): String {
        val uri =
            path
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString()
        return if (uri.endsWith("/") && uri != "file:///") uri.dropLast(1) else uri
    }

    /** The path of a `file` URI, or `null` for another scheme, a remote host or what is no path. */
    fun toPath(uri: String): Path? {
        if (!uri.startsWith("file:", ignoreCase = true)) return null
        var rest = uri.substring("file:".length).substringBefore('#').substringBefore('?')
        if (rest.startsWith("//")) {
            rest = rest.substring(2)
            val slash = rest.indexOf('/')
            if (slash < 0) return null
            val authority = rest.substring(0, slash)
            if (authority.isNotEmpty() && !authority.equals("localhost", ignoreCase = true)) return null
            rest = rest.substring(slash)
        }
        val decoded = percentDecoded(rest) ?: return null
        return try {
            Path.of(decoded).normalize()
        } catch (_: InvalidPathException) {
            null
        }
    }

    /** [uri] in the form [of] gives its path, so that URIs of one file are equal; other URIs unchanged. */
    fun normalize(uri: String): String = toPath(uri)?.let(::of) ?: uri

    private fun percentDecoded(text: String): String? {
        if ('%' !in text) return text
        val bytes = ByteArrayOutputStream(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val value = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.write(value)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return bytes.toString(Charsets.UTF_8)
    }
}
