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
)

internal typealias PageBuild = (EpubBook, Boolean, Boolean, String, IntRange?, Boolean) -> BookPage

internal sealed interface BookLoad {
    class Ready(
        val loaded: LoadedBook,
        /**
         * The page to show first (null in chapter mode). The view keeps it as its shown page; [LoadedBook] holds no
         * second copy of the html.
         */
        val first: ShownPage?,
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
    /** [BookPageBuilder.build]; a parameter so that a test can see which builds happen. */
    private val builder: PageBuild = BookPageBuilder::build,
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
        } catch (_: StackOverflowError) {
            BookLoad.Notice("This book is too deeply nested to open.")
        } catch (_: OutOfMemoryError) {
            BookLoad.Notice("This book is too large to open.")
        }

    private fun build(
        book: EpubBook,
        ownStyle: Boolean,
        dark: Boolean,
    ): BookLoad {
        // Measured without base64 of the images: an image-heavy book must not run out of memory before chapter mode.
        val light = measure(book, ownStyle, dark)
        val chapterMode = light.estimate > largeBookChars
        // Nothing left out: the measured html is the inline one. Else it is dropped before the inline build.
        val html =
            when {
                light.page.resourceRefs == 0 && !chapterMode -> {
                    light.takeHtml()
                }

                else -> {
                    light.takeHtml()
                    if (chapterMode) null else builder(book, ownStyle, dark, script, null, true).html
                }
            }
        return BookLoad.Ready(LoadedBook(book, light.page, chapterMode), html?.let { ShownPage(it, null, 0) })
    }

    private class Measured(
        val page: BookPage,
        val estimate: Long,
        private var html: String?,
    ) {
        fun takeHtml(): String? = html.also { html = null }
    }

    /** The whole book without images; its html is dropped from [Measured.page] so only one copy is alive. */
    private fun measure(
        book: EpubBook,
        ownStyle: Boolean,
        dark: Boolean,
    ): Measured {
        val built = builder(book, ownStyle, dark, script, null, false)
        val light =
            BookPage(
                "",
                built.text,
                built.skip,
                built.chapters,
                built.toc,
                built.anchors,
                built.anchorChapters,
                built.resourceRefs,
            )
        return Measured(light, built.estimatedInlineLength(), built.html)
    }

    /** Builds the page to show for [loaded]: the whole book, or in chapter mode the chapter at [chapter]. */
    fun render(
        loaded: LoadedBook,
        chapter: Int?,
        ownStyle: Boolean,
        dark: Boolean,
    ): ShownPage {
        if (!loaded.chapterMode) {
            return ShownPage(builder(loaded.book, ownStyle, dark, script, null, true).html, null, 0)
        }
        val index = (chapter ?: 0).coerceIn(0, loaded.page.chapters.size - 1)
        val span = loaded.page.chapters[index]
        val local = builder(loaded.book, ownStyle, dark, script, span.index..span.index, true)
        val localStart = local.chapters.firstOrNull()?.start ?: 0
        return ShownPage(local.html, index, span.start - localStart)
    }

    companion object {
        const val LARGE_BOOK_CHARS = 50_000_000
    }
}
