package dev.sluide.previews.json

import dev.sluide.previews.preview.Formatted
import org.jsoup.Jsoup
import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JsonTextTest {
    private fun format(text: String): String = assertIs<Formatted.Text>(JsonText.format(text)).text

    private fun failure(text: String): String = assertIs<Formatted.Failed>(JsonText.format(text)).reason

    @Test
    fun `indents with two spaces and puts commas and colons as the view does`() {
        assertEquals(
            "{\n  \"a\": [\n    1,\n    {\n      \"b\": null\n    }\n  ],\n  \"c\": true\n}\n",
            format("{\"a\":[1,{\"b\":null}],\"c\":true}"),
        )
    }

    @Test
    fun `empty containers stay on one line`() {
        assertEquals("{\n  \"a\": {},\n  \"b\": []\n}\n", format("{\"a\":{},\"b\":[]}"))
        assertEquals("[]\n", format("[]"))
    }

    @Test
    fun `numbers are kept as spelled and strings are re-quoted`() {
        assertEquals(
            "[\n  1.0,\n  1e10,\n  -0,\n  \"a\\\"b\\n\\u0001é\"\n]\n",
            format("[1.0, 1e10, -0, \"a\\\"b\\n\\u0001\\u00e9\"]"),
        )
    }

    @Test
    fun `comments and trailing commas are dropped and keys keep their order`() {
        assertEquals(
            "{\n  \"z\": 1,\n  \"a\": 2,\n  \"z\": 3\n}\n",
            format("// top\n{\"z\": 1, /* x */ \"a\": 2, \"z\": 3,}"),
        )
    }

    @Test
    fun `a byte order mark is ignored`() {
        assertEquals("[\n  1\n]\n", format("\uFEFF[1]"))
    }

    @Test
    fun `the output is the same JSON`() {
        val mapper = JsonMapper()
        val samples =
            listOf(
                "{\"a\":[1,2.50,{\"b\":null,\"c\":\"x\\ty\"}],\"d\":{},\"e\":[],\"f\":false}",
                "[[[]],[{}],\"\\u00e9\\\"/\\\\\"]",
                "\"just a string\"",
                "12345678901234567890",
                "{\"k/\\\"q\":{\"~\":[true]}}",
            )
        for (sample in samples) {
            assertEquals(mapper.readTree(sample), mapper.readTree(format(sample)), sample)
        }
    }

    @Test
    fun `an error fails with the message of the view`() {
        val text = "{\n  \"a\": 1,\n  \"b\" 2\n}"
        val reason = failure(text)

        assertTrue(reason.startsWith("Line 3, column "), reason)
        val notice = Jsoup.parse(JsonRenderer.render(text, false)).selectFirst("p.fold-error")
        assertEquals(notice?.text(), reason)
    }

    @Test
    fun `an unclosed container fails like the view`() {
        assertTrue(failure("{\n  \"a\": [1,\n").contains("the array opened on line 2 is not closed"))
    }

    @Test
    fun `empty text fails`() {
        assertEquals("The file is empty", failure(""))
        assertEquals("The file is empty", failure("  \n"))
        assertEquals("The file is empty", failure("// only a comment\n"))
    }

    @Test
    fun `more values than the page shows are formatted in full`() {
        val count = JsonRenderer.MAX_VALUES + 5
        val lines = format((1..count).joinToString(",", "[", "]")).lines()

        assertEquals(count + 3, lines.size)
        assertEquals("  $count", lines[count])
    }
}
