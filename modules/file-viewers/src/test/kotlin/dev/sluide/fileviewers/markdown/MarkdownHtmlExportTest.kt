package dev.sluide.fileviewers.markdown

import org.jsoup.Jsoup
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownHtmlExportTest {
    @TempDir
    lateinit var dir: Path

    private val fetched = mutableListOf<URI>()
    private val png = byteArrayOf(1, 2, 3)

    private fun export(images: Map<String, FetchedImage> = emptyMap()) =
        MarkdownHtmlExport {
            fetched += it
            images[it.toString()]
        }

    private fun page(
        text: String,
        export: MarkdownHtmlExport = export(),
    ) = export.render(dir.resolve("notes.md"), text)

    @Test
    fun `the page and the stylesheet are named after the file`() {
        val export = export()
        assertEquals(Path("/a/notes.html"), export.target(Path("/a/notes.md")))
        assertEquals(Path("/a/README.html"), export.target(Path("/a/README.markdown")))
        assertEquals(Path("/a/notes.css"), export.stylesheet(Path("/a/notes.md")))
        assertEquals(Path("/a/README.css"), export.stylesheet(Path("/a/README.markdown")))
    }

    @Test
    fun `local and remote images are embedded`() {
        dir.resolve("local.png").writeBytes(png)
        val remote = FetchedImage("image/png", png)
        val page =
            page(
                "![a](local.png) ![b](https://example.test/b.png)",
                export(
                    mapOf("https://example.test/b.png" to remote),
                ),
            )
        val srcs = Jsoup.parse(page.html).select("img").map { it.attr("src") }
        assertTrue(srcs.all { it.startsWith("data:image/png;base64,") }, srcs.toString())
        assertEquals(emptyList(), page.notEmbedded)
    }

    @Test
    fun `images that cannot be embedded keep their source and are listed`() {
        val page = page("![a](https://example.test/a.png) ![b](missing.png) ![c](https://example.test/a.png)")
        val srcs = Jsoup.parse(page.html).select("img").map { it.attr("src") }
        assertEquals(listOf("https://example.test/a.png", "missing.png", "https://example.test/a.png"), srcs)
        assertEquals(listOf("https://example.test/a.png", "missing.png"), page.notEmbedded)
        assertEquals(1, fetched.size)
    }

    @Test
    fun `the stylesheet beside the file comes last`() {
        assertEquals(1, Jsoup.parse(page("# Hi").html).select("style").size)
        dir.resolve("notes.css").writeText("a::before { content: '</STYLE><b>' }")
        assertEquals(2, Jsoup.parse(page("# Hi").html).select("style").size)
        assertTrue(page("# Hi").html.contains("<\\/STYLE><b>"))
        dir.resolve("notes.css").writeText("h1 { color: red }")
        val styles = Jsoup.parse(page("# Hi").html).select("style").map { it.data() }
        assertEquals("h1 { color: red }", styles.last().trim())
        assertEquals(2, styles.size)
    }

    @Test
    fun `the page has the file name as title`() {
        assertEquals("notes.md", Jsoup.parse(page("# Hi").html).title())
    }

    @Test
    fun `math and diagrams are inlined and nothing is referenced`() {
        val doc = Jsoup.parse(page("```math\nx\n```\n\n```mermaid\ngraph TD; A-->B\n```\n").html)
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        assertTrue(scripts.contains("katex"))
        assertTrue(scripts.contains("mermaid"))
        assertTrue(doc.select("script[src]").isEmpty())
        assertTrue(doc.select("link[rel=stylesheet]").isEmpty())
    }
}
