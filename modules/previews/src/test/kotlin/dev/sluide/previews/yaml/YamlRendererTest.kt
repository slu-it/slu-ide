package dev.sluide.previews.yaml

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlRendererTest {
    private fun page(
        text: String,
        collapsed: Set<String> = emptySet(),
    ): Document = Jsoup.parse(YamlRenderer.render(text, false, collapsed))

    /** The text of a line as shown when its block is open: a summary without its tail; spaces kept. */
    private fun lineText(element: Element): String {
        // An empty line holds a <br>.
        val copy = element.clone().also { it.select(".tail, br").remove() }
        return copy.wholeText()
    }

    /** All lines of [doc] in order, as shown with everything open. */
    private fun lines(doc: Document): List<String> =
        doc.select(".fold-tree summary, .fold-tree div:not(.children)").map { lineText(it) }

    private fun error(doc: Document): String? = doc.selectFirst("p.fold-error")?.text()

    @Test
    fun `nested structure with paths and source lines`() {
        val doc = page(YamlFixtures.kubernetes)

        assertEquals(
            listOf(
                "/0/metadata",
                "/0/metadata/labels",
                "/0/spec",
                "/0/spec/containers",
                "/0/spec/containers/0",
                "/0/spec/containers/0/ports",
                "/0/spec/containers/1",
                "/0/spec/containers/1/args",
            ),
            doc.select("details[data-path]").map { it.attr("data-path") },
        )
        assertEquals(
            listOf("2", "2", "3", "4", "5", "7", "8", "10"),
            doc.select("details[data-path]").map { it.attr("data-line") },
        )
        assertTrue(doc.select("details").all { it.hasAttr("open") })
        assertNull(error(doc))
        assertNull(doc.getElementById("fold-state"))
    }

    @Test
    fun `block style lines with compact sequence items and comments dropped`() {
        val doc = page(YamlFixtures.kubernetes)

        assertEquals(
            listOf(
                "apiVersion: v1",
                "kind: Pod",
                "metadata:",
                "name: web",
                "labels:",
                "app: x",
                "spec:",
                "containers:",
                "- name: web",
                "image: \"nginx:1\"",
                "ports:",
                "- 80",
                "- 443",
                "- image: x",
                "env: []",
                "args:",
                "- a",
                "---",
                "kind: Service",
            ),
            lines(doc),
        )
        assertFalse(doc.text().contains("#"))
        // The second document's entries do not fold: documents have no fold of their own.
        assertTrue(doc.select("details[data-path^=/1]").isEmpty())
    }

    @Test
    fun `a closed fold shows its size`() {
        val doc = page(YamlFixtures.kubernetes, collapsed = setOf("/0/spec/containers/0", "/0/metadata"))

        val item = assertNotNull(doc.selectFirst("details[data-path=/0/spec/containers/0]"))
        assertFalse(item.hasAttr("open"))
        assertEquals("- name: web … 3 keys", item.selectFirst("summary")?.text())
        assertEquals(
            "metadata: … 2 keys",
            doc.selectFirst("details[data-path=/0/metadata] > summary")?.text(),
        )
        assertEquals(" … 2 items", doc.selectFirst("details[data-path=/0/spec/containers] .tail")?.wholeText())
    }

    @Test
    fun `colours as in the source highlighting`() {
        val doc = page(YamlFixtures.anchors)

        assertEquals(listOf("base", "a", "derived", "<<", "b", "c", "d", "e", "f", "g", "h"), texts(doc, "property"))
        assertEquals(listOf("&base", "*base", "!!str", "!Ref"), texts(doc, "annotation"))
        assertEquals(listOf("~", "TRUE"), texts(doc, "keyword"))
        assertEquals(listOf("1", "0x1F", ".inf"), texts(doc, "number"))
        // A tag gives the 2 its type: no number colour.
        assertTrue(doc.select(".string").isEmpty())
        assertEquals("h: plain text", lines(doc).last())
        // Other plain scalars are not coloured.
        assertEquals(listOf("h"), doc.select("div[data-line=10] span").map { it.text() })
    }

    private fun texts(
        doc: Document,
        style: String,
    ) = doc.select(".fold-tree .$style").map { it.text() }

    @Test
    fun `quoted and block scalars are strings`() {
        val doc = page("a: \"x\"\nb: 'y'\nc: |\n  z\n")

        assertEquals(listOf("\"x\"", "'y'", "|", "z"), doc.select(".fold-tree .string").map { it.text() })
    }

    @Test
    fun `multi-line scalars are indented under their key with their source lines`() {
        val doc = page(YamlFixtures.blocks)

        assertEquals(
            listOf(
                "lit: |",
                "  line1",
                "    deeper",
                "",
                "  line3",
                "strip: |-",
                "  x",
                "keep: |+",
                "  y",
                "",
                "",
                "fold: >",
                "  a",
                "  b",
                "",
                "  c",
                "ind: |2",
                "    lead",
                "  x",
                "next: 1",
            ),
            lines(doc),
        )
        assertEquals(
            listOf("1", "2", "3", "4"),
            doc
                .select(".fold-tree div[data-line]")
                .drop(1)
                .take(4)
                .map { it.attr("data-line") },
        )
    }

    @Test
    fun `plain and quoted scalars over several lines lose their indentation`() {
        val doc = page("- k: a\n      b\n\n      c\n  q: \"x\n         y\"\n")

        assertEquals(listOf("- k: a", "  b", "", "  c", "q: \"x", "  y\""), lines(doc))
    }

    @Test
    fun `a block scalar that ends the file without a line break is stripped`() {
        assertEquals(listOf("k: |-", "  a"), lines(page("k: |\n  a")))
        assertEquals(listOf("k: |-", "  a"), lines(page("k: |+\n  a")))
        assertEquals(listOf("k: >2-", "  a", "  b"), lines(page("k: >1\n a\n b")))
        assertEquals(listOf("k: |", "  a"), lines(page("k: |\n  a\n")))
    }

    @Test
    fun `line and paragraph separators and NEL end the lines of a scalar`() {
        val doc = page("k: |\n    a\u2028    b\u0085    c\n    d\n")

        assertEquals(listOf("k: |", "  a", "  b", "  c", "  d"), lines(doc))
        // The editor counts none of them as a line break.
        assertEquals(
            listOf("0", "1", "1", "1", "2"),
            doc.select(".fold-tree div[data-line]").map { it.attr("data-line") },
        )
    }

    @Test
    fun `an explicit indentation indicator becomes the new step`() {
        val doc = page("m:\n      - |4-\n            lead\n          x\n")

        assertEquals(listOf("m:", "- |2-", "    lead", "  x"), lines(doc))
    }

    @Test
    fun `flow collections are expanded and empty ones kept`() {
        val doc = page(YamlFixtures.flow)

        assertEquals(
            listOf("- a", "-", "- b", "- c", "- d: e", "-", "f:", "- g", "- x:"),
            lines(doc).map { it.trim() },
        )
        assertEquals(
            listOf("/0/1", "/0/2", "/0/3", "/0/3/f", "/0/4"),
            doc.select("details").map { it.attr("data-path") },
        )
        assertEquals(listOf("a: {}", "b: []"), lines(page("a: {}\nb: [ ]\n")))
    }

    @Test
    fun `anchors aliases merge keys and tags are shown as written and never expanded`() {
        val doc = page(YamlFixtures.anchors)

        assertEquals(
            listOf("base: &base", "a: 1", "derived:", "<<: *base", "b: !!str 2", "c: !Ref Foo"),
            lines(doc).take(6),
        )
        assertEquals(listOf("/0/base", "/0/derived"), doc.select("details").map { it.attr("data-path") })
    }

    @Test
    fun `explicit entries for collection and multi-line keys`() {
        val doc = page(YamlFixtures.complexKeys)

        assertEquals(
            listOf("?", "- a", "- b", ": 1", "? x: 1", ": 2", "*a : 3", "m:", "? \"multi", "  key\"", ": 4"),
            lines(doc).take(11),
        )
        // Keys do not fold; the value of a collection key folds under the entry's index.
        assertEquals(
            listOf("/0/m", "/0/m/plain key", "/0/?5", "/0/?6"),
            doc.select("details").map { it.attr("data-path") },
        )
    }

    @Test
    fun `paths escape keys like JSON Pointers and alias keys stay as written`() {
        val doc = page("\"a/b~c\":\n  x: 1\n*al :\n  y: 2\n")

        assertEquals(listOf("/0/a~1b~0c", "/0/*al"), doc.select("details").map { it.attr("data-path") })
    }

    @Test
    fun `duplicate keys are kept`() {
        assertEquals(listOf("a: 1", "a: 2"), lines(page(YamlFixtures.duplicateKeys)))
    }

    @Test
    fun `documents are separated by marker lines with their directives`() {
        assertEquals(
            listOf(
                "%YAML 1.1",
                "%TAG !e! tag:e.com,2000:",
                "--- !e!foo",
                "a: !e!bar 1",
                "...",
                "%YAML 1.1",
                "---",
                "- x",
            ),
            lines(page(YamlFixtures.directives)),
        )
        assertEquals(listOf("---", "---", "a: 1", "---"), lines(page(YamlFixtures.emptyDocuments)))
        assertEquals(listOf("--- &r", "a: 1"), lines(page(YamlFixtures.rootAnchor)))
        assertEquals(listOf("|", "  root block"), lines(page(YamlFixtures.rootBlock)))
        assertEquals(listOf("a: 1"), lines(page("--- # first\na: 1\n...\n")))
    }

    @Test
    fun `characters beyond the BMP before a scalar do not shift it`() {
        val doc = page(YamlFixtures.sequences)

        assertEquals(
            listOf("- \uD83D\uDE00 x: \"\uD83D\uDE00\"", "y: |", "  \uD83D\uDE00 z", "-", "- a", "- b"),
            lines(doc).take(6),
        )
        assertEquals(listOf("- []", "- {}", "- &x", "k: v", "- !!map", "k: v", "- k: v"), lines(doc).drop(6))
    }

    @Test
    fun `empty files and files with only comments`() {
        assertEquals("Empty file", page("").selectFirst("p.fold-note")?.text())
        assertEquals("Empty file", page(" \n").selectFirst("p.fold-note")?.text())
        assertEquals("Empty file", page("# only a comment\n").selectFirst("p.fold-note")?.text())
        assertTrue(page("").select("details").isEmpty())
    }

    @Test
    fun `byte order mark is ignored`() {
        val doc = page("\uFEFFa:\n  b: 1\n")

        assertNull(error(doc))
        assertEquals(listOf("a:", "b: 1"), lines(doc))
    }

    @Test
    fun `bad indentation shows the error above the part that parsed`() {
        val doc = page("a:\n  b: 1\n c: 2\n")

        assertEquals(
            "Line 3, column 2: expected <block end>, but found '<block mapping start>'; " +
                "the block mapping started on line 1",
            error(doc),
        )
        assertEquals(listOf("a:", "b: 1"), lines(doc))
    }

    @Test
    fun `an unclosed flow sequence is reported`() {
        val doc = page("a: [1, 2\nb: 3\n")

        val message = assertNotNull(error(doc))
        assertTrue(message.startsWith("Line 2, column 2: "), message)
        assertTrue(message.endsWith("; the flow sequence started on line 1"), message)
        assertEquals(listOf("a:", "- 1"), lines(doc).take(2))
    }

    @Test
    fun `tab indentation is reported`() {
        val message = assertNotNull(error(page("a:\n\tb: 1\n")))

        assertTrue(message.startsWith("Line 2, column 1: found character '\\t(TAB)'"), message)
    }

    @Test
    fun `an unclosed quote names the line it started on`() {
        assertEquals(
            "Line 3, column 1: found unexpected end of stream; the quoted scalar started on line 1",
            error(page("a: \"unclosed\nb: 1\n")),
        )
    }

    @Test
    fun `a control character is reported with its place`() {
        assertEquals("Line 2, column 4: the character U+0001 is not allowed in YAML", error(page("a: 1\nb: \u0001\n")))
    }

    @Test
    fun `folds of an invalid page are kept for the next render`() {
        val collapsed = setOf("/0/a", "/0/z")
        val doc = page("a:\n  b: 1\nz: [\n", collapsed)

        assertNotNull(error(doc))
        val state = assertNotNull(doc.getElementById("fold-state"))
        assertEquals("[\"/0/a\",\"/0/z\"]", state.attr("data-collapsed"))
        assertFalse(assertNotNull(doc.selectFirst("details[data-path=/0/a]")).hasAttr("open"))
        assertNotNull(page("", collapsed).getElementById("fold-state"))
        assertNull(page("a:\n  b: 1\n", collapsed).getElementById("fold-state"))
    }

    @Test
    fun `large files are cut off with a note`() {
        val text = (1..50).joinToString("") { "k$it: [a, b]\n" }
        val doc = Jsoup.parse(YamlRenderer.render(text, false, setOf("/0/k1"), 10))

        assertEquals("Showing the first 10 nodes.", doc.selectFirst("p.fold-note")?.text())
        assertTrue(lines(doc).size < 10)
        assertNotNull(doc.getElementById("fold-state"))
    }

    @Test
    fun `nesting deeper than the cap is an error`() {
        val depth = YamlParser.MAX_DEPTH + 1
        val doc = page("[".repeat(depth) + "]".repeat(depth))

        assertEquals(
            "Line 1, column ${YamlParser.MAX_DEPTH + 1}: nested deeper than ${YamlParser.MAX_DEPTH} levels",
            error(doc),
        )
        assertNull(error(page("[".repeat(YamlParser.MAX_DEPTH) + "]".repeat(YamlParser.MAX_DEPTH))))
    }

    @Test
    fun `dark pages use the dark theme`() {
        assertTrue(Jsoup.parse(YamlRenderer.render("a: 1", true)).body().hasClass("dark"))
        assertTrue(page("a: 1").body().hasClass("fold-page"))
    }
}
