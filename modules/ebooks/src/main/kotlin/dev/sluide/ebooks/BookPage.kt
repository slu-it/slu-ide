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
)

/** A chapter in [BookPage.text]: [index] is the spine index, [end] is exclusive. */
internal data class ChapterSpan(
    val index: Int,
    val title: String,
    val start: Int,
    val end: Int,
)

/** A table of contents entry resolved to an offset in [BookPage.text]. */
internal data class TocItem(
    val title: String,
    val offset: Int,
    val children: List<TocItem>,
)
