package dev.sluide.fileviewers.xml

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmlRendererTest {
    private fun page(
        text: String,
        collapsed: Set<String> = emptySet(),
    ): Document = Jsoup.parse(XmlRenderer.render(text, false, collapsed))

    private val pom =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
          <name>demo</name>
          <dependencies>
            <dependency><artifactId>a</artifactId></dependency>
            <dependency scope="test">
              <artifactId>b</artifactId>
            </dependency>
          </dependencies>
        </project>
        """.trimIndent()

    /** The text of a line as shown when its element is open: a summary without its tail. */
    private fun lineText(element: Element): String {
        if (element.tagName() != "summary") return element.text()
        return element
            .clone()
            .also { it.select(".tail").remove() }
            .text()
            .trim()
    }

    @Test
    fun `nested structure with paths and source lines`() {
        val doc = page(pom)

        assertEquals(
            listOf(
                "/project[1]",
                "/project[1]/dependencies[1]",
                "/project[1]/dependencies[1]/dependency[1]",
                "/project[1]/dependencies[1]/dependency[2]",
            ),
            doc.select("details[data-path]").map { it.attr("data-path") },
        )
        assertEquals(listOf("1", "3", "4", "5"), doc.select("details[data-path]").map { it.attr("data-line") })
        assertTrue(doc.select("details").all { it.hasAttr("open") })
        assertEquals(
            listOf(
                "<project>",
                "<name>demo</name>",
                "<dependencies>",
                "<dependency>",
            ),
            doc.select(".fold-tree summary, .fold-tree div[data-line]").take(4).map { lineText(it) },
        )
        assertNull(doc.selectFirst("p.fold-error"))
        assertFalse(doc.text().contains("<?xml"))
    }

    @Test
    fun `lines in document order with closing tags`() {
        val doc = page("<a><b x=\"1\"/><c>t</c><d><e/></d></a>")

        assertEquals(
            listOf("<a>", "<b x=\"1\"/>", "<c>t</c>", "<d>", "<e/>", "</d>", "</a>"),
            doc.select(".fold-tree summary, .fold-tree div[data-line], .fold-tree .close").map { lineText(it) },
        )
    }

    @Test
    fun `path index counts same-name siblings only`() {
        val doc = page("<r><x><y/><y>t</y><z><k/></z></x><w><k/></w><x><k/></x></r>")

        assertEquals(
            listOf("/r[1]", "/r[1]/x[1]", "/r[1]/x[1]/z[1]", "/r[1]/w[1]", "/r[1]/x[2]"),
            doc.select("details").map { it.attr("data-path") },
        )
    }

    @Test
    fun `attributes and namespace prefixes are shown as written`() {
        val doc = page("<a:r xmlns:a=\"urn:x\" a:k='v \"q\"'><a:c/></a:r>")

        assertEquals(listOf("xmlns:a", "a:k"), doc.select("span.attribute").map { it.text() })
        assertEquals(listOf("\"urn:x\"", "\"v &quot;q&quot;\""), doc.select("span.string").map { it.text() })
        assertEquals("/a:r[1]", doc.selectFirst("details")?.attr("data-path"))
        assertTrue(doc.text().contains("<a:c/>"))
    }

    @Test
    fun `token classes`() {
        val doc = page("<!DOCTYPE a><a k=\"v\"><!-- c --><?pi data?><![CDATA[x<y]]>text</a>")

        assertEquals(listOf("k"), doc.select("span.attribute").map { it.text() })
        assertEquals(listOf("\"v\"", "<![CDATA[x<y]]>"), doc.select("span.string").map { it.text() })
        assertEquals(listOf("<!-- c -->"), doc.select("span.comment").map { it.text() })
        assertEquals(listOf("<!DOCTYPE a>", "<?pi data?>"), doc.select("span.annotation").map { it.text() })
        assertTrue(doc.select("span.tag").isNotEmpty())
    }

    @Test
    fun `text is trimmed, whitespace-only text dropped, mixed content on own lines`() {
        val doc = page("<a>\n  <b>  padded text  </b>\n  lead <i>x</i> tail\n</a>")

        assertEquals("<b>padded text</b>", doc.selectFirst("div[data-line]")?.text())
        assertEquals(listOf("lead", "tail"), doc.select(".children > div:not([data-line])").map { it.text() })
        assertEquals(listOf("… </a> 4 children"), doc.select("summary .tail").map { it.text() })
    }

    @Test
    fun `empty and text-only elements are single lines`() {
        val doc = page("<a><b/><c></c><d>1.0</d></a>")

        assertEquals(1, doc.select("details").size)
        assertEquals(listOf("<b/>", "<c/>", "<d>1.0</d>"), doc.select("div[data-line]").map { it.text() })
    }

    @Test
    fun `comments processing instructions CDATA and DOCTYPE are kept, the declaration dropped`() {
        val doc =
            page(
                "<?xml version=\"1.0\"?>\n<!DOCTYPE a SYSTEM \"a.dtd\">\n<!-- top -->\n" +
                    "<a><?target some data?><!--in--><![CDATA[<raw & text>]]></a>",
            )
        val text = doc.select(".fold-tree").text()

        assertTrue(text.contains("<!DOCTYPE a SYSTEM \"a.dtd\">"), text)
        assertTrue(text.contains("<!-- top -->"), text)
        assertTrue(text.contains("<?target some data?>"), text)
        assertTrue(text.contains("<!--in-->"), text)
        assertTrue(text.contains("<![CDATA[<raw & text>]]>"), text)
        assertFalse(text.contains("<?xml"), text)
        assertEquals("3 children", doc.selectFirst("details > summary .tail")?.text()?.substringAfterLast("> "))
    }

    @Test
    fun `text is escaped as XML and shows entity references as written`() {
        val doc = page("<a q=\"1 &lt; 2 &amp; 3\">x &lt; y &amp; z &#65;</a>")

        assertEquals("<a q=\"1 &lt; 2 &amp; 3\">x &lt; y &amp; z A</a>", doc.selectFirst("div[data-line]")?.text())
    }

    @Test
    fun `hostile values inject no elements`() {
        val plain = Jsoup.parse(XmlRenderer.render("<a/>", false)).select("script").size
        val doc =
            page(
                "<a><b k=\"&quot;&gt;&lt;script&gt;x&lt;/script&gt;\">" +
                    "&lt;script&gt;y&lt;/script&gt;</b><!--<script>z</script>--></a>",
            )

        assertEquals(plain, doc.select("script").size)
        assertTrue(doc.text().contains("&lt;script>x&lt;/script>"), doc.text())
        assertTrue(doc.text().contains("&lt;script>y&lt;/script>"), doc.text())
        assertEquals(1, doc.select("[data-path]").size)
    }

    @Test
    fun `hostile element names and invalid input inject no elements`() {
        val plain = Jsoup.parse(XmlRenderer.render("<a/>", false)).select("script").size
        val doc = page("<a><script>x</script><b\"><script></a>")

        assertEquals(plain, doc.select("script").size)
        assertNotNull(doc.selectFirst("p.fold-error"))
    }

    @Test
    fun `an external entity is never read`() {
        val secret = Files.createTempFile("xxe", ".txt")
        try {
            Files.writeString(secret, "TOP-SECRET-MARKER")
            val xml =
                "<?xml version=\"1.0\"?>\n<!DOCTYPE a [<!ENTITY x SYSTEM \"${secret.toUri()}\">]>\n<a>&x;</a>"
            val html = XmlRenderer.render(xml, false)

            assertFalse(html.contains("TOP-SECRET-MARKER"))
            val doc = Jsoup.parse(html)
            assertTrue(assertNotNull(doc.selectFirst("p.fold-error")).text().contains("x"))
            assertTrue(doc.select(".fold-tree").text().contains("<!DOCTYPE a ["))
        } finally {
            Files.deleteIfExists(secret)
        }
    }

    @Test
    fun `an external parameter entity is never fetched`() {
        val secret = Files.createTempFile("xxe", ".txt")
        try {
            Files.writeString(secret, "TOP-SECRET-MARKER")
            val xml = "<!DOCTYPE a [<!ENTITY % p SYSTEM \"${secret.toUri()}\"> %p;]>\n<a/>"

            assertFalse(XmlRenderer.render(xml, false).contains("TOP-SECRET-MARKER"))
        } finally {
            Files.deleteIfExists(secret)
        }
    }

    @Test
    fun `a billion laughs DOCTYPE does not expand`() {
        val xml =
            "<!DOCTYPE lolz [<!ENTITY lol \"lol\">" +
                "<!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">" +
                "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">]>\n<lolz>&lol3;</lolz>"
        val html = XmlRenderer.render(xml, false)

        assertTrue(html.length < 50_000, "page of ${html.length} chars")
        assertFalse(html.contains("lollollol"))
        assertNotNull(Jsoup.parse(html).selectFirst("p.fold-error"))
    }

    @Test
    fun `an undeclared entity is an error with the tree so far`() {
        val doc = page("<a>\n  <b>1</b>\n  <c>&nope;</c>\n</a>")

        val error = assertNotNull(doc.selectFirst("p.fold-error")).text()
        assertTrue(error.startsWith("Line 3, column "), error)
        assertTrue(error.contains("nope"), error)
        assertFalse(error.contains("ParseError"), error)
        assertFalse(error.contains("Message:"), error)
        // The element the error is in is still open: its start tag only, not an empty element.
        assertEquals(listOf("<b>1</b>", "<c>"), doc.select("div[data-line]").map { it.text() })
        assertEquals(1, doc.select("details").size)
    }

    @Test
    fun `an unclosed element is reported and the page closed`() {
        val doc = page("<a>\n  <b>\n    <c/>\n")

        val error = assertNotNull(doc.selectFirst("p.fold-error")).text()
        assertTrue(error.startsWith("Line "), error)
        assertTrue(error.contains("the element <b> opened on line 2 is not closed"), error)
        assertEquals(listOf("/a[1]", "/a[1]/b[1]"), doc.select("details").map { it.attr("data-path") })
    }

    @Test
    fun `text before an error is kept and open containers have no end tag`() {
        val doc = page("<a><b>\n<c/>hello &bogus;")

        assertTrue(doc.select(".fold-tree").text().contains("hello"), doc.text())
        assertTrue(doc.select(".close").isEmpty())
        assertEquals(listOf("… 1 child", "… 2 children"), doc.select("summary .tail").map { it.text() })
        assertEquals(
            listOf("<a>", "<b>"),
            doc.select("summary").map {
                it
                    .clone()
                    .also { c ->
                        c.select(".tail").remove()
                    }.text()
                    .trim()
            },
        )
    }

    @Test
    fun `the end of the file inside markup is not an unclosed element`() {
        val inMarkup =
            "the file ends inside markup (a tag, comment, CDATA section, " +
                "processing instruction or reference)"
        for (xml in listOf("<a>\n<!-- x", "<a>\n<b x=\"1", "<a>\n<![CDATA[x", "<a>\n<?pi x", "<a>\nx &am")) {
            val error = assertNotNull(page(xml).selectFirst("p.fold-error"), xml).text()

            assertTrue(
                error.contains(
                    "the file ends inside markup (a tag, comment, CDATA section, processing instruction or reference)",
                ),
                "$xml: $error",
            )
        }
        val withEntity = assertNotNull(page("<a>x &amp; y").selectFirst("p.fold-error")).text()
        assertTrue(withEntity.contains("the element <a> opened on line 1 is not closed"), withEntity)
        val plain = assertNotNull(page("<a>\n  text").selectFirst("p.fold-error")).text()
        assertTrue(plain.contains("the element <a> opened on line 1 is not closed"), plain)
    }

    @Test
    fun `an element starts on the line its start tag starts on`() {
        val doc = page("<a>\n<b\n  x=\"1\"\r\n  y=\"2\">t</b>\r<c\n/></a>")

        assertEquals(listOf("1", "4"), doc.select("div[data-line]").map { it.attr("data-line") })
        assertEquals("0", doc.selectFirst("details")?.attr("data-line"))
    }

    @Test
    fun `an unclosed element reports the line it starts on`() {
        val error = assertNotNull(page("<a\n  k=\"v\">\n").selectFirst("p.fold-error")).text()

        assertTrue(error.contains("the element <a> opened on line 1 is not closed"), error)
    }

    @Test
    fun `many built-in entity references are no error`() {
        val doc = page("<a>" + "&amp;".repeat(100_001) + "</a>")

        assertNull(doc.selectFirst("p.fold-error"))
        assertEquals(1, doc.select("div[data-line]").size)
    }

    @Test
    fun `a control character in a DOCTYPE is an error, not a crash`() {
        val doc = page("<!DOCTYPE p [\u0001]><p/>")

        assertNotNull(doc.selectFirst("p.fold-error"))
    }

    @Test
    fun `a DOCTYPE is taken from its start, not from text in its internal subset`() {
        val doc = page("<!-- <!DOCTYPE no> -->\n<!DOCTYPE a [<!ENTITY x \"<!DOCTYPE y\">]>\n<a/>")

        assertEquals(
            listOf("<!DOCTYPE a [<!ENTITY x \"<!DOCTYPE y\">]>"),
            doc.select("span.annotation").map { it.text() },
        )
    }

    @Test
    fun `a mismatched end tag keeps the parser message`() {
        val doc = page("<a>\n<b>\n</a>")

        val error = assertNotNull(doc.selectFirst("p.fold-error")).text()
        assertTrue(error.startsWith("Line 3, column "), error)
        assertTrue(error.contains("</b>"), error)
    }

    @Test
    fun `content after the root is an error`() {
        val doc = page("<a/><b/>")

        assertNotNull(doc.selectFirst("p.fold-error"))
        assertEquals(listOf("<a/>"), doc.select("div[data-line]").map { it.text() })
    }

    @Test
    fun `byte order mark and an encoding declaration are ignored`() {
        val doc = page("\uFEFF<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>é</a>")

        assertNull(doc.selectFirst("p.fold-error"))
        assertEquals("<a>é</a>", doc.selectFirst("div[data-line]")?.text())
    }

    @Test
    fun `empty and blank text give a note`() {
        for (text in listOf("", "  \n\t", "\uFEFF")) {
            val doc = page(text)

            assertEquals("Empty file", doc.selectFirst("p.fold-note")?.text(), text)
            assertNull(doc.selectFirst("p.fold-error"), text)
            assertNull(doc.selectFirst(".fold-tree"), text)
        }
    }

    @Test
    fun `nodes beyond the limit are left out with a note`() {
        val doc = Jsoup.parse(XmlRenderer.render("<a><b/><c/><d/><e/></a>", false, emptySet(), 3))

        assertEquals("Showing the first 3 nodes.", doc.selectFirst("p.fold-note")?.text())
        assertNull(doc.selectFirst("p.fold-error"))
        assertEquals(listOf("<b/>", "<c/>"), doc.select("div[data-line]").map { it.text() })
    }

    @Test
    fun `very deep nesting is an error, not a crash`() {
        val xml = "<a>".repeat(3000) + "</a>".repeat(3000)

        val doc = page(xml)

        assertNotNull(doc.selectFirst("p.fold-error"))
        assertTrue(doc.select("details").size in 100..1100)
    }

    @Test
    fun `collapsed paths start closed`() {
        val doc = page(pom, setOf("/project[1]/dependencies[1]", "/project[1]/gone[1]"))

        assertNull(doc.selectFirst("details[data-path=\"/project[1]/dependencies[1]\"][open]"))
        assertNotNull(doc.selectFirst("details[data-path=\"/project[1]\"][open]"))
        assertNotNull(doc.selectFirst("details[data-path=\"/project[1]/dependencies[1]/dependency[2]\"][open]"))
    }

    @Test
    fun `an incomplete page keeps the folds given, escaped`() {
        val folds = setOf("/a[1]", "/\"gone\"[1]")
        val cases =
            mapOf(
                "error" to XmlRenderer.render("<a><b/>", false, folds),
                "empty" to XmlRenderer.render("", false, folds),
                "truncated" to XmlRenderer.render("<a><b><c/></b></a>", false, folds, 2),
            )
        for ((name, html) in cases) {
            val state = assertNotNull(Jsoup.parse(html).selectFirst("#fold-state"), name)
            assertEquals("[\"/\\\"gone\\\"[1]\",\"/a[1]\"]", state.attr("data-collapsed"), name)
        }
    }

    @Test
    fun `a complete page keeps no folds besides its closed elements`() {
        val doc = Jsoup.parse(XmlRenderer.render("<a><b><c/></b></a>", false, setOf("/a[1]/b[1]", "/gone[1]")))

        assertNull(doc.selectFirst("#fold-state"))
        assertNull(doc.selectFirst("details[data-path=\"/a[1]/b[1]\"][open]"))
    }

    @Test
    fun `tails say child or children`() {
        val doc = page("<a><b><c/></b><d><e/><f/></d></a>")

        assertEquals(
            listOf("… </a> 2 children", "… </b> 1 child", "… </d> 2 children"),
            doc.select("details > summary .tail").map { it.text() },
        )
    }

    @Test
    fun `dark theme sets the body class`() {
        val doc = Jsoup.parse(XmlRenderer.render("<a/>", true))

        assertTrue(doc.body().hasClass("dark"))
        assertTrue(doc.body().hasClass("fold-page"))
    }
}
