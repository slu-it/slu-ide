package dev.sluide.previews.markdown

import dev.sluide.previews.TextMarks
import dev.sluide.previews.preview.Vendor
import org.jsoup.Jsoup
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
    fun `YAML front matter is one highlighted code block and keeps the lines after it`() {
        val page = render("---\nname: delegate\ndescription: Use it\n---\n\n# Delegate\n\nText\n")

        val block = page.select("pre.front-matter")
        assertEquals(1, block.size)
        assertEquals("0", block.first()?.attr("data-line"))
        val code = block.select("code.language-yaml")
        assertEquals("name: delegate\ndescription: Use it\n", code.first()?.wholeText())
        assertTrue(code.select("span").isNotEmpty())
        assertTrue(page.select("hr, h2").isEmpty())
        assertEquals("5", page.selectFirst("h1")?.attr("data-line"))
        assertEquals("7", page.selectFirst("p")?.attr("data-line"))
    }

    @Test
    fun `TOML front matter is a TOML block`() {
        val page = render("+++\ntitle = \"Hi\"\n+++\n\nText\n")

        assertEquals(1, page.select("pre.front-matter code.language-toml").size)
        assertTrue(page.select("pre.front-matter span").isNotEmpty())
        assertEquals("4", page.selectFirst("p")?.attr("data-line"))
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
        // Only our own scripts are left: the one that PreviewFileView calls and the text marks.
        assertEquals(2, body.select("script").size)
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
    fun `the page brings its stylesheet and script, then the text marks`() {
        val page = render("text")

        assertTrue("body.dark" in page.select("style").html())
        val scripts = page.select("script").map { it.data() }
        assertTrue("scrollToLine" in scripts.first())
        assertEquals(TextMarks.SCRIPT.trim(), scripts.last().trim(), "after ours, which sets window.sluide anew")
    }

    /** The source offsets and texts of the text runs of the preview of [markdown]. */
    private fun runs(markdown: String) =
        render(markdown).select("span[data-o]").map { it.attr("data-o").toInt() to it.wholeText() }

    @Test
    fun `texts and inline code carry the source offset of their first char, through the sanitiser`() {
        val markdown = "# Title\n\nSome *em* [link](x.md) and `code`.\n\n- item\n"

        assertEquals(
            listOf(2 to "Title", 9 to "Some ", 15 to "em", 18 to " ", 20 to "link", 31 to " and ", 37 to "code") +
                listOf(42 to ".", 47 to "item"),
            runs(markdown),
        )
        assertEquals("code", render(markdown).selectFirst("code > span[data-o]")?.text(), "inside the code element")
    }

    @Test
    fun `escapes and entities start new runs, so that the chars after them keep their offsets`() {
        // a \*b\* &amp; c &eacute;t
        val markdown = "a \\*b\\* &amp; c &eacute;t"

        assertEquals(
            listOf(0 to "a ", 3 to "*b", 6 to "* ", 8 to "&", 13 to " c ", 16 to "\u00e9", 24 to "t"),
            runs(markdown),
        )
        assertEquals(listOf(2 to "&amp;"), runs("``&amp;``"), "an entity in code stays as written")
    }

    @Test
    fun `fenced code, math, diagrams and front matter have no runs, texts after front matter keep their offsets`() {
        val markdown =
            "---\na: b\n---\n\nText\n\n```kotlin\nval x = 1\n```\n\n\$x^2\$ and\n\n\$\$\ny\n\$\$\n\n" +
                "```math\nz\n```\n\n```mermaid\ngraph TD; A-->B\n```\n"
        val page = render(markdown)

        assertTrue(page.select("pre [data-o], .math [data-o]").isEmpty())
        assertEquals(listOf(markdown.indexOf("Text") to "Text", markdown.indexOf(" and") to " and"), runs(markdown))
        assertEquals(1, page.select("pre.mermaid").size)
        assertEquals("val x = 1\n", page.selectFirst("code.language-kotlin")?.wholeText())
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "- [ ] foo bar|6|foo bar",
            "- [x] done|6|done",
            "- top\\n  - [X] sub task|14|sub task",
            "- [x] x|6|x",
            "- [x] x-ray results|6|x-ray results",
            "- [X] X: done|6|X: done",
            "- [ ]   ] spaced|8|] spaced",
            "- [x] done\\n\\n  [ ] later|14|[ ] later",
        ],
    )
    fun `the text of a task item starts after its box`(
        written: String,
        offset: Int,
        text: String,
    ) {
        val markdown = written.replace("\\n", "\n")

        assertEquals(offset to text, runs(markdown).last())
    }

    @Test
    fun `offsets written in the file's HTML are dropped, so that only the runs carry offsets`() {
        val markdown =
            "Text <span data-o=\"0\">one</span> <span DATA-O=7 class=\"x\">two</span>\n\n" +
                "<div data-o='3'>\nThe data-o attribute <span/data-o=5>three</span>" +
                " <span class=\"y\"data-o=5>four</span>\n</div>\n"
        val page = render(markdown)

        assertEquals(listOf(0 to "Text ", 22 to "one", 32 to " ", 58 to "two"), runs(markdown))
        assertEquals("The data-o attribute three four", page.selectFirst("div")?.text(), "the file's text as written")
        assertEquals(listOf("x", "y"), page.select("span.x, span.y").map { it.className() })
    }

    @Test
    fun `only the preview of a file has runs, not the content of other pages`() {
        val body = MarkdownRenderer.body("Some *text*", dir)

        assertTrue(body.select("[data-o]").isEmpty())
    }
}
