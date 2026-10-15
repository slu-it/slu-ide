package dev.sluide.fileviewers

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

/** Where a link in a preview (Markdown, HTML, notebooks, …) leads. */
internal sealed interface LinkTarget {
    /** A page for the system browser (`http`, `https`, `mailto`). */
    data class WebPage(
        val uri: URI,
    ) : LinkTarget

    /** A file for Thoreau to open. */
    data class File(
        val path: Path,
    ) : LinkTarget
}

/**
 * Where [href], a link in a previewed file in [folder], leads: web pages as they are, relative paths resolved against
 * [folder] without fragment or query (`other.md#part` is `other.md`), scheme-relative ones (`//host/x`) as `https`.
 * `null` for links within the page (`#part`), for other schemes and for links that are no valid URI or path.
 */
internal fun resolveLink(
    href: String,
    folder: Path,
): LinkTarget? {
    val link = href.trim()
    if (link.isEmpty() || link.startsWith("#")) return null
    return try {
        val uri = URI(link.replace(" ", "%20"))
        when (uri.scheme?.lowercase()) {
            "http", "https", "mailto" -> LinkTarget.WebPage(uri)
            "file" -> LinkTarget.File(Path.of(URI("file", uri.authority, uri.path, null)).normalize())
            null if uri.authority != null -> LinkTarget.WebPage(URI("https:$link"))
            null -> uri.path?.takeIf { it.isNotEmpty() }?.let { LinkTarget.File(folder.resolve(it).normalize()) }
            else -> null
        }
    } catch (_: URISyntaxException) {
        null
    } catch (_: IllegalArgumentException) {
        // Also InvalidPathException: e.g. `file://server/share` or a NUL character.
        null
    }
}
