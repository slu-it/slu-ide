package dev.sluide.ebooks

import java.util.Base64

/**
 * The data URIs of a book's resources, one base64 text per resource and build. Only validated media types of images
 * and fonts are inlined; the media type is written into the URI, so it must never carry anything else.
 */
internal class ResourceUris(
    private val resources: Map<String, Resource>,
) {
    private val cache = HashMap<String, String?>()

    /** The data URI of the resource [href] (relative to the folder [base]) refers to; null if remote or unusable. */
    fun dataUri(
        href: String,
        base: String,
    ): String? {
        val trimmed = href.trim()
        if (trimmed.startsWith("data:", ignoreCase = true)) return ownDataUri(trimmed)
        if (EpubPaths.schemeOf(href) != null || trimmed.startsWith("//")) return null
        val path = EpubPaths.resolve(base, href).path
        if (path in cache) return cache[path]
        return encode(resources[path]).also { cache[path] = it }
    }

    private fun encode(resource: Resource?): String? {
        if (resource == null || !isMediaType(resource.mediaType) || !isInlinable(resource.mediaType)) return null
        return "data:${resource.mediaType};base64,${Base64.getEncoder().encodeToString(resource.bytes)}"
    }

    /** A `data:` URI the book wrote itself: only base64 images and fonts, rebuilt from its validated parts. */
    private fun ownDataUri(href: String): String? {
        val match = ownData.matchEntire(href.filter { !it.isWhitespace() }) ?: return null
        return "data:${match.groupValues[1].lowercase()};base64,${match.groupValues[2]}"
    }

    companion object {
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
