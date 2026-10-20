package dev.sluide.fileviewers.xml

import dev.sluide.fileviewers.preview.Formatted
import org.jsoup.Jsoup
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XmlTextTest {
    private fun format(text: String): String = assertIs<Formatted.Text>(XmlText.format(text)).text

    private fun failure(text: String): String = assertIs<Formatted.Failed>(XmlText.format(text)).reason

    @Test
    fun `indents elements and keeps leaves and empty elements on one line`() {
        assertEquals(
            "<project>\n  <name>demo</name>\n  <deps>\n    <dep scope=\"test\">\n      <a>b</a>\n    </dep>\n" +
                "    <empty/>\n  </deps>\n</project>\n",
            format(
                "<?xml version=\"1.0\"?>\n<project><name>demo</name><deps><dep scope=\"test\"><a>b</a></dep>" +
                    "<empty></empty></deps></project>",
            ),
        )
    }

    @Test
    fun `comments instructions CDATA and DOCTYPE are kept and the declaration dropped`() {
        assertEquals(
            "<!DOCTYPE a SYSTEM \"a.dtd\">\n<!-- top -->\n<a>\n  <?pi data?>\n  <![CDATA[x<y]]>\n  text\n" +
                "  <b><![CDATA[1 & 2]]></b>\n</a>\n",
            format(
                "<?xml version=\"1.0\"?><!DOCTYPE a SYSTEM \"a.dtd\"><!-- top --><a><?pi data?><![CDATA[x<y]]>" +
                    "\n  text\n  <b><![CDATA[1 & 2]]></b></a>",
            ),
        )
    }

    @Test
    fun `namespace prefixes and declarations are written as in the source`() {
        assertEquals(
            "<x:a xmlns:x=\"urn:x\">\n  <x:b x:k=\"v\"/>\n</x:a>\n",
            format("<x:a xmlns:x=\"urn:x\"><x:b x:k=\"v\"/></x:a>"),
        )
    }

    @Test
    fun `text and attribute values are escaped`() {
        val out = format("<a k=\"&lt;b&gt; &amp; &quot;c&quot; &amp;lt;\">&lt;/x&gt; &amp; \"c\" &amp;amp;</a>")

        assertEquals("<a k=\"&lt;b> &amp; &quot;c&quot; &amp;lt;\">&lt;/x> &amp; \"c\" &amp;amp;</a>\n", out)
    }

    /** The nodes of [nodes] as lines (kind, name, attributes, text; children indented), to compare documents. */
    private fun outline(
        nodes: List<XmlNode>,
        depth: Int = 0,
    ): List<String> =
        nodes.flatMap { node ->
            val pad = "  ".repeat(depth)
            when (node) {
                is XmlElement -> {
                    listOf(pad + "<" + node.name + node.attributes.joinToString("") { " ${it.first}=[${it.second}]" }) +
                        outline(node.children, depth + 1)
                }

                is XmlTextNode -> {
                    listOf(pad + "text[${node.text}]")
                }

                is XmlCdata -> {
                    listOf(pad + "cdata[${node.text}]")
                }

                is XmlComment -> {
                    listOf(pad + "comment[${node.text}]")
                }

                is XmlInstruction -> {
                    listOf(pad + "pi[${node.text}]")
                }

                is XmlDoctype -> {
                    listOf(pad + "doctype[${node.text}]")
                }
            }
        }

    private fun parse(xml: String): ParsedXml = XmlParser.parse(xml, Int.MAX_VALUE).also { assertNull(it.error, xml) }

    @Test
    fun `the output parses to the same elements attributes and text`() {
        val samples =
            listOf(
                "<a x=\"1\" y=\"&amp;&quot;\"><b>t &amp; u</b><c/><d><e k=\"v\">1 &lt; 2</e></d></a>",
                "<r><!-- c --><i>one</i><i>two</i><n:j xmlns:n=\"urn:n\" n:k=\"w\">z</n:j></r>",
                "<a>x ]]&gt; y<b>]]&gt;</b>]]&gt;</a>",
                "<a k=\"1&#10;2&#9;3&#13;4\" l=\"&#13;&#10;\">x</a>",
                "<a k='say \"hi\"' l='it&apos;s'>1</a>",
                "<a>one&#13;two<b>x&#13;y</b></a>",
                "<a>mixed <b>bold</b> text <![CDATA[c]]><?pi d?></a>",
            )
        for (sample in samples) {
            val out = format(sample)

            assertEquals(outline(parse(sample).nodes), outline(parse(out).nodes), "$sample -> $out")
        }
    }

    @Test
    fun `a closing CDATA marker in text is escaped`() {
        assertEquals("<a>x ]]&gt; y</a>\n", format("<a>x ]]&gt; y</a>"))
    }

    @Test
    fun `tabs and line breaks in attribute values and a carriage return in text are written as references`() {
        assertEquals(
            "<a k=\"1&#10;2&#9;3&#13;4\">x&#13;y</a>\n",
            format("<a k=\"1&#10;2&#9;3&#13;4\">x&#13;y</a>"),
        )
    }

    @Test
    fun `formatting twice gives the same text`() {
        val once = format("<a><b k=\"&amp;\">x &lt; y</b><!--c--><d/></a>")

        assertEquals(once, format(once))
    }

    @Test
    fun `an error fails with the message of the view`() {
        val text = "<a>\n  <b>1</b>\n  <c>&nope;</c>\n</a>"
        val reason = failure(text)

        assertTrue(reason.startsWith("Line 3, column "), reason)
        val notice = Jsoup.parse(XmlRenderer.render(text, false)).selectFirst("p.fold-error")
        assertEquals(notice?.text(), reason)
    }

    @Test
    fun `an unclosed element fails like the view`() {
        assertTrue(failure("<a>\n<b>").contains("the element <b> opened on line 2 is not closed"))
    }

    @Test
    fun `empty text fails`() {
        assertEquals("The file is empty", failure(""))
        assertEquals("The file is empty", failure(" \n "))
    }

    @Test
    fun `an external entity is never read`() {
        val secret = Files.createTempFile("xxe", ".txt")
        try {
            Files.writeString(secret, "TOP-SECRET-MARKER")
            val xml = "<!DOCTYPE a [<!ENTITY x SYSTEM \"${secret.toUri()}\">]>\n<a>&x;</a>"
            val result = XmlText.format(xml)

            assertIs<Formatted.Failed>(result)
            assertFalse(result.reason.contains("TOP-SECRET-MARKER"))
            val parameter = "<!DOCTYPE a [<!ENTITY % p SYSTEM \"${secret.toUri()}\"> %p;]>\n<a/>"
            val other = XmlText.format(parameter)
            val text = (other as? Formatted.Text)?.text ?: (other as Formatted.Failed).reason
            assertFalse(text.contains("TOP-SECRET-MARKER"))
        } finally {
            Files.deleteIfExists(secret)
        }
    }

    @Test
    fun `more nodes than the page shows are formatted in full`() {
        val count = XmlRenderer.MAX_NODES + 5
        val lines = format((1..count).joinToString("", "<a>", "</a>") { "<i>$it</i>" }).lines()

        assertEquals(count + 3, lines.size)
        assertEquals("  <i>$count</i>", lines[count])
    }
}
