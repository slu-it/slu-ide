package dev.sluide.previews.yaml

import dev.sluide.previews.preview.Formatted
import org.jsoup.Jsoup
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.events.AliasEvent
import org.yaml.snakeyaml.events.CollectionStartEvent
import org.yaml.snakeyaml.events.DocumentStartEvent
import org.yaml.snakeyaml.events.Event
import org.yaml.snakeyaml.events.NodeEvent
import org.yaml.snakeyaml.events.ScalarEvent
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class YamlTextTest {
    private fun format(text: String): String =
        when (val formatted = YamlText.format(text)) {
            is Formatted.Text -> formatted.text
            is Formatted.Failed -> fail("${formatted.reason} for:\n$text")
        }

    private fun failure(text: String): String = assertIs<Formatted.Failed>(YamlText.format(text)).reason

    @Test
    fun `block style with a 2-space indent and comments dropped`() {
        assertEquals(
            "apiVersion: v1\nkind: Pod\nmetadata:\n  name: web\n  labels:\n    app: x\nspec:\n  containers:\n" +
                "    - name: web\n      image: \"nginx:1\"\n      ports:\n        - 80\n        - 443\n" +
                "    - image: x\n      env: []\n      args:\n        - a\n---\nkind: Service\n",
            format(YamlFixtures.kubernetes),
        )
    }

    @Test
    fun `block scalars are indented anew with their chomping`() {
        assertEquals(
            "lit: |\n  line1\n    deeper\n\n  line3\nstrip: |-\n  x\nkeep: |+\n  y\n\n\n" +
                "fold: >\n  a\n  b\n\n  c\nind: |2\n    lead\n  x\nnext: 1\n",
            format(YamlFixtures.blocks),
        )
        assertEquals("a:\n  - |2-\n     sp\n    t\n", format("a:\n- |1-\n  sp\n t\n"))
    }

    @Test
    fun `multi-line plain and quoted scalars`() {
        assertEquals("plain: a\n  b\n\n  c\nq: \"x\n  y \\\n  z\"\ns: 'p\n  q'\n", format(YamlFixtures.multiline))
    }

    @Test
    fun `explicit entries and alias keys`() {
        assertEquals(
            "?\n  - a\n  - b\n: 1\n? x: 1\n: 2\n*a : 3\nm:\n  ? \"multi\n    key\"\n  : 4\n  ? plain\n    key\n" +
                "  :\n    - x\n? |\n  blk\n: 6\n? a: 1\n  b: 2\n: c: 3\n  d: 4\n?\n  - x\n:\n  - y\n? &k !t\n: &v\n",
            format(YamlFixtures.complexKeys),
        )
    }

    @Test
    fun `documents directives and properties`() {
        assertEquals(
            "%YAML 1.1\n%TAG !e! tag:e.com,2000:\n--- !e!foo\na: !e!bar 1\n...\n%YAML 1.1\n---\n- x\n",
            format(YamlFixtures.directives),
        )
        assertEquals("---\n---\na: 1\n---\n", format(YamlFixtures.emptyDocuments))
        assertEquals("--- &r\na: 1\n", format(YamlFixtures.rootAnchor))
        assertEquals("top\n  more\n", format(YamlFixtures.rootScalar))
        assertEquals(
            "k: !!null\nj: &a\nl: !t &b x\nm:\n  - |2-\n     sp\n    t\nn: !<tag:x.com,2000:y> z\n",
            format(YamlFixtures.properties),
        )
    }

    @Test
    fun `flow collections are expanded`() {
        assertEquals(
            "- a\n-\n  - b\n  - c\n- d: e\n-\n  f:\n    - g\n- x:\n",
            format(YamlFixtures.flow),
        )
    }

    @Test
    fun `byte order mark and line breaks`() {
        assertEquals("a:\n  b: 1\n", format("\uFEFFa:\r\n  b: 1\r\n"))
    }

    @Test
    fun `an error after a byte order mark has the same place on the page and in the copy`() {
        val text = "\uFEFFa: b: c"
        val page = Jsoup.parse(YamlRenderer.render(text, false)).selectFirst("p.fold-error")?.text()

        assertEquals(page, failure(text))
        assertTrue(failure(text).startsWith("Line 1, column 5: "), failure(text))
    }

    @Test
    fun `a block scalar that ends the file keeps its value without a final line break`() {
        assertEquals("k: |-\n  a\n", format("k: |\n  a"))
        assertEquals("k: |-\n  a\n", format("k: |+\n  a"))
        assertEquals("k: >-\n  a\n  b\n", format("k: >\n  a\n  b"))
        // An empty one keeps its chomping; one ending in a line separator keeps it with an empty line after it.
        assertEquals("k: |\nj: |+\nl: 1\n", format("k: |\nj: |+\nl: 1\n"))
        assertEquals("k: |\n  a\u2028\n", format("k: |\n  a\u2028"))
        assertEquals("k: |+\n  a\u2029", format("k: |+\n  a\u2029"))
    }

    @Test
    fun `line and paragraph separators stay as written and NEL becomes a line feed`() {
        assertEquals("k: |\n  a\u2028  b\n  c\n", format("k: |\n    a\u2028    b\n    c\n"))
        assertEquals("k: |\n  a\n  b\n", format("k: |\n    a\u0085    b\n"))
        assertEquals("- a\u2028  b\n", format("[a\u2028b]\n"))
    }

    @Test
    fun `nothing to copy from an empty or invalid file`() {
        assertEquals("The file is empty", failure(""))
        assertEquals("The file is empty", failure("# comment only\n"))
        assertEquals(
            "Line 3, column 2: expected <block end>, but found '<block mapping start>'; " +
                "the block mapping started on line 1",
            failure("a:\n  b: 1\n c: 2\n"),
        )
    }

    @Test
    fun `no node cap and no code point limit`() {
        val many = (1..(YamlRenderer.MAX_NODES + 10)).joinToString("") { "- $it\n" }
        assertEquals(many, format(many))
        // Beyond SnakeYAML's default limit of 3 MB.
        val long = "a: " + "x".repeat(4 * 1024 * 1024) + "\n"
        assertEquals(long, format(long))
    }

    @Test
    fun `formatted text reads back as the original`() {
        val extra =
            listOf(
                "a:\n- |1-\n  sp\n t\n",
                "x: \"a\\tb\\u00e9\"\ny: 'it''s'\nz: \"multi\\\n   line\"\n",
                "- !!binary |\n  R0lG\n- ? complex\n  : value\n",
                "key with spaces: value with:colon\nurl: http://x/y#z\n",
                "a: [1, {b: [2, {c: d}]}, [], {}]\n",
                "--- !!set\n? a\n? b\n",
                "k: | # comment\n  x\na:\n  b: |+\n    x\n\n  c: !t &a 1\n",
                "k: |\r\n  a\r\n  b\r\nl: >-\r\n  c\r\n\r\n  d\r\n",
                "{a: [b\n  c, *x], \"q\\\"k\": 1}\n",
                "?\n: v\n? !!null\n: w\n",
                "k: |\n  a",
                "k: |+\n  a",
                "k: >\n  a\n  b",
                "- |2\n    x\n  y",
                "a:\n  k: |-\n    z",
                "k: |\n    a\u0085    b\n",
                "k: |\n    a\u2028      b\n    c\n",
                "- >\n  a\u2029  b\n",
                "[a\u2028b, \"c\u0085 d\"]\n",
                "k: plain\u2028  more\n",
                "k: |\n  a\u2028\nj: 1\n",
                "k: |\n  a\u2028",
                "k: |+\n  a\u2029",
                "k: |+\n  a\u2028\nj: 1\n",
                "- |+\n  a\u2029\n  \u2029- x\n",
                "k: |\nj: |+\nl: 1\n",
                "- &a\n  k: v\n- !t\n  - x\n- &b []\n",
            )
        for (text in YamlFixtures.all + extra) {
            val formatted = format(text)
            assertEquals(events(text), events(formatted), "for:\n$text\nformatted:\n$formatted")
            assertEquals(formatted, format(formatted), "formatting is stable for:\n$text")
        }
    }

    /**
     * The events of [text] without marks and the styles that formatting changes (flow collections, `---` and `...`):
     * type, anchor, tag, implicit flags, and for scalars value and style.
     */
    private fun events(text: String): List<String> =
        Yaml(LoaderOptions()).parse(StringReader(text.removePrefix("\uFEFF"))).map { event -> describe(event) }

    private fun describe(event: Event): String {
        val parts = mutableListOf<Any?>(event.eventId)
        if (event is NodeEvent) parts += event.anchor
        when (event) {
            is ScalarEvent -> {
                parts.addAll(listOf(event.tag, event.implicit.toString(), event.value, event.scalarStyle))
            }

            is CollectionStartEvent -> {
                parts.addAll(listOf(event.tag, event.implicit))
            }

            is DocumentStartEvent -> {
                // An explicit document start gives an empty map where an implicit one gives none.
                parts.addAll(listOf(event.version, event.tags.orEmpty()))
            }

            is AliasEvent -> {}

            else -> {}
        }
        return parts.joinToString(" | ")
    }

    @Test
    fun `events compare what formatting keeps`() {
        assertTrue(events("a: 1") != events("a: '1'"))
        assertEquals(events("{a: [1]}"), events("a:\n  - 1\n"))
    }
}
