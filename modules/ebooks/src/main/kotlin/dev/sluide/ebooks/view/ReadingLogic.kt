package dev.sluide.ebooks.view

import dev.sluide.ebooks.BookPage
import dev.sluide.ebooks.ChapterSpan
import dev.sluide.ebooks.TocItem

/**
 * The page the WebView shows: the whole book ([chapter] null, [shift] 0) or one chapter of a large book. Offsets in
 * the page are local; the whole-book offset is the local one plus [shift] (ADR 0165).
 */
internal class ShownPage(
    val html: String,
    val chapter: Int?,
    val shift: Int,
) {
    fun toWhole(local: Int) = local + shift

    fun toLocal(whole: Int) = whole - shift
}

/** The table of contents entries shown in the Contents panel: the book's, else one per chapter. */
internal fun contentsItems(page: BookPage): List<TocItem> =
    page.toc.ifEmpty { page.chapters.map { TocItem(it.title, it.start, emptyList()) } }

/** The deepest entry (the last in reading order) whose offset is at or before [position]; null before the first. */
internal fun contentsItemAt(
    items: List<TocItem>,
    position: Int,
): TocItem? {
    var found: TocItem? = null

    fun walk(list: List<TocItem>) {
        for (item in list) {
            if (item.offset <= position) found = item
            walk(item.children)
        }
    }
    walk(items)
    return found
}

/** The list position of the chapter containing [offset]: the last one starting at or before it, 0 if before all. */
internal fun chapterAt(
    chapters: List<ChapterSpan>,
    offset: Int,
): Int = chapters.indexOfLast { it.start <= offset }.coerceAtLeast(0)

/** "Chapter title · 37 %" for [position] in a book with [textLength] chars; just the percentage without chapters. */
internal fun progressText(
    chapters: List<ChapterSpan>,
    textLength: Int,
    position: Int,
): String {
    val percent = if (textLength <= 0) 0 else (position.coerceIn(0, textLength).toLong() * 100 / textLength).toInt()
    val title = chapters.getOrNull(chapterAt(chapters, position))?.title
    return if (title.isNullOrBlank()) "$percent %" else "$title · $percent %"
}

/**
 * The part of the whole-book range `[start, end)` that this page shows, as local offsets (end exclusive); null if
 * none of it is on the page. A whole-book page shows everything, a chapter page only its chapter of [chapters].
 */
internal fun ShownPage.localRange(
    chapters: List<ChapterSpan>,
    start: Int,
    end: Int,
): Pair<Int, Int>? {
    val span = chapter?.let { chapters.getOrNull(it) }
    val from = maxOf(start, span?.start ?: 0)
    val to = minOf(end, span?.end ?: Int.MAX_VALUE)
    return if (from < to) toLocal(from) to toLocal(to) else null
}

/** The local offset of the whole-book [offset], or null if this page does not show it (another chapter). */
internal fun ShownPage.localOffset(
    chapters: List<ChapterSpan>,
    offset: Int,
): Int? {
    val span = chapter?.let { chapters.getOrNull(it) } ?: return toLocal(offset)
    return if (offset in span.start..span.end) toLocal(offset) else null
}
