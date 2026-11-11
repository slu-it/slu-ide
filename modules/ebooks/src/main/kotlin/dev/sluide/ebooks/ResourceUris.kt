package dev.sluide.ebooks

import java.util.Base64

/**
 * The data URIs of a book's resources, one base64 text per resource and build. Only validated media types of images
 * and fonts are inlined; the media type is written into the URI, so it must never carry anything else.
 */
internal class ResourceUris(
    private val resources: Map<String, Resource>,
    /** False: no base64 is built; a URI is a stub (`data:<type>;base64,`) and the bytes it stands for are counted. */
    val inline: Boolean = true,
) {
    private val cache = HashMap<String, String?>()

    /** With `inline = false`: how many references to images and fonts were stubbed. */
    var references = 0
        private set

    /** With `inline = false`: the characters the stubbed references would add when inlined, at least, per reference. */
    var referencedChars = 0L
        private set

    /** The data URI of the resource [href] (relative to the folder [base]) refers to; null if remote or unusable. */
    fun dataUri(
        href: String,
        base: String,
    ): String? {
        val trimmed = href.trim()
        if (trimmed.startsWith("data:", ignoreCase = true)) return ownDataUri(trimmed)
        if (EpubPaths.schemeOf(href) != null || trimmed.startsWith("//")) return null
        val path = EpubPaths.resolve(base, href).path
        val resource = resources[path]
        if (!inline) {
            if (resource == null || !isMediaType(resource.mediaType) || !isInlinable(resource.mediaType)) return null
            references++
            referencedChars += stub(resource.mediaType).length + OVERHEAD + 4L * ((resource.bytes.size + 2) / 3)
            return stub(resource.mediaType)
        }
        if (path in cache) return cache[path]
        return encode(resource).also { cache[path] = it }
    }

    private fun stub(type: String) = "data:$type;base64,"

    private fun encode(resource: Resource?): String? {
        if (resource == null || !isMediaType(resource.mediaType) || !isInlinable(resource.mediaType)) return null
        return "data:${resource.mediaType};base64,${Base64.getEncoder().encodeToString(resource.bytes)}"
    }

    /** A `data:` URI the book wrote itself: only base64 images and fonts, rebuilt from its validated parts. */
    private fun ownDataUri(href: String): String? {
        val match = ownData.matchEntire(href.filter { !it.isWhitespace() }) ?: return null
        val type = match.groupValues[1].lowercase()
        if (!inline) {
            references++
            referencedChars += stub(type).length + OVERHEAD + match.groupValues[2].length
            return stub(type)
        }
        return "data:$type;base64,${match.groupValues[2]}"
    }

    companion object {
        /** The most a reference adds around its URI: ` src=""` and ` href=""` against nothing, `url("")` against `none`. */
        private const val OVERHEAD = 8
        private val mediaType = Regex("[a-z]+/[a-z0-9.+-]+")
        private val ownData = Regex("(?i)data:((?:image|font)/[a-z0-9.+-]+);base64,([A-Za-z0-9+/=]*)")

        /** A media type as it may be written into a data URI. */
        fun isMediaType(text: String) = mediaType.matches(text)

        /** Lower case and validated, or null for anything else. */
        fun validMediaType(text: String): String? = text.trim().lowercase().takeIf(::isMediaType)

        fun isInlinable(type: String) =
            type.startsWith("image/") ||
                type.startsWith("font/") ||
                type.startsWith("application/font-") ||
                type.startsWith("application/x-font-") ||
                type == "application/vnd.ms-opentype"

        /** What the reader keeps in memory: what can be inlined, and style sheets. */
        fun isKept(type: String) = isMediaType(type) && (isInlinable(type) || type == "text/css")
    }
}
