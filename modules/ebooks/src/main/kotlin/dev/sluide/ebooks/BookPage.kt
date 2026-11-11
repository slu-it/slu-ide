package dev.sluide.ebooks

/**
 * The one HTML page of a book and its flat text (ADR 0165). Every text node of [html] is a `<span data-o="N">` whose
 * text equals `text.substring(N, N + length)`; the separators between blocks exist only in [text].
 * [skip] are the ranges of [text] not to be read aloud, [anchors] map the ids of [html] to text offsets.
 */
internal class BookPage(
    val html: String,
    val text: String,
    val skip: List<IntRange>,
    val chapters: List<ChapterSpan>,
    val toc: List<TocItem>,
    val anchors: Map<String, Int>,
    /** The spine index of the chapter each id of [anchors] is in: an offset alone cannot name a chapter without text. */
    val anchorChapters: Map<String, Int>,
    /** References to images and fonts a build with `inline = false` left out; with 0 its html is the inline one. */
    val resourceRefs: Int = 0,
    /** What those references add when inlined (never less), counted by a build with `inline = false`. */
    val resourceChars: Long = 0,
) {
    /** The length of [html] with its resources inlined, or a little more. */
    fun estimatedInlineLength(): Long = html.length + resourceChars
}

/** A chapter in [BookPage.text]: [index] is the spine index, [end] is exclusive. */
internal data class ChapterSpan(
    val index: Int,
    val title: String,
    val start: Int,
    val end: Int,
)

/** A table of contents entry resolved to an offset in [BookPage.text] and the spine index of its [chapter]. */
internal data class TocItem(
    val title: String,
    val offset: Int,
    val children: List<TocItem>,
    val chapter: Int,
)
