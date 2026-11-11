package dev.sluide.ebooks.view

import dev.sluide.ebooks.BookPage
import dev.sluide.ebooks.BookPageBuilder
import dev.sluide.ebooks.EpubBook
import dev.sluide.ebooks.EpubReader
import dev.sluide.ebooks.EpubResult
import java.nio.file.Path

/**
 * A book that was read and built; [page] is the whole book without its html (`html` is empty): text, skip ranges,
 * chapters, contents and anchors. The html lives only in the [ShownPage]s, one at a time.
 */
internal class LoadedBook(
    val book: EpubBook,
    val page: BookPage,
    /** Chapter mode: the page is too large and one chapter is built at a time. */
    val chapterMode: Boolean,
    /** The page to show first; null in chapter mode, where the view picks the chapter. */
    val first: ShownPage?,
)

internal sealed interface BookLoad {
    class Ready(
        val loaded: LoadedBook,
    ) : BookLoad

    /** The book cannot be shown; [text] is what the tab says instead. */
    class Notice(
        val text: String,
    ) : BookLoad
}

/**
 * Reads books and builds their pages, off the FX thread (ADR 0165). The whole book is built once; when its page is
 * longer than [largeBookChars] the view shows one chapter at a time: the whole-book text, chapters and contents are
 * kept (without the html), and each chapter's page is built on demand with `chapterRange = i..i`, its offsets starting
 * at 0; [ShownPage.shift] maps them to whole-book offsets.
 */
internal class BookLoading(
    private val script: String,
    private val largeBookChars: Int = LARGE_BOOK_CHARS,
) {
    fun load(
        file: Path,
        ownStyle: Boolean,
        dark: Boolean,
    ): BookLoad =
        try {
            when (val result = EpubReader.read(file)) {
                is EpubResult.Drm -> BookLoad.Notice("This book is DRM-protected and cannot be opened.")
                is EpubResult.Invalid -> BookLoad.Notice("Not a valid EPUB: ${result.reason}")
                is EpubResult.Book -> build(result.book, ownStyle, dark)
            }
        } catch (_: RuntimeException) {
            BookLoad.Notice("Not a valid EPUB: cannot read the book")
        } catch (_: OutOfMemoryError) {
            BookLoad.Notice("This book is too large to open.")
        }

    private fun build(
        book: EpubBook,
        ownStyle: Boolean,
        dark: Boolean,
    ): BookLoad {
        val page = BookPageBuilder.build(book, ownStyle, dark, script)
        val chapterMode = page.html.length > largeBookChars
        val shown = if (chapterMode) null else ShownPage(page.html, null, 0)
        val light = BookPage("", page.text, page.skip, page.chapters, page.toc, page.anchors)
        return BookLoad.Ready(LoadedBook(book, light, chapterMode, shown))
    }

    /** Builds the page to show for [loaded]: the whole book, or in chapter mode the chapter at [chapter]. */
    fun render(
        loaded: LoadedBook,
        chapter: Int?,
        ownStyle: Boolean,
        dark: Boolean,
    ): ShownPage {
        if (!loaded.chapterMode) {
            return ShownPage(BookPageBuilder.build(loaded.book, ownStyle, dark, script).html, null, 0)
        }
        val index = (chapter ?: 0).coerceIn(0, loaded.page.chapters.size - 1)
        val span = loaded.page.chapters[index]
        val local = BookPageBuilder.build(loaded.book, ownStyle, dark, script, span.index..span.index)
        val localStart = local.chapters.firstOrNull()?.start ?: 0
        return ShownPage(local.html, index, span.start - localStart)
    }

    companion object {
        const val LARGE_BOOK_CHARS = 50_000_000
    }
}
