package dev.sluide.fileviewers.markdown

import dev.sluide.fileviewers.preview.Vendor
import org.jsoup.Jsoup
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownHtmlExportTest {
    @TempDir
    lateinit var dir: Path

    private val fetched = mutableListOf<URI>()
    private val png = byteArrayOf(1, 2, 3)

    /** What the fake renderer was asked for. */
    private val asked = mutableListOf<Pair<List<MathSource>, List<String>>>()

    /**
     * Typesets `$tex` as `<b>tex</b>`, but not `nope`; draws a diagram as an SVG naming its theme, or as [EVIL_SVG] if
     * it says `evil`; fails on one that says `bad` (Mermaid's error) or `slow` (timed out).
     */
    private val renderer =
        ExportRenderer { math, diagrams ->
            asked += math to diagrams
            ExportRendering(
                math.map { if (it.tex == "nope") null else "<b class=\"katex\">${it.tex}</b>" },
                diagrams.map {
                    if ("bad" in it) {
                        DrawnDiagram(null, null, "Parse error <here>")
                    } else if ("slow" in it) {
                        DrawnDiagram(null, null, "Timed out", failed = true)
                    } else if ("evil" in it) {
                        DrawnDiagram(EVIL_SVG, EVIL_SVG, null)
                    } else {
                        DrawnDiagram(
                            "<svg id=\"l\"><text>light</text></svg>",
                            "<svg id=\"d\"><text>dark</text></svg>",
                            null,
                        )
                    }
                },
            )
        }

    private fun export(images: Map<String, FetchedImage> = emptyMap()) =
        MarkdownHtmlExport(
            {
                fetched += it
                images[it.toString()]
            },
            renderer,
        )

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
        assertEquals(emptyList(), page.problems)
    }

    @Test
    fun `images that cannot be embedded keep their source and are listed`() {
        val page = page("![a](https://example.test/a.png) ![b](missing.png) ![c](https://example.test/a.png)")
        val srcs = Jsoup.parse(page.html).select("img").map { it.attr("src") }
        assertEquals(listOf("https://example.test/a.png", "missing.png", "https://example.test/a.png"), srcs)
        assertEquals(
            listOf("Image not embedded: https://example.test/a.png", "Image not embedded: missing.png"),
            page.problems,
        )
        assertEquals(1, fetched.size)
    }

    @Test
    fun `the stylesheet beside the file comes last`() {
        assertEquals(2, Jsoup.parse(page("# Hi").html).select("style").size)
        dir.resolve("notes.css").writeText("a::before { content: '</STYLE><b>' }")
        assertEquals(3, Jsoup.parse(page("# Hi").html).select("style").size)
        assertTrue(page("# Hi").html.contains("<\\/STYLE><b>"))
        dir.resolve("notes.css").writeText("h1 { color: red }")
        val styles = Jsoup.parse(page("# Hi").html).select("style").map { it.data() }
        assertEquals("h1 { color: red }", styles.last().trim())
        assertEquals(3, styles.size)
    }

    @Test
    fun `the page has the file name as title`() {
        assertEquals("notes.md", Jsoup.parse(page("# Hi").html).title())
    }

    @Test
    fun `math is typeset and diagrams are drawn in both variants`() {
        val page = page("Inline \$x\$.\n\n```math\ny\n```\n\n```mermaid\ngraph TD; A-->B\n```\n")
        assertEquals(listOf("x" to false, "y" to true), asked.single().first.map { it.tex to it.display })
        assertEquals(listOf("graph TD; A-->B\n"), asked.single().second)
        val doc = Jsoup.parse(page.html)
        assertEquals(listOf("span", "div"), doc.select(".math-rendered").map { it.tagName() })
        assertEquals(listOf(false, true), doc.select(".math-rendered").map { it.hasClass("display") })
        assertEquals("x", doc.selectFirst("span.math-rendered > b.katex")?.text())
        assertTrue(doc.select(".math").isEmpty())
        val diagram = doc.select("div.diagram").single()
        assertEquals("light", diagram.selectFirst("> div.diagram-light > svg")?.text())
        assertEquals("dark", diagram.selectFirst("> div.diagram-dark > svg")?.text())
        assertFalse(diagram.hasAttr("data-line"))
        assertTrue(doc.select("pre.mermaid").isEmpty())
        assertEquals(emptyList(), page.problems)
    }

    @Test
    fun `the page has no script but the theme script and references nothing`() {
        val html = page("Inline \$x\$.\n\n```mermaid\ngraph TD; A-->B\n```\n").html
        val doc = Jsoup.parse(html)
        assertTrue(
            doc
                .select("script")
                .single()
                .data()
                .contains("matchMedia("),
        )
        assertTrue(doc.select("[src]:not(img), link").isEmpty())
        assertFalse(html.contains(Vendor.mermaidJs.take(SAMPLE)))
        assertFalse(html.contains(Vendor.katexJs.take(SAMPLE)))
    }

    @Test
    fun `the page is small, apart from KaTeX's fonts with math`() {
        val diagram = page("```mermaid\ngraph TD; A-->B\n```\n").html
        assertTrue(diagram.length < SMALL, "${diagram.length}")
        assertFalse(diagram.contains("KaTeX_Main"))
        val math = page("\$x\$\n\n```mermaid\ngraph TD; A-->B\n```\n").html
        assertTrue(math.contains("KaTeX_Main"))
        assertTrue(math.length < SMALL + Vendor.katexCss.length, "${math.length}")
    }

    @Test
    fun `a diagram that cannot be drawn shows its error and is reported`() {
        val page = page("```mermaid\ngraph TD; A-->B\n```\n\n```mermaid\nbad\n```\n")
        val doc = Jsoup.parse(page.html)
        assertEquals(1, doc.select("div.diagram").size)
        assertEquals("Parse error <here>", doc.select("pre.mermaid.error").single().text())
        assertTrue(doc.select("pre.mermaid.failed").isEmpty())
        assertEquals(listOf("Diagram 2 could not be drawn: Parse error <here>"), page.problems)
    }

    @Test
    fun `a diagram that failed for another reason than its syntax is marked`() {
        val page = page("```mermaid\nslow\n```\n")
        assertEquals(
            "Timed out",
            Jsoup
                .parse(page.html)
                .select("pre.mermaid.error.failed")
                .single()
                .text(),
        )
        assertEquals(listOf("Diagram 1 could not be drawn: Timed out"), page.problems)
    }

    @Test
    fun `inline display math stays in its paragraph`() {
        val doc = Jsoup.parse(page("Text \$\$x\$\$ more.\n").html)
        val paragraph = doc.select("p").single()
        assertEquals("x", paragraph.select("> span.math-rendered.display").single().text())
        assertTrue(paragraph.text().endsWith(" more."), paragraph.text())
    }

    @Test
    fun `math that is not typeset keeps its source and is reported once`() {
        val page = page("\$nope\$ and \$x\$ and\n\n```math\nnope\n```\n")
        val doc = Jsoup.parse(page.html)
        assertEquals(listOf("nope", "nope"), doc.select(".math").map { it.text() })
        assertEquals(1, doc.select(".math-rendered").size)
        assertEquals(listOf("Math could not be typeset"), page.problems)
    }

    @Test
    fun `drawings are inserted as they are`() {
        val page = page("```mermaid\nevil\n```\n")
        assertTrue(page.html.contains("<div class=\"diagram-light\">$EVIL_SVG</div>"))
        val doc = Jsoup.parse(page.html)
        assertTrue(doc.select("img").isEmpty())
        assertEquals(2, doc.select("div.diagram svg").size)
    }

    private companion object {
        /** An SVG whose style holds escaped markup, which would become an element if it were unescaped. */
        const val EVIL_SVG = "<svg><style>a{content:\"&lt;img src=x onerror=alert(1)&gt;\"}</style></svg>"

        /** The largest page without math, in characters. */
        const val SMALL = 200_000

        /** How much of a library is looked for. */
        const val SAMPLE = 200
    }
}
