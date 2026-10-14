package dev.sluide.fileviewers

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

/** Where a link in the Markdown preview leads. */
internal sealed interface LinkTarget {
    /** A page for the system browser (`http`, `https`, `mailto`). */
    data class WebPage(
        val uri: URI,
    ) : LinkTarget

    /** A file for SluIde to open. */
    data class File(
        val path: Path,
    ) : LinkTarget
}

/**
 * Where [href], a link in a Markdown file in [folder], leads: web pages as they are, relative paths resolved against
 * [folder] without fragment or query (`other.md#part` is `other.md`). `null` for links within the page (`#part`),
 * for other schemes and for links that are no valid URI.
 */
internal fun resolveLink(
    href: String,
    folder: Path,
): LinkTarget? {
    val link = href.trim()
    if (link.isEmpty() || link.startsWith("#")) return null
    val uri =
        try {
            URI(link.replace(" ", "%20"))
        } catch (_: URISyntaxException) {
            return null
        }
    return when (uri.scheme?.lowercase()) {
        "http", "https", "mailto" -> LinkTarget.WebPage(uri)
        "file" -> LinkTarget.File(Path.of(URI("file", uri.authority, uri.path, null)).normalize())
        null -> uri.path?.takeIf { it.isNotEmpty() }?.let { LinkTarget.File(folder.resolve(it).normalize()) }
        else -> null
    }
}
