package dev.sluide.todos.markdown

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** What a click on a Markdown link does. */
internal sealed interface LinkTarget {
    /** Open the web page (or mail address). */
    data class Web(
        val uri: URI,
    ) : LinkTarget

    /** Open the file of the project. */
    data class File(
        val path: Path,
    ) : LinkTarget

    /** Nothing: the link is not safe or not usable. */
    data object None : LinkTarget
}

private val schemePattern = Regex("^([a-zA-Z][a-zA-Z0-9+.-]+):")
private val webSchemes = setOf("http", "https")

private fun schemeOf(text: String): String? =
    schemePattern
        .find(text)
        ?.groupValues
        ?.get(1)
        ?.lowercase()

/**
 * The source with the task marker at [markerOffset] (the `[` of `[ ]`, `[x]` or `[X]`) flipped (`[ ]` to `[x]`, `[x]`
 * and `[X]` to `[ ]`); null if the text there is no marker.
 */
internal fun toggleTask(
    source: String,
    markerOffset: Int,
): String? {
    if (markerOffset < 0 || markerOffset + 3 > source.length || source[markerOffset] != '[') return null
    if (source[markerOffset + 2] != ']') return null
    val flipped =
        when (source[markerOffset + 1]) {
            ' ' -> "[x]"
            'x', 'X' -> "[ ]"
            else -> return null
        }
    return source.replaceRange(markerOffset, markerOffset + 3, flipped)
}

/**
 * What the link [href] opens: `http`, `https` and `mailto` are web targets; a relative or absolute path under
 * [projectFolder] (percent-decoded, without `#fragment` and `?query`) is a file; anything else is [LinkTarget.None].
 */
internal fun linkTarget(
    href: String,
    projectFolder: Path?,
): LinkTarget {
    val text = href.trim()
    val scheme = schemeOf(text)
    if (scheme != null) {
        if (scheme !in webSchemes && scheme != "mailto") return LinkTarget.None
        return try {
            LinkTarget.Web(URI(text))
        } catch (_: java.net.URISyntaxException) {
            LinkTarget.None
        }
    }
    return localPath(text, projectFolder)?.let { LinkTarget.File(it) } ?: LinkTarget.None
}

/** The loadable source of the image [src]: a web URL as it is, a regular file under [projectFolder] as `file:` URI. */
internal fun imageSource(
    src: String,
    projectFolder: Path?,
): String? {
    val text = src.trim()
    val scheme = schemeOf(text)
    if (scheme != null) return if (scheme in webSchemes) text else null
    val path = localPath(text, projectFolder) ?: return null
    return if (Files.isRegularFile(path)) path.toUri().toString() else null
}

/** [text] as a path (decoded, without fragment and query) that is under [projectFolder] after `normalize()`. */
private fun localPath(
    text: String,
    projectFolder: Path?,
): Path? {
    if (projectFolder == null) return null
    val raw = text.substringBefore('#').substringBefore('?')
    if (raw.isEmpty()) return null
    return try {
        val decoded = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)
        val root = projectFolder.toAbsolutePath().normalize()
        val path = Path.of(decoded)
        val resolved = (if (path.isAbsolute) path else root.resolve(path)).normalize()
        resolved.takeIf { it != root && it.startsWith(root) }
    } catch (_: InvalidPathException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
