package dev.sluide.java

import dev.sluide.languageservers.LibraryDocument
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Where the text of a `jdt:` class URI jdtls names goes (ADR 0179, ADR 0150): below the Java classes cache, in a folder
 * of the URI's hash, under a file name made of the URI's last path segment. The URI comes from the server and names
 * what the project's classpath holds, so nothing of it is trusted: the name keeps only `[A-Za-z0-9_$.-]`.
 */
internal object JdtLibraryDocuments {
    private const val SCHEME = "jdt:"
    private const val HASH_DIGITS = 12
    private const val MAX_NAME = 100
    private const val ESCAPE_LENGTH = 3
    private val ESCAPES = Regex("(?:%[0-9A-Fa-f]{2})+")

    /** The request that fetches [uri]'s text and the file to write it to below [root]; null for a URI that is not `jdt:`. */
    fun of(
        uri: String,
        root: Path,
    ): LibraryDocument? {
        if (!uri.startsWith(SCHEME, ignoreCase = true)) return null
        val file = root.resolve(hashOf(uri)).resolve(fileName(uri)).normalize()
        if (!file.startsWith(root.normalize())) return null
        return LibraryDocument("java/classFileContents", mapOf("uri" to uri), file)
    }

    private fun hashOf(uri: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(uri.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(HASH_DIGITS)

    /** The last path segment of [uri] without query and fragment, decoded and reduced to the allowed characters. */
    private fun fileName(uri: String): String {
        val rest = uri.substring(SCHEME.length).substringBefore('#').substringBefore('?')
        // Without "//" the rest is the path itself; with it the authority up to the next slash is not.
        val path = if (rest.startsWith("//")) rest.drop(2).substringAfter('/', "") else rest
        val reduced =
            percentDecode(path.substringAfterLast('/'))
                .filter {
                    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '$' || it == '.' ||
                        it == '-'
                }.removeSuffix(".class")
                .take(MAX_NAME)
        return when {
            reduced.all { it == '.' } -> "Class.java"
            reduced.endsWith(".java") -> reduced
            else -> "$reduced.java"
        }
    }

    /** Decodes runs of `%XX` as UTF-8; a `%` that starts no valid sequence stays as it is. */
    private fun percentDecode(text: String): String =
        ESCAPES.replace(text) { run ->
            val digits = run.value.length / ESCAPE_LENGTH
            val bytes =
                ByteArray(
                    digits,
                ) {
                    run.value
                        .substring(it * ESCAPE_LENGTH + 1, (it + 1) * ESCAPE_LENGTH)
                        .toInt(16)
                        .toByte()
                }
            String(bytes, StandardCharsets.UTF_8)
        }
}
