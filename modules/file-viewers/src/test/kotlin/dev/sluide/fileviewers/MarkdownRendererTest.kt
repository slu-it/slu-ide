package dev.sluide.fileviewers

import org.jsoup.Jsoup
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
        // Only our own script is left: the one that MarkdownFileView calls.
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
    fun `relative links and images resolve against the file's folder`() {
        val page = render("![logo](img/logo.png)")

        val base = page.selectFirst("base")?.attr("href").orEmpty()
        assertEquals(dir.toUri().toString().removeSuffix("/") + "/", base)
        assertEquals("img/logo.png", page.selectFirst("img")?.attr("src"))
    }

    @Test
    fun `the page brings its stylesheet and script`() {
        val page = render("text")

        assertTrue("body.dark" in page.select("style").html())
        assertTrue("scrollToLine" in page.select("script").html())
    }
}
