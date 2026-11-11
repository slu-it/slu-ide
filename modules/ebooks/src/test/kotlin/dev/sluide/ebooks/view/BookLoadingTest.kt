package dev.sluide.ebooks.view

import dev.sluide.ebooks.BookPageBuilder
import dev.sluide.ebooks.TestChapter
import dev.sluide.ebooks.TestEpub
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookLoadingTest {
    @TempDir
    lateinit var dir: Path

    private val chapters =
        listOf(
            TestChapter("a.xhtml", "<h1>Alpha</h1><p>First chapter text.</p>"),
            TestChapter("b.xhtml", "<h1>Beta</h1><p>Second chapter text.</p>"),
            TestChapter("c.xhtml", "<h1>Gamma</h1><p>Third chapter text.</p>"),
        )

    private fun readyLoad(
        threshold: Int,
        loading: BookLoading = BookLoading("/*js*/", threshold),
        epub: TestEpub = TestEpub(chapters),
    ) = assertIs<BookLoad.Ready>(loading.load(epub.write(dir), ownStyle = false, dark = false))

    private fun ready(
        threshold: Int,
        loading: BookLoading = BookLoading("/*js*/", threshold),
    ) = readyLoad(threshold, loading).loaded

    @Test
    fun `a small book is one page with whole-book offsets`() {
        val load = readyLoad(BookLoading.LARGE_BOOK_CHARS)
        val book = load.loaded
        assertFalse(book.chapterMode)
        val first = assertNotNull(load.first)
        assertNull(first.chapter)
        assertEquals("", book.page.html)
        assertEquals(0, first.shift)
        assertTrue(first.html.contains("Second chapter text."))
    }

    @Test
    fun `a page above the threshold switches to one chapter at a time keeping the whole text`() {
        val whole = ready(BookLoading.LARGE_BOOK_CHARS)
        val load = readyLoad(100)
        val book = load.loaded
        assertTrue(book.chapterMode)
        assertNull(load.first)
        assertEquals("", book.page.html)
        assertEquals(whole.page.text, book.page.text)
        assertEquals(whole.page.chapters, book.page.chapters)
        assertEquals(whole.page.toc, book.page.toc)
    }

    @Test
    fun `a chapter page maps its local offsets to the whole book`() {
        val loading = BookLoading("/*js*/", 100)
        val book = ready(100, loading)
        val text = book.page.text
        for (index in book.page.chapters.indices) {
            val shown = loading.render(book, index, ownStyle = false, dark = false)
            assertEquals(index, shown.chapter)
            val span = book.page.chapters[index]
            assertTrue(shown.html.contains("data-o=\"${shown.toLocal(span.start)}\""))
            // The chapter's own words lie at the mapped offsets of the whole text.
            val word = listOf("Alpha", "Beta", "Gamma")[index]
            assertEquals(word, text.substring(span.start, span.start + word.length))
            assertFalse(listOf("Alpha", "Beta", "Gamma").filter { it != word }.any { shown.html.contains(">$it<") })
        }
    }

    @Test
    fun `drm and invalid books give a notice`() {
        val loading = BookLoading("/*js*/")
        val drm = TestEpub(chapters, rights = true).write(dir, "drm.epub")
        assertEquals(
            "This book is DRM-protected and cannot be opened.",
            assertIs<BookLoad.Notice>(loading.load(drm, false, false)).text,
        )
        val broken = Files.writeString(dir.resolve("broken.epub"), "not a zip")
        assertTrue(assertIs<BookLoad.Notice>(loading.load(broken, false, false)).text.startsWith("Not a valid EPUB: "))
    }

    @Test
    fun `an image-heavy book goes into chapter mode by an estimate, without building the inline page`() {
        val builds = mutableListOf<Boolean>()
        val loading =
            BookLoading("/*js*/", 100_000) { book, ownStyle, dark, script, range, inline ->
                builds += inline
                BookPageBuilder.build(book, ownStyle, dark, script, range, inline)
            }
        val epub =
            TestEpub(
                listOf(TestChapter("a.xhtml", "<p>Text</p><img src=\"images/big.png\" alt=\"\"/>")),
                resources = mapOf("images/big.png" to ByteArray(200_000)),
            )
        val load = readyLoad(100_000, loading, epub)
        assertTrue(load.loaded.chapterMode)
        assertEquals(listOf(false), builds)
        // A small book with images is built twice: measured without them, then inline for the view.
        builds.clear()
        val small =
            readyLoad(
                100_000,
                loading,
                TestEpub(
                    listOf(TestChapter("a.xhtml", "<p>Text</p><img src=\"images/a.png\" alt=\"\"/>")),
                    resources = mapOf("images/a.png" to TestEpub.PNG),
                ),
            )
        assertFalse(small.loaded.chapterMode)
        assertTrue(assertNotNull(small.first).html.contains("data:image/png;base64,"))
        assertEquals(listOf(false, true), builds)
        // Without any image the measured page is the page: no second build.
        builds.clear()
        val text = readyLoad(100_000, loading, TestEpub(chapters))
        assertTrue(assertNotNull(text.first).html.contains("Second chapter text."))
        assertEquals(listOf(false), builds)
    }
}
