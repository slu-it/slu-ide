package dev.sluide.fileviewers.markdown

import org.jsoup.Jsoup
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandalonePageTest {
    @TempDir
    lateinit var dir: Path

    private fun page(
        markdown: String,
        standalone: Boolean,
        title: String? = null,
        styles: List<String> = emptyList(),
    ) = MarkdownRenderer.page(MarkdownRenderer.body(markdown, dir), false, standalone, title, styles)

    @Test
    fun `the title is escaped`() {
        val doc = Jsoup.parse(page("# Hi", standalone = true, title = "a <b> & c.md"))
        assertEquals("a <b> & c.md", doc.title())
        assertTrue(page("# Hi", true, "a <b>").contains("<title>a &lt;b&gt;</title>"))
    }

    @Test
    fun `a standalone page follows the reader's colour scheme before diagrams are drawn`() {
        val html = page("```mermaid\ngraph TD; A-->B\n```\n", standalone = true)
        assertTrue(Jsoup.parse(html).body().hasClass("standalone"))
        val theme = html.indexOf("matchMedia(")
        assertTrue(theme > 0)
        assertTrue(theme < html.lastIndexOf("sluide.renderDiagrams();"))
    }

    @Test
    fun `extra styles come after the built-in ones`() {
        val html = page("\$x\$ and \$\$y\$\$\n\n```math\nz\n```\n", standalone = true, styles = listOf("/*mine*/"))
        val styles = Jsoup.parse(html).select("style").map { it.data() }
        assertEquals("/*mine*/", styles.last().trim())
        assertTrue(styles.size >= 2)
    }

    @Test
    fun `a page that is not standalone has neither the class nor the theme script`() {
        val html = page("# Hi", standalone = false)
        assertFalse(Jsoup.parse(html).body().hasClass("standalone"))
        assertFalse(html.contains("matchMedia("))
    }
}
