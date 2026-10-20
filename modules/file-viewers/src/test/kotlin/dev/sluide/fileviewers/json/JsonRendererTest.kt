package dev.sluide.fileviewers.json

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonRendererTest {
    private fun page(
        text: String,
        collapsed: Set<String> = emptySet(),
    ): Document = Jsoup.parse(JsonRenderer.render(text, false, collapsed))

    @Test
    fun `nested structure with paths and source lines`() {
        val doc = page("{\n  \"a\": [1, {\"b\": null}],\n  \"c\": true\n}")

        assertEquals(listOf("", "/a", "/a/1"), doc.select("details[data-path]").map { it.attr("data-path") })
        assertEquals(listOf("0", "1", "1"), doc.select("details[data-path]").map { it.attr("data-line") })
        assertEquals("2", doc.select("div[data-line]").last()?.attr("data-line"))
        assertTrue(doc.select("details").all { it.hasAttr("open") })
        assertEquals(listOf("1,", "\"b\": null", "\"c\": true"), doc.select("div[data-line]").map { it.text() })
    }

    @Test
    fun `pointers escape tilde and slash`() {
        val doc = page("{\"a/b\": {\"c~d\": [0]}}")

        assertEquals(listOf("", "/a~1b", "/a~1b/c~0d"), doc.select("details[data-path]").map { it.attr("data-path") })
    }

    @Test
    fun `token classes`() {
        val doc = page("{\"k\": \"v\", \"n\": 1, \"t\": false}")

        assertEquals(listOf("\"k\"", "\"n\"", "\"t\""), doc.select("span.property").map { it.text() })
        assertEquals(listOf("\"v\""), doc.select("span.string").map { it.text() })
        assertEquals(listOf("1"), doc.select("span.number").map { it.text() })
        assertEquals(listOf("false"), doc.select("span.keyword").map { it.text() })
    }

    @Test
    fun `html characters and escapes are kept as JSON text`() {
        val doc = page("{\"<k&>\": \"a<b>&\\\"\\u00e9\\n\"}")

        assertEquals("\"<k&>\"", doc.selectFirst("span.property")?.text())
        assertEquals("\"a<b>&\\\"é\\n\"", doc.selectFirst("span.string")?.text())
    }

    @Test
    fun `numbers keep their spelling`() {
        val doc = page("[2e3, 1.50, 12345678901234567890123, -0, 1E+2]")

        val shown = doc.select("div[data-line]").map { it.text() }
        assertEquals(listOf("2e3,", "1.50,", "12345678901234567890123,", "-0,", "1E+2"), shown)
    }

    @Test
    fun `comments and trailing commas are accepted`() {
        val doc = page("// head\n{\n  /* c */ \"a\": [1, 2,], // x\n  \"b\": 3,\n}")

        assertNull(doc.selectFirst(".json-error"))
        assertEquals(listOf("", "/a"), doc.select("details").map { it.attr("data-path") })
        assertFalse(doc.body().text().contains("head"))
        assertFalse(doc.body().text().contains("c */"))
        assertEquals(listOf("1,", "2", "\"b\": 3"), doc.select("div[data-line]").map { it.text() })
    }

    @Test
    fun `empty containers are not foldable`() {
        val doc = page("{\"a\": {}, \"b\": []}")

        assertEquals(listOf(""), doc.select("details").map { it.attr("data-path") })
        assertEquals(listOf("\"a\": {},", "\"b\": []"), doc.select("div[data-line]").map { it.text() })
    }

    @Test
    fun `collapsed paths start closed`() {
        val doc = page("{\"a\": [1], \"b\": [2]}", setOf("/a"))

        assertFalse(assertNotNull(doc.selectFirst("details[data-path=/a]")).hasAttr("open"))
        assertTrue(assertNotNull(doc.selectFirst("details[data-path=/b]")).hasAttr("open"))
        assertTrue(assertNotNull(doc.selectFirst("details[data-path='']")).hasAttr("open"))
    }

    @Test
    fun `tail counts keys and items`() {
        val doc = page("{\"o\": {\"x\": 1}, \"p\": {\"x\": 1, \"y\": 2}, \"a\": [1], \"b\": [1, 2]}")

        val tails = doc.select("details[data-path] > summary .tail").map { it.text() }
        assertEquals(
            listOf("… } 4 keys", "… } 1 key,", "… } 2 keys,", "… ] 1 item,", "… ] 2 items"),
            tails,
        )
    }

    @Test
    fun `commas between siblings and none after the last`() {
        val doc = page("{\"a\": [1, 2], \"b\": {\"c\": 3}}")

        assertEquals(
            listOf("{", "\"a\": [", "1,", "2", "],", "\"b\": {", "\"c\": 3", "}", "}"),
            doc.select(".json-tree summary, .json-tree div[data-line], .json-tree .close").map { lineText(it) },
        )
    }

    /** The text of a line as shown when its container is open: a summary without its tail. */
    private fun lineText(element: Element): String {
        if (element.tagName() != "summary") return element.text()
        return element
            .clone()
            .also { it.select(".tail").remove() }
            .text()
            .trim()
    }

    @Test
    fun `byte order mark is ignored`() {
        val doc = page("\uFEFF{\"a\": 1}")

        assertNull(doc.selectFirst(".json-error"))
        assertEquals(listOf("\"a\": 1"), doc.select("div[data-line]").map { it.text() })
    }

    @Test
    fun `no text nodes between blocks`() {
        val doc = page("{\"a\": [1, 2], \"b\": 3}")

        assertEquals(
            "",
            doc
                .select(".json-tree details, .json-tree .children")
                .map { it.ownText() }
                .joinToString("")
                .trim(),
        )
        assertFalse(JsonRenderer.render("[1, 2]", false).contains("</div>\n<div"))
    }

    @Test
    fun `hostile keys neither break out of attributes nor inject elements`() {
        val key = "\"><script>x</script>"
        val html = JsonRenderer.render("{\"" + key.replace("\"", "\\\"") + "\": {\"b\": 1}}", false)
        val doc = Jsoup.parse(html)
        val plain = Jsoup.parse(JsonRenderer.render("[]", false)).select("script").size

        assertEquals(
            listOf("", "/" + key.replace("/", "~1")),
            doc.select("details[data-path]").map { it.attr("data-path") },
        )
        assertEquals(plain, doc.select("script").size)
    }

    @Test
    fun `invalid input does not inject elements`() {
        val doc = page("{\"a\": <script>}")
        val plain = Jsoup.parse(JsonRenderer.render("[]", false)).select("script").size

        assertNotNull(doc.selectFirst("p.json-error"))
        assertEquals(plain, doc.select("script").size)
    }

    @Test
    fun `members start at their key line`() {
        val doc = page("{\n  \"a\":\n    {\"b\": 1},\n  \"c\":\n    2\n}")

        assertEquals("1", doc.selectFirst("details[data-path=/a]")?.attr("data-line"))
        assertEquals(listOf("2", "3"), doc.select("div[data-line]").map { it.attr("data-line") })
    }

    @Test
    fun `unclosed container names where it was opened`() {
        val doc = page("{\n  \"a\": [1,\n  2")

        val error = assertNotNull(doc.selectFirst("p.json-error")).text()
        assertTrue(error.contains("the array opened on line 2 is not closed"), error)
        assertFalse(error.contains("Source") || error.contains("REDACTED"), error)
        assertEquals(listOf("", "/a"), doc.select("details").map { it.attr("data-path") })
    }

    @Test
    fun `mismatched close marker does not leak the source excerpt`() {
        val error = assertNotNull(page("{\"a\": 1]").selectFirst("p.json-error")).text()

        assertTrue(error.startsWith("Line 1, column "), error)
        assertFalse(error.contains("Source") || error.contains("REDACTED"), error)
    }

    @Test
    fun `an array cut off after a comma is an unclosed array`() {
        val error = assertNotNull(page("{\n  \"a\": [1, 2,\n").selectFirst("p.json-error")).text()

        assertTrue(error.contains("the array opened on line 2 is not closed"), error)
        assertFalse(error.contains("within/between"), error)
    }

    @Test
    fun `an object cut off after a comma is an unclosed object`() {
        val error = assertNotNull(page("{\n  \"a\": 1,\n").selectFirst("p.json-error")).text()

        assertTrue(error.contains("the object opened on line 1 is not closed"), error)
        assertFalse(error.contains("within/between"), error)
    }

    @Test
    fun `an incomplete page keeps the folds given, escaped`() {
        val folds = setOf("/a", "/\"gone\"")
        val cases =
            mapOf(
                "error" to JsonRenderer.render("{\"a\": {\"b\": 1}, ", false, folds),
                "empty" to JsonRenderer.render("", false, folds),
                "truncated" to JsonRenderer.render("{\"a\": {\"b\": 1}}", false, folds, 2),
            )
        for ((name, html) in cases) {
            val state = assertNotNull(Jsoup.parse(html).selectFirst("#json-state"), name)
            assertEquals("[\"/\\\"gone\\\"\",\"/a\"]", state.attr("data-collapsed"), name)
        }
    }

    @Test
    fun `a complete page keeps no folds besides its closed containers`() {
        val doc = Jsoup.parse(JsonRenderer.render("{\"a\": {\"b\": 1}}", false, setOf("/a", "/gone")))

        assertNull(doc.selectFirst("#json-state"))
        assertNull(doc.selectFirst("details[data-path=\"/a\"][open]"))
    }

    @Test
    fun `other end of input errors keep the parser message`() {
        val error = assertNotNull(page("{\"a\": [\"abc").selectFirst("p.json-error")).text()

        assertFalse(error.contains("is not closed"), error)
        assertFalse(error.contains("Source") || error.contains("REDACTED"), error)
        assertTrue(error.contains("end-of-input"), error)
    }

    @Test
    fun `only comments is an empty file`() {
        val doc = page("// nothing\n/* here */")

        assertEquals("Empty file", doc.selectFirst("p.json-note")?.text())
        assertNull(doc.selectFirst(".json-error"))
    }

    @Test
    fun `error notice with the tree that parsed before it`() {
        val doc = page("{\n  \"a\": [1, 2,\n  \"b\": }")

        val error = assertNotNull(doc.selectFirst("p.json-error")).text()
        assertTrue(error.startsWith("Line 3, column "), error)
        assertFalse(error.contains("Source"), error)
        assertEquals(listOf("", "/a"), doc.select("details").map { it.attr("data-path") })
        assertEquals(listOf("1,", "2,"), doc.select("div[data-line]").map { it.text() }.take(2))
    }

    @Test
    fun `content after the value is an error`() {
        val doc = page("{} 1")

        assertTrue(assertNotNull(doc.selectFirst("p.json-error")).text().contains("after the end"))
    }

    @Test
    fun `empty text`() {
        val doc = page("  \n")

        assertEquals("Empty file", doc.selectFirst("p.json-note")?.text())
        assertNull(doc.selectFirst(".json-tree"))
    }

    @Test
    fun `stops after the value limit and closes the containers`() {
        val html = JsonRenderer.render("[1, 2, 3, [4, 5], 6]", false, emptySet(), 6)
        val doc = Jsoup.parse(html)

        assertEquals(listOf("1,", "2,", "3,", "4"), doc.select("div[data-line]").map { it.text() })
        assertEquals(2, doc.select("details").size)
        assertEquals("Showing the first 6 values.", doc.selectFirst("p.json-note")?.text())
        assertNull(doc.selectFirst(".json-error"))
    }

    @Test
    fun `dark class on the body`() {
        assertTrue(Jsoup.parse(JsonRenderer.render("[]", true)).body().hasClass("dark"))
        assertFalse(Jsoup.parse(JsonRenderer.render("[]", false)).body().hasClass("dark"))
    }
}
