package dev.sluide.ebooks

import org.jsoup.Jsoup
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BookPageBuilderTest {
    @TempDir
    lateinit var dir: Path

    private fun book(epub: TestEpub): EpubBook = assertIs<EpubResult.Book>(EpubReader.read(epub.write(dir))).book

    private fun page(
        vararg bodies: String,
        ownStyle: Boolean = false,
        dark: Boolean = false,
        range: IntRange? = null,
        resources: Map<String, ByteArray> = emptyMap(),
        head: String = "",
        toc: List<TestToc>? = null,
        script: String = "/*js*/",
    ): BookPage {
        val chapters = bodies.mapIndexed { i, body -> TestChapter("c$i.xhtml", body, head, title = "T$i") }
        val epub = TestEpub(chapters, resources = resources, toc = toc)
        return BookPageBuilder.build(book(epub), ownStyle, dark, script, range)
    }

    /** The contract: a span's text is the flat text at its offset, spans are ordered, only separators lie between. */
    private fun assertOffsetContract(page: BookPage) {
        val spans = Jsoup.parse(page.html).select("[data-o]")
        var end = 0
        for (span in spans) {
            val offset = span.attr("data-o").toInt()
            val content = span.wholeText()
            assertEquals(page.text.substring(offset, offset + content.length), content, "span at $offset")
            assertTrue(offset >= end, "span at $offset overlaps the one before")
            val between = page.text.substring(end, offset)
            assertTrue(between.all { it == '\n' }, "only separators between spans: '$between'")
            if (span.parents().none { it.tagName() == "pre" }) assertFalse("\n\n" in content)
            end = offset + content.length
        }
        assertEquals(page.text.length, end, "the text has no tail outside spans")
    }

    @Test
    fun `blocks are separated by a blank line, br by a line break, whitespace is normalised`() {
        val page =
            page("<h1>Title</h1><p>  Hello <em>big</em>   world.  </p><p> Next<br/>line</p><div><div>deep</div></div>")
        assertEquals("Title\n\nHello big world.\n\nNext\nline\n\ndeep", page.text)
        assertOffsetContract(page)
    }

    @Test
    fun `a space between inline elements stays, entities are decoded`() {
        val page = page("<p><b>bold</b> <i>it</i> a &amp; b &lt; c</p>")
        assertEquals("bold it a & b < c", page.text)
        assertOffsetContract(page)
    }

    @Test
    fun `pre keeps its text as written`() {
        val page = page("<p>before</p><pre>a  b\n\n  c</pre><p>after</p>")
        assertEquals("before\n\na  b\n\n  c\n\nafter", page.text)
        assertOffsetContract(page)
    }

    @Test
    fun `chapters are separated like blocks and have spans with titles`() {
        val page = page("<h1>Alpha</h1><p>one</p>", "<p>two</p>")
        assertEquals("Alpha\n\none\n\ntwo", page.text)
        assertEquals(
            listOf(ChapterSpan(0, "T0", 0, 10), ChapterSpan(1, "T1", 12, 15)),
            page.chapters,
        )
        assertEquals(listOf("ch0", "ch1"), Jsoup.parse(page.html).select("section").map { it.id() })
        assertOffsetContract(page)
    }

    @Test
    fun `chapter titles fall back to the first heading, then to the number`() {
        val chapters =
            listOf(
                TestChapter("a.xhtml", "<h2>Heading</h2><p>x</p>"),
                TestChapter("b.xhtml", "<p>y</p>"),
            )
        // The reader's spine fallback would title the chapters, so strip the titles by hand.
        val read = book(TestEpub(chapters, toc = emptyList()))
        val untitled = read.chapters.map { it.copy(title = null) }
        val bare = EpubBook(read.title, null, false, untitled, emptyList(), read.resources)
        val page = BookPageBuilder.build(bare, false, false, "")
        assertEquals(listOf("Heading", "Chapter 2"), page.chapters.map { it.title })
    }

    @Test
    fun `skip ranges cover note references, tables and page breaks, not footnote bodies`() {
        val page =
            page(
                "<p>Text<a epub:type=\"noteref\" href=\"#n1\">1</a> more<sup><a href=\"#n2\">[2]</a></sup>.</p>" +
                    "<table><tr><td>cell</td><td>two</td></tr></table>" +
                    "<p>Mid<span epub:type=\"pagebreak\" title=\"12\">p. 12</span> dle</p>" +
                    "<p>Role<a role=\"doc-noteref\" href=\"#n1\">3</a></p>" +
                    "<aside epub:type=\"footnote\" id=\"n1\"><p>The footnote.</p></aside>",
            )
        val skipped = page.skip.map { page.text.substring(it.first, it.last + 1) }
        assertEquals(listOf("1", "[2]", "cell\n\ntwo", "p. 12", "3"), skipped)
        assertTrue("The footnote." in page.text)
        assertFalse("title" in page.html.substringAfter("<body"))
        assertOffsetContract(page)
    }

    @Test
    fun `a link in a sup without a number is not a note marker`() {
        val page = page("<p>x<sup>th</sup> y<a href=\"https://e.com\">link</a></p>")
        assertEquals(emptyList(), page.skip)
    }

    @Test
    fun `images become data URIs and their alt text is not in the text`() {
        val page =
            page(
                "<p>Hi</p><img src=\"images/a.png\" alt=\"ALT\"/>" +
                    "<img src=\"https://remote.example/y.png\" alt=\"R\"/><img src=\"missing.png\"/>",
                resources = mapOf("images/a.png" to TestEpub.PNG),
            )
        val images = Jsoup.parse(page.html).select("img")
        assertEquals(1, images.size)
        assertTrue(images.single().attr("src").startsWith("data:image/png;base64,"))
        assertEquals("Hi", page.text)
        assertFalse("remote.example" in page.html)
    }

    @Test
    fun `svg cover images are inlined and other svg content is dropped`() {
        val page =
            page(
                "<svg viewBox=\"0 0 1 1\"><text>SVGTEXT</text>" +
                    "<image xlink:href=\"images/a.png\" width=\"1\" height=\"1\"/></svg><p>x</p>",
                resources = mapOf("images/a.png" to TestEpub.PNG),
            )
        assertTrue("<image href=\"data:image/png;base64," in page.html)
        assertEquals("x", page.text)
    }

    @Test
    fun `links are rewritten to the page, external ones kept, the rest dropped with their text kept`() {
        val page =
            page(
                "<p><a href=\"c1.xhtml#x\">cross</a> <a href=\"c1.xhtml\">chap</a> <a href=\"#loc\">local</a> " +
                    "<a href=\"https://e.com/a\">web</a> <a href=\"mailto:a@b.c\">mail</a> " +
                    "<a href=\"javascript:alert(1)\">js</a> <a href=\"java&#10;script:alert(1)\">js2</a> " +
                    "<a href=\"nofile.xhtml\">gone</a> <a href=\"images/a.png\">img</a>" +
                    "<a id=\"loc\"/></p>",
                "<p id=\"x\">Target</p>",
            )
        val links = Jsoup.parse(page.html).select("a[href]")
        val hrefs = links.map { it.attr("href") }
        val expected = listOf("#ch1-x", "#ch1", "#ch0-loc", "https://e.com/a", "mailto:a@b.c")
        assertEquals(expected, hrefs)
        assertTrue("js js2 gone img" in page.text)
        assertEquals(page.text.indexOf("Target"), page.anchors["ch1-x"])
        assertEquals(page.text.indexOf("Target"), page.anchors["ch1"])
        assertTrue(Jsoup.parse(page.html).getElementById("ch0-loc") != null)
        assertOffsetContract(page)
    }

    @Test
    fun `ids are made unique with the chapter prefix and a repeated one is dropped`() {
        val page = page("<p id=\"a\">one</p><p id=\"a\">two</p>", "<p id=\"a\">three</p>")
        val ids = Jsoup.parse(page.html).select("[id]").map { it.id() }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.containsAll(listOf("ch0", "ch1", "ch0-a", "ch1-a")))
        assertEquals(page.text.indexOf("one"), page.anchors["ch0-a"])
        assertEquals(page.text.indexOf("three"), page.anchors["ch1-a"])
    }

    @Test
    fun `a chapter range includes only those chapters and starts its offsets at 0`() {
        val page = page("<p>zero</p>", "<p>one</p>", "<p>two</p>", range = 1..2)
        assertEquals("one\n\ntwo", page.text)
        assertEquals(listOf(1, 2), page.chapters.map { it.index })
        assertEquals(0, page.chapters.first().start)
        assertEquals(0, page.anchors["ch1"])
        assertFalse("ch0" in page.anchors)
        assertFalse("zero" in page.html)
        assertOffsetContract(page)
    }

    @Test
    fun `the table of contents is resolved to offsets, entries outside the range are dropped, their children kept`() {
        val toc =
            listOf(
                TestToc("Part", "c0.xhtml", listOf(TestToc("Section", "c1.xhtml#s"))),
                TestToc("Gone", "missing.xhtml"),
            )
        val bodies = arrayOf("<p>zero</p>", "<p>one</p><h2 id=\"s\">Sec</h2><p>tail</p>")
        val full = page(*bodies, toc = toc)
        assertEquals(
            listOf(TocItem("Part", 0, listOf(TocItem("Section", full.text.indexOf("Sec"), emptyList())))),
            full.toc,
        )
        val partial = page(*bodies, toc = toc, range = 1..1)
        assertEquals(listOf(TocItem("Section", partial.text.indexOf("Sec"), emptyList())), partial.toc)
    }

    @Test
    fun `the page is a document with the dark class and the script in the head`() {
        val dark = page("<p>x</p>", dark = true, script = "window.thoreau = 1;")
        assertTrue(dark.html.startsWith("<!DOCTYPE html>"))
        assertTrue("<meta charset=\"utf-8\">" in dark.html)
        assertEquals("dark", Jsoup.parse(dark.html).body().className())
        val doc = Jsoup.parse(dark.html)
        assertEquals(listOf("window.thoreau = 1;"), doc.head().select("script").map { it.data() })
        assertTrue(doc.body().select("script").isEmpty())
        assertEquals("", Jsoup.parse(page("<p>x</p>").html).body().className())
    }

    @Test
    fun `scripts, event handlers, frames, forms and objects are removed`() {
        val page =
            page(
                "<p onclick=\"evil()\" style=\"color:red\" class=\"c\">safe<script>alert(1)</script></p>" +
                    "<iframe src=\"https://e.com\"></iframe><form action=\"x\"><input value=\"in\"/></form>" +
                    "<object data=\"x\">obj</object>" +
                    "<embed src=\"x\"/><p>end</p>",
            )
        val doc = Jsoup.parse(page.html)
        assertEquals(listOf("/*js*/"), doc.select("script").map { it.data() })
        assertTrue(doc.select("iframe, form, input, object, embed").isEmpty())
        assertTrue(doc.select("[onclick], [style], [class=c]").isEmpty())
        assertEquals("safe\n\nend", page.text)
    }

    @Test
    fun `without the book style its CSS and classes are gone and the reading style is there`() {
        val css = ".big{color:red}"
        val page =
            page(
                "<p class=\"big\" style=\"color:blue\">x</p>",
                head = "<link rel=\"stylesheet\" href=\"style.css\"/><style>.inline{color:green}</style>",
                resources = mapOf("style.css" to css.toByteArray()),
            )
        for (gone in listOf(".big", ".inline", "color:blue", "class=\"big\"")) assertFalse(gone in page.html, gone)
        assertTrue("max-width: 40em" in page.html)
        assertMarkCss(page)
    }

    @Test
    fun `with the book style its CSS is kept and made safe`() {
        val css =
            "@import url(https://evil.example/a.css);\n@font-face{font-family:F;src:url(fonts/f.woff)}\n" +
                ".big{color:red;background:url(https://evil.example/b.png)}\n" +
                ".esc{background:\\75 rl(https://evil.example/c.png)}"
        val page =
            page(
                "<p class=\"big\" style=\"background:url(https://evil.example/d.png);color:blue\">x</p>",
                ownStyle = true,
                head = "<link rel=\"stylesheet\" href=\"style.css\"/><style>.inline{color:green}</style>",
                resources = mapOf("style.css" to css.toByteArray(), "fonts/f.woff" to byteArrayOf(1, 2, 3)),
            )
        assertTrue(".big{color:red;background:none}" in page.html)
        assertTrue(".inline{color:green}" in page.html)
        assertTrue("src:url(\"data:font/woff;base64,AQID\")" in page.html)
        assertTrue("class=\"big\"" in page.html && "color:blue" in page.html)
        for (remote in listOf("a.css", "b.png", "d.png")) assertFalse("evil.example/$remote" in page.html, remote)
        // The escaped url( is neutralised: the escape is gone, what is left is not the url function.
        assertFalse("\\75" in page.html || Regex("(?i)url\\([^)]*evil").containsMatchIn(page.html))
        assertFalse("@import" in page.html)
        assertFalse("max-width: 40em" in page.html)
        assertMarkCss(page)
    }

    private fun assertMarkCss(page: BookPage) {
        for (name in listOf("sentence", "word", "start", "search")) {
            for (rule in listOf("::highlight(mark-$name)", "body.dark ::highlight(mark-$name)")) {
                assertTrue(rule in page.html, rule)
            }
        }
        // A book's own class="mark-word" must not paint: only the highlights, which no markup can create, style marks.
        assertFalse(Regex("\\.mark-").containsMatchIn(ReadingCss.marks))
        assertFalse(Regex("\\.mark-").containsMatchIn(page.html.substringAfter("<style>").substringBefore("</style>")))
    }

    @Test
    fun `nothing but anchors references a remote URL`() {
        val page =
            page(
                "<p><a href=\"https://e.com\">l</a><img src=\"https://r.example/i.png\"/></p>",
                ownStyle = true,
                head = "<style>@import 'https://r.example/a.css'; p{background:url('https://r.example/b.png')}</style>",
            )
        val html = page.html.replace(Regex("<a href=\"https://e.com\">"), "")
        assertFalse("http" in html.substringAfter("<body"), "no remote reference in the body")
        assertFalse(Regex("(?i)(src=|url\\(|@import)[^;>]*http").containsMatchIn(html))
    }

    @Test
    fun `a build without inlining has the same text, skips, chapters, toc and anchors and embeds nothing`() {
        val bodies =
            arrayOf(
                "<h1 id=\"top\">One</h1><p>Hi <a href=\"#top\">x</a></p><img src=\"images/a.png\" alt=\"A\"/>" +
                    "<svg viewBox=\"0 0 1 1\"><image xlink:href=\"images/a.png\"/></svg><p class=\"big\">end</p>",
                "<table><tr><td>cell</td></tr></table><p>two</p>",
            )
        val css = "@font-face{src:url(fonts/f.woff)}\n.big{background:url(images/a.png)}"
        val resources =
            mapOf(
                "style.css" to css.toByteArray(),
                "fonts/f.woff" to ByteArray(300),
                "images/a.png" to TestEpub.PNG,
            )
        val chapters =
            bodies.mapIndexed { i, body ->
                TestChapter("c$i.xhtml", body, "<link rel=\"stylesheet\" href=\"style.css\"/>", title = "T$i")
            }
        val loaded = book(TestEpub(chapters, resources = resources))
        val full = BookPageBuilder.build(loaded, true, false, "/*js*/")
        val light = BookPageBuilder.build(loaded, true, false, "/*js*/", inline = false)
        assertEquals(full.text, light.text)
        assertEquals(full.skip, light.skip)
        assertEquals(full.chapters, light.chapters)
        assertEquals(full.toc, light.toc)
        assertEquals(full.anchors, light.anchors)
        assertFalse("base64" in light.html)
        assertTrue("base64" in full.html)
        val img = Jsoup.parse(light.html).selectFirst("img")
        assertTrue(img != null && !img.hasAttr("src") && img.attr("alt") == "A")
        assertTrue(Jsoup.parse(light.html).selectFirst("image")?.hasAttr("href") == false)
        // The font once, the png in the img, the svg image and the css background: each reference counts.
        assertEquals(4, light.resourceRefs)
        // The estimate is never below the inline page and above it by less than the 8 characters a reference may add.
        val gap = light.estimatedInlineLength() - full.html.length
        assertTrue(gap in 0..8 * 4, "gap $gap")
    }

    @Test
    fun `text directly in a table part keeps its place when an HTML parser foster-parents it`() {
        val tables =
            listOf(
                "<table>stray<tr><td>a</td></tr>more</table>",
                "<table><tr>stray<td>a</td>more<td>b</td></tr></table>",
                "<table><tbody>x<tr>y<td>a</td></tr>z</tbody><tfoot>f</tfoot><thead>h</thead></table>",
                "<table><b>bold <i>it</i></b><tr><td>a</td></tr><p>para</p></table>",
                "<table><tr><td>a</td></tr><tr><td><table>inner<tr>deep<td>d</td></tr></table></td></tr></table>",
                // A no-break space is not whitespace to the parser; in a pre every character is text.
                "<table>&#160;<tr>&#160;<td>a</td></tr></table>",
                "<pre><table>  <tr> <td>a</td> </tr> </table></pre>",
            )
        for (table in tables) {
            val page = page("<p>before</p>$table<p>tail</p>")
            // assertOffsetContract parses with jsoup's HTML parser, which moves stray table content like a browser.
            assertOffsetContract(page)
            val words = Regex("[a-z]+").findAll(table.replace(Regex("<[^>]*>"), " ")).map { it.value }.toList()
            val inHtml = Jsoup.parse(page.html).select("table span[data-o]").map { it.wholeText().trim() }
            assertEquals(words, inHtml.filter { it.isNotEmpty() }, table)
        }
    }
}
