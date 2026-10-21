package dev.sluide.previews.markdown

import dev.sluide.previews.preview.Vendor
import org.jsoup.Jsoup
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkdownRendererTest {
    @TempDir
    lateinit var dir: Path

    private fun render(markdown: String) = Jsoup.parse(MarkdownRenderer.render(markdown, dir))

    @Test
    fun `blocks carry their first source line`() {
        val page = render("# Title\n\nSome text\nmore text\n\n## Next\n\n- one\n- two\n")

        assertEquals("0", page.selectFirst("h1")?.attr("data-line"))
        assertEquals("2", page.selectFirst("p")?.attr("data-line"))
        assertEquals("5", page.selectFirst("h2")?.attr("data-line"))
        assertEquals(listOf("7", "8"), page.select("li").map { it.attr("data-line") })
    }

    @Test
    fun `GitHub tables, task lists, strikethrough, autolinks and heading anchors`() {
        val page =
            render(
                "# Getting Started\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n- [x] done\n- [ ] open\n\n~~old~~ https://example.com\n",
            )

        assertEquals(listOf("1", "2"), page.select("table td").map { it.text() })
        val boxes = page.select("li input[type=checkbox]")
        assertEquals(listOf(true, false), boxes.map { it.hasAttr("checked") })
        assertTrue(boxes.all { it.hasAttr("disabled") })
        assertEquals("old", page.selectFirst("del")?.text())
        assertEquals("https://example.com", page.selectFirst("a")?.attr("href"))
        assertEquals("getting-started", page.selectFirst("h1")?.id())
    }

    @Test
    fun `fenced code with a known language is highlighted`() {
        val page = render("```kotlin\nfun main() = println(\"<hi>\")\n```\n\n```\nplain <b>\n```\n")

        val (kotlin, plain) = page.select("pre code")
        assertEquals("language-kotlin", kotlin.className())
        assertEquals("fun", kotlin.selectFirst("span.keyword")?.text())
        assertEquals("\"<hi>\"", kotlin.selectFirst("span.string")?.text())
        assertEquals("plain <b>\n", plain.wholeText())
        assertTrue(plain.select("span").isEmpty())
    }

    @Test
    fun `scripts, event handlers and javascript links are removed, other HTML stays`() {
        val page =
            render(
                """
                |<script>alert(1)</script>
                |
                |<details open><summary>More</summary><img src="logo.png" onerror="alert(2)"></details>
                |
                |[bad](javascript:alert(3)) <a href="other.md" onclick="alert(4)">other</a> <kbd>Ctrl</kbd>
                |
                """.trimMargin(),
            )

        val body = page.body()
        // Only our own script is left: the one that PreviewFileView calls.
        assertEquals(1, body.select("script").size)
        assertFalse("alert(1)" in body.select("script").html())
        assertNotNull(page.selectFirst("details[open] > summary"))
        val image = assertNotNull(page.selectFirst("img"))
        assertEquals("logo.png", image.attr("src"))
        assertFalse(image.hasAttr("onerror"))
        val (bad, other) = page.select("a")
        assertFalse(bad.hasAttr("href"))
        assertEquals("other.md", other.attr("href"))
        assertNull(other.attributes().firstOrNull { it.key.startsWith("on") })
        assertEquals("Ctrl", page.selectFirst("kbd")?.text())
    }

    @Test
    fun `local images are embedded, other images and links stay as written`() {
        dir
            .resolve("img")
            .createDirectories()
            .resolve("logo.png")
            .writeBytes(byteArrayOf(1, 2, 3))

        val page = render("![a](img/logo.png) ![b](missing.png) ![c](https://example.com/c.png) [doc](docs/other.md)")

        val sources = page.select("img").map { it.attr("src") }
        assertEquals("data:image/png;base64,AQID", sources[0])
        assertEquals(listOf("missing.png", "https://example.com/c.png"), sources.drop(1))
        assertEquals("docs/other.md", page.selectFirst("a")?.attr("href"))
        // No base: anchors (#part) must stay on the page.
        assertNull(page.selectFirst("base"))
    }

    @Test
    fun `local images over 10 MB are not embedded`() {
        RandomAccessFile(dir.resolve("big.png").toFile(), "rw").use { it.setLength(10L * 1024 * 1024 + 1) }
        dir.resolve("small.png").writeBytes(byteArrayOf(1))

        val sources = render("![big](big.png) ![small](small.png)").select("img").map { it.attr("src") }

        assertEquals(listOf("big.png", "data:image/png;base64,AQ=="), sources)
    }

    @Test
    fun `the page is rendered in the requested colour scheme`() {
        assertTrue(Jsoup.parse(MarkdownRenderer.render("x", dir, dark = true)).body().hasClass("dark"))
        assertFalse(render("x").body().hasClass("dark"))
    }

    @Test
    fun `active content of every kind is removed`() {
        val page =
            render(
                """
                |<iframe src="https://example.com"></iframe><object data="x.swf"></object><embed src="x.swf">
                |<svg onload="alert(1)"><circle r="5"/></svg>
                |<form action="https://example.com"><input name="q"></form>
                |<meta http-equiv="refresh" content="0; url=https://example.com">
                |<p style="position:fixed">styled</p>
                |<a href="vbscript:msgbox(1)">vb</a> <a href="data:text/html,<script>alert(1)</script>">data</a>
                |
                """.trimMargin(),
            )

        val body = page.body()
        for (tag in listOf("iframe", "object", "embed", "svg", "form", "input", "meta")) {
            assertTrue(body.select(tag).isEmpty(), tag)
        }
        assertFalse(assertNotNull(body.selectFirst("p")).hasAttr("style"))
        assertTrue(body.select("a").none { it.hasAttr("href") })
    }

    @Test
    fun `inputs other than checkboxes are removed`() {
        val page =
            render(
                "<input type=\"text\" value=\"x\"> <input type=\"file\"> <input> " +
                    "<input type=\"CheckBox\" checked>\n\n- [x] done\n",
            )

        val inputs = page.select("input")
        assertEquals(2, inputs.size)
        assertTrue(inputs.all { it.attr("type").equals("checkbox", ignoreCase = true) })
    }

    @Test
    fun `mermaid fences become diagrams, with Mermaid only on pages that have one`() {
        val withDiagram = MarkdownRenderer.render("Intro\n\n```mermaid\ngraph TD; A-->B<script>\n```\n", dir)
        val diagram = Jsoup.parse(withDiagram).selectFirst("pre.mermaid")

        assertEquals("graph TD; A-->B<script>\n", diagram?.attr("data-source"))
        assertEquals("graph TD; A-->B<script>\n", diagram?.wholeText())
        assertEquals("2", diagram?.attr("data-line"))
        assertTrue(Vendor.mermaidJs in withDiagram && "sluide.renderDiagrams()" in withDiagram)
        assertFalse(Vendor.mermaidJs in MarkdownRenderer.render("```kotlin\nval x = 1\n```\n", dir))
    }

    @Test
    fun `the page brings its stylesheet and script`() {
        val page = render("text")

        assertTrue("body.dark" in page.select("style").html())
        assertTrue("scrollToLine" in page.select("script").html())
    }
}
