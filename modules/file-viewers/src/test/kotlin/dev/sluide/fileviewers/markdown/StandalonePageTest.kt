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
    fun `the only script of a standalone page follows the reader's colour scheme and adds the toggle button`() {
        val html = page("\$x\$\n\n```mermaid\ngraph TD; A-->B\n```\n", standalone = true)
        val doc = Jsoup.parse(html)
        assertTrue(doc.body().hasClass("standalone"))
        val script = doc.select("script").single().data()
        assertTrue(script.contains("matchMedia("))
        assertTrue(script.contains("theme-toggle"))
        assertFalse(script.contains("sluide"))
        val styles = doc.select("style").joinToString("\n") { it.data() }
        assertTrue(styles.contains(".theme-toggle"))
        assertTrue(Regex("""@media print\s*\{[^@]*\.theme-toggle[^}]*display:\s*none""").containsMatchIn(styles))
        // Neither the preview's script nor the libraries.
        assertFalse(html.contains("scrollToLine"))
        assertFalse(html.contains("renderDiagrams"))
        assertFalse(html.contains("renderMath"))
    }

    @Test
    fun `a standalone page has KaTeX's stylesheet only with typeset math`() {
        val katex = "KaTeX_Main"
        assertFalse(page("\$x\$", standalone = true).contains(katex))
        assertTrue(page("<span class=\"math-rendered\">x</span>", standalone = true).contains(katex))
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
        assertFalse(html.contains("theme-toggle"))
    }
}
