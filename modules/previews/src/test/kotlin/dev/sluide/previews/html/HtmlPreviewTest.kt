package dev.sluide.previews.html

import dev.sluide.previews.preview.LinkTarget
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlPreviewTest {
    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("index.html").apply { writeText("<h1>Hi</h1>") }

    private fun url(page: Path = file) = PreviewServer.url(dir, page).toString()

    @Test
    fun `the own page is the file at any anchor`() {
        assertTrue(HtmlPreview.isOwnPage(url(), file))
        assertTrue(HtmlPreview.isOwnPage(url() + "#top", file))
    }

    @Test
    fun `a query makes it another page, also before an anchor`() {
        assertFalse(HtmlPreview.isOwnPage(url() + "?r", file))
        assertFalse(HtmlPreview.isOwnPage(url() + "?r#top", file))
        // A question mark after the # belongs to the anchor.
        assertTrue(HtmlPreview.isOwnPage(url() + "#top?x", file))
    }

    @Test
    fun `no location, other sites, other files and deleted files are not the own page`() {
        val other = dir.resolve("other.html").apply { writeText("x") }

        assertFalse(HtmlPreview.isOwnPage(null, file))
        assertFalse(HtmlPreview.isOwnPage("https://example.com/", file))
        assertFalse(HtmlPreview.isOwnPage(url(other), file))
        // A page that is gone cannot be compared with the file (IOException), so it is not the own page.
        assertFalse(HtmlPreview.isOwnPage(url(dir.resolve("gone.html")), file))
    }

    @Test
    fun `a link to a page on the server leads to its file, other links as from the file on disk`() {
        val other = dir.resolve("other.html").apply { writeText("x") }

        assertEquals(LinkTarget.File(other), HtmlPreview.linkTarget(url(other), file))
        assertEquals(
            LinkTarget.WebPage(URI("https://example.com/a")),
            HtmlPreview.linkTarget("https://example.com/a", file),
        )
        assertEquals(LinkTarget.File(dir.resolve("style.css")), HtmlPreview.linkTarget("style.css#x", file))
    }
}
