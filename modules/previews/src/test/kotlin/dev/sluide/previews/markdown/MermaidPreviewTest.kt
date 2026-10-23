package dev.sluide.previews.markdown

import dev.sluide.previews.preview.Vendor
import org.jsoup.Jsoup
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MermaidPreviewTest {
    private val source = "graph TD\n  A[\"<b>bold</b>\"] --> B<script>alert(1)</script>\n  %% \"quoted\" & more\n"

    @Test
    fun `the page draws the file as one diagram that shows its errors`() {
        val page = MermaidPreview.render(source, dark = false)
        val document = Jsoup.parse(page)
        val diagram = assertNotNull(document.selectFirst("pre.mermaid"))

        assertEquals(1, document.select("pre.mermaid").size)
        assertTrue(diagram.hasAttr("data-errors"))
        assertEquals(source, diagram.attr("data-source"))
        assertEquals(source, diagram.wholeText())
        assertTrue(Vendor.mermaidJs in page && "sluide.renderDiagrams()" in page)
        assertTrue(document.body().hasClass("mermaid-file") && document.body().hasClass("zoomable"))
        assertTrue(!document.body().hasClass("dark"))
        assertTrue(Jsoup.parse(MermaidPreview.render(source, dark = true)).body().hasClass("dark"))
    }

    @Test
    fun `the source is escaped, never markup of the page`() {
        val document = Jsoup.parse(MermaidPreview.render(source, dark = false))

        assertEquals(emptyList(), document.select("pre.mermaid b, pre.mermaid script").toList())
        // The page's own scripts only: our script, Mermaid and the call that starts it.
        assertEquals(3, document.select("script").size)
        assertTrue(document.select("script").none { "alert(1)" in it.data() })
    }

    @Test
    fun `Markdown diagrams keep hiding their errors`() {
        val markdown =
            Jsoup.parse(
                MarkdownRenderer.render("```mermaid\ngraph TD; A-->\n```\n", Path(".")),
            )

        assertTrue(markdown.select("pre.mermaid").none { it.hasAttr("data-errors") })
    }

    @Test
    fun `linked node ids are listed on the diagram, which has none without them`() {
        val linked = Jsoup.parse(MermaidPreview.render(source, dark = false, links = linkedSetOf("a", "b1")))
        val plain = Jsoup.parse(MermaidPreview.render(source, dark = false))

        assertEquals("a b1", linked.selectFirst("pre.mermaid")?.attr("data-node-links"))
        assertTrue(plain.select("pre.mermaid[data-node-links]").isEmpty())
    }

    @Test
    fun `side links are listed on the diagram as from and to, which has none without them`() {
        val sided =
            Jsoup.parse(
                MermaidPreview.render(source, dark = false, sideLinks = listOf("ai" to "a", "bi" to "b1")),
            )
        val plain = Jsoup.parse(MermaidPreview.render(source, dark = false))

        assertEquals("ai>a bi>b1", sided.selectFirst("pre.mermaid")?.attr("data-side-links"))
        assertTrue(plain.select("pre.mermaid[data-side-links]").isEmpty())
    }
}
