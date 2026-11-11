package dev.sluide.ebooks

import org.jsoup.Jsoup
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What may never reach the page, and the structure cases that came with it (ADR 0165). */
class PageSecurityTest {
    @TempDir
    lateinit var dir: Path

    private fun page(
        vararg bodies: String,
        ownStyle: Boolean = false,
        head: String = "",
        resources: Map<String, ByteArray> = emptyMap(),
        script: String = "/*js*/",
    ): BookPage {
        val chapters = bodies.mapIndexed { i, body -> TestChapter("c$i.xhtml", body, head, title = "T$i") }
        val epub = TestEpub(chapters, resources = resources)
        val book = assertIs<EpubResult.Book>(EpubReader.read(epub.write(dir))).book
        return BookPageBuilder.build(book, ownStyle, false, script)
    }

    private fun builtBy(
        resource: Resource,
        body: String,
        ownStyle: Boolean = false,
    ): BookPage {
        val xhtml = "<html><head><link rel=\"stylesheet\" href=\"s.css\"/></head><body>$body</body></html>"
        val book =
            EpubBook(
                "t",
                null,
                false,
                listOf(Chapter("c0.xhtml", null, xhtml)),
                emptyList(),
                mapOf(
                    "x" to resource,
                    "s.css" to Resource("text/css", "p{background:url(x)}".toByteArray()),
                ),
            )
        return BookPageBuilder.build(book, ownStyle, false, "")
    }

    @Test
    fun `a media type that is not plain never reaches an attribute or a url`() {
        val evil = Resource("image/png\" onerror=\"alert(1)", TestEpub.PNG)
        val image = builtBy(evil, "<img src=\"x\"/><svg><image href=\"x\"/></svg>")
        assertTrue(Jsoup.parse(image.html).select("img, image").isEmpty())
        assertFalse("onerror" in image.html)
        val css = builtBy(evil, "<p>t</p>", ownStyle = true)
        assertFalse("onerror" in css.html)
        assertTrue("p{background:none}" in css.html)
    }

    @Test
    fun `attribute values are escaped`() {
        val page =
            page(
                "<p lang=\"en&quot; onclick=&quot;x()\" dir=\"rtl\">" +
                    "<img src=\"images/a.png\" alt=\"x&quot; onerror=&quot;y()\"/>" +
                    "<a href=\"https://e.com/&quot; onmouseover=&quot;z()\">t</a></p>",
                resources = mapOf("images/a.png" to TestEpub.PNG),
            )
        val attributes = Jsoup.parse(page.html).getAllElements().flatMap { it.attributes().map { a -> a.key } }
        assertTrue(attributes.none { it.startsWith("on") }, attributes.toString())
        assertEquals("x\" onerror=\"y()", Jsoup.parse(page.html).selectFirst("img")?.attr("alt"))
    }

    @Test
    fun `the policy allows only the page's own script by its hash`() {
        val script = "window.thoreau = {};\r\nwindow.x = 1;"
        val page = page("<p>x</p>", script = script)
        val meta = Jsoup.parse(page.html).selectFirst("meta[http-equiv=Content-Security-Policy]")
        val policy =
            meta
                ?.attr("content")
                .orEmpty()
                .split(";")
                .map { it.trim() }
        val directives = policy.associate { it.substringBefore(' ') to it.substringAfter(' ', "") }
        val seen = script.replace("\r\n", "\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(seen.toByteArray())
        val hash = Base64.getEncoder().encodeToString(digest)
        assertEquals("'sha256-$hash'", directives["script-src"])
        assertEquals("'none'", directives["default-src"])
        assertTrue("<script>$seen</script>" in page.html)
        // Live links must find the handler before a large body has been parsed.
        assertEquals(listOf(seen), Jsoup.parse(page.html).head().select("script").map { it.data() })
        assertTrue(page.html.indexOf("<script>") < page.html.indexOf("<body"))
    }

    @Test
    fun `things that load or navigate are gone`() {
        val page =
            page(
                "<p background=\"https://evil.example/bg\">kept</p>" +
                    "<img srcset=\"https://evil.example/s 1x\" src=\"https://evil.example/i\"/>" +
                    "<img src=\"//evil.example/i\"/>" +
                    "<video poster=\"https://evil.example/p\"><source src=\"https://evil.example/v\"/></video>" +
                    "<base href=\"https://evil.example/\"/>" +
                    "<meta http-equiv=\"refresh\" content=\"0;url=https://evil.example/\"/>" +
                    "<link rel=\"stylesheet\" href=\"https://evil.example/a.css\"/>" +
                    "<svg><use xlink:href=\"https://evil.example/s.svg#a\"/>" +
                    "<image xlink:href=\"https://evil.example/i\"/></svg>" +
                    "<object data=\"https://evil.example/o\">o</object><embed src=\"https://evil.example/e\"/>" +
                    "<a href=\"//evil.example/x\">proto</a><a href=\" JaVaScRiPt:alert(1)\">js</a>" +
                    "<a href=\"\tVBScript:x\">vb</a><a href=\"data:text/html;base64,AAAA\">data</a>" +
                    "<a href=\"file:///etc/passwd\">file</a>",
            )
        assertFalse("evil.example" in page.html)
        for (gone in listOf("srcset", "background=", "<base", "refresh")) assertFalse(gone in page.html, gone)
        assertTrue(Jsoup.parse(page.html).select("a[href]").isEmpty())
        assertTrue("protojsvbdatafile" in page.text)
    }

    @Test
    fun `book CSS cannot close the style element and start a script`() {
        val page =
            page(
                "<p>x</p>",
                ownStyle = true,
                head = "<link rel=\"stylesheet\" href=\"s.css\"/>",
                resources = mapOf("s.css" to "p{color:red}\n</style><script>alert(1)</script>".toByteArray()),
            )
        val doc = Jsoup.parse(page.html)
        assertEquals(listOf("/*js*/"), doc.select("script").map { it.data() })
        assertTrue("p{color:red}" in page.html)
    }

    @Test
    fun `text after an unclosed br or hr is not lost`() {
        val page = page("<p>line<br>after</p><hr>tail")
        assertEquals("line\nafter\n\ntail", page.text)
    }

    @Test
    fun `a newline right after pre is dropped like a browser does`() {
        assertEquals("abc\n\nnext", page("<pre>\nabc</pre><p>next</p>").text)
        assertEquals("abc", page("<pre>\r\nabc</pre>").text)
        assertEquals("\na", page("<pre>\n\na</pre>").text, "only one newline is dropped")
    }

    @Test
    fun `direction is kept for right-to-left books and nothing else of dir`() {
        val page = page("<p dir=\"RTL\">a</p><bdo dir=\"rtl\">b</bdo><p dir=\"x&quot;y\">c</p>")
        val doc = Jsoup.parse(page.html)
        assertEquals(listOf("rtl", "rtl"), doc.select("[dir]").map { it.attr("dir") })
    }

    @Test
    fun `data URIs the book wrote itself are kept for images only`() {
        val png = Base64.getEncoder().encodeToString(TestEpub.PNG)
        val page = page("<img src=\"data:image/png;base64,$png\"/><img src=\"data:text/html;base64,AAAA\"/>")
        val sources = Jsoup.parse(page.html).select("img").map { it.attr("src") }
        assertEquals(listOf("data:image/png;base64,$png"), sources)
    }

    @Test
    fun `deep content keeps its line breaks and block separators`() {
        val depth = ChapterWalker.MAX_DEPTH + 50
        val body = "<div>".repeat(depth) + "x<br/>y<p>z</p>w" + "</div>".repeat(depth)
        assertEquals("x\ny\n\nz\n\nw", page(body).text)
    }

    @Test
    fun `very deep nesting is flattened to its text`() {
        val depth = 10_000
        val body = "<div>".repeat(depth) + "deep <script>no()</script>text" + "</div>".repeat(depth) + "<p>after</p>"
        val page = page("<p>before</p>$body")
        // jsoup itself caps the depth (512) and makes the rest siblings, so the words may be in separate blocks.
        assertEquals(listOf("before", "deep", "text", "after"), page.text.split(Regex("\\s+")))
        val divs = Regex("<div[ >]").findAll(page.html).count()
        assertTrue(divs <= ChapterWalker.MAX_DEPTH + 1, "$divs divs")
        assertFalse("no()" in page.html)
    }
}
