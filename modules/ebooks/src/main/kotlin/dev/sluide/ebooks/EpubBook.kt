package dev.sluide.ebooks

/** A file of the EPUB that the page may inline: its media type from the manifest (or the extension) and its bytes. */
internal class Resource(
    val mediaType: String,
    val bytes: ByteArray,
)

/**
 * An EPUB read into memory (the zip is closed after reading). [resources] holds the manifest items that are not
 * chapters, keyed by their normalised zip path (the same form as [Chapter.href] and [TocEntry.href] without fragment).
 */
internal class EpubBook(
    val title: String,
    val language: String?,
    val fixedLayout: Boolean,
    val chapters: List<Chapter>,
    val toc: List<TocEntry>,
    val resources: Map<String, Resource>,
)

/** A spine item; [href] is its zip path, [title] the title of the table of contents entry that points at it. */
internal data class Chapter(
    val href: String,
    val title: String?,
    val xhtml: String,
)

/** A table of contents entry; [href] is a zip path that may carry a `#fragment`. */
internal data class TocEntry(
    val title: String,
    val href: String,
    val children: List<TocEntry>,
)

internal sealed interface EpubResult {
    data class Book(
        val book: EpubBook,
    ) : EpubResult

    /** The book is protected (rights.xml or an encryption other than font obfuscation); Thoreau does not open it. */
    data object Drm : EpubResult

    /** [reason] is short, user-facing and lower case; the UI prefixes "Not a valid EPUB: ". */
    data class Invalid(
        val reason: String,
    ) : EpubResult
}
