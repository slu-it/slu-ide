package dev.sluide.converting.data

import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.DoubleNode
import tools.jackson.databind.node.JsonNodeFactory

class JsonFormatTest {
    @Test
    fun `comments and trailing commas are read, comments are counted`() {
        val read = read("// header\n{\n  \"a\": 1, /* inline */\n  \"b\": [1, 2,],\n}\n")

        assertEquals(tree("""{"a":1,"b":[1,2]}"""), read.content.documents.single())
        assertEquals(listOf("2 comments dropped"), read.notes)
    }

    @Test
    fun `comment markers inside strings are not comments`() {
        val read = read("""{"url": "http://example.com/*x*/", "q": "\"//"} // one""")

        assertEquals(
            "http://example.com/*x*/",
            read.content.documents
                .single()["url"]
                .asString(),
        )
        assertEquals(listOf("1 comment dropped"), read.notes)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"a": 1, "a": 2}|Duplicate""",
            """{"a": 1} {"b": 2}|Trailing""",
            """{"a": 1|Unexpected end""",
            """{"a" 1}|Unexpected character""",
        ],
    )
    fun `invalid JSON fails with Jackson's message`(
        text: String,
        start: String,
    ) {
        val failed = JsonFormat.read(text, OptionValues()) as ReadResult.Failed

        assertTrue(failed.message.startsWith(start), failed.message)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"a": 1, "a": 2}|12""",
            """{"a": 1,\n "b" 2}|14""",
        ],
    )
    fun `a read error carries Jackson's char offset`(
        text: String,
        offset: Int,
    ) {
        val failed = JsonFormat.read(text.replace("\\n", "\n"), OptionValues()) as ReadResult.Failed

        assertEquals(offset, failed.offset)
    }

    @Test
    fun `numbers are kept exact`() {
        val text = """{"a":1.0,"b":12345678901234567890123,"c":1.50,"d":-0.000001,"e":1E+3}"""

        assertEquals(text, write(read(text).content.documents.single(), compact = true))
    }

    @ParameterizedTest
    @CsvSource("2", "4")
    fun `pretty output is indented, keeps empty containers short and ends with a newline`(indent: Int) {
        val pad = " ".repeat(indent)
        val node = tree("""{"a":1,"b":[true,null],"c":{},"d":[],"e":"ü 😀"}""")

        assertEquals(
            "{\n$pad\"a\": 1,\n$pad\"b\": [\n$pad${pad}true,\n$pad${pad}null\n$pad],\n$pad\"c\": {},\n$pad\"d\": []," +
                "\n$pad\"e\": \"ü 😀\"\n}\n",
            write(node, indent = indent.toString()),
        )
    }

    @Test
    fun `compact output is one line without a newline`() {
        assertEquals("""{"a":[1,2],"b":{"c":"x"}}""", write(tree("""{"a":[1,2],"b":{"c":"x"}}"""), compact = true))
    }

    @Test
    fun `sort keys sorts nested objects too`() {
        val node = tree("""{"b":1,"a":{"d":[{"z":1,"y":2}],"c":2}}""")

        assertEquals("""{"a":{"c":2,"d":[{"y":2,"z":1}]},"b":1}""", write(node, compact = true, sortKeys = true))
    }

    @Test
    fun `several documents are written as an array with a note`() {
        val written =
            JsonFormat.write(
                DataContent(listOf(tree("1"), tree("""{"a":2}"""), tree("null"))),
                OptionValues().with("compact", true),
            ) as WriteResult.Written

        assertEquals("""[1,{"a":2},null]""", written.text)
        assertEquals(listOf("3 documents written as an array"), written.notes)
    }

    @Test
    fun `NaN and infinities are written as strings with a note`() {
        val node =
            JsonNodeFactory.instance
                .arrayNode()
                .add(DoubleNode.valueOf(Double.NaN))
                .add(DoubleNode.valueOf(Double.POSITIVE_INFINITY))
                .add(DoubleNode.valueOf(Double.NEGATIVE_INFINITY))
                .add(DoubleNode.valueOf(1.5))
        val written =
            JsonFormat.write(
                DataContent(listOf(node)),
                OptionValues().with("compact", true),
            ) as WriteResult.Written

        assertEquals("""["NaN","Infinity","-Infinity",1.5]""", written.text)
        assertEquals(listOf("3 NaNs or infinities written as strings"), written.notes)
    }

    @Test
    fun `a tree deeper than Jackson writes fails instead of throwing`() {
        val root = JsonNodeFactory.instance.arrayNode()
        (1..2000).fold(root) { parent, _ -> parent.addArray() }

        assertTrue(JsonFormat.write(DataContent(listOf(root)), OptionValues()) is WriteResult.Failed)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"a": 1}|100""",
            """[1, 2]|100""",
            """// config\n{"a": 1}|100""",
            """123|20""",
            """"x"|20""",
            """true|20""",
        ],
    )
    fun `detection is sure of objects and arrays, unsure of scalars`(
        text: String,
        score: Int,
    ) {
        assertEquals(score, JsonFormat.detect(text.replace("\\n", "\n")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["a: 1", "{a: 1}", "// only a comment", "hello", "{\"a\": 1"])
    fun `text that does not read is not detected`(text: String) {
        assertEquals(null, JsonFormat.detect(text))
    }

    private fun read(text: String) = JsonFormat.read(text, OptionValues()) as ReadResult.Read

    private fun tree(json: String): JsonNode = read(json).content.documents.single()

    private fun write(
        node: JsonNode,
        indent: String = "2",
        compact: Boolean = false,
        sortKeys: Boolean = false,
    ): String {
        val options = OptionValues(mapOf("indent" to indent, "compact" to compact, "sortKeys" to sortKeys))
        return (JsonFormat.write(DataContent(listOf(node)), options) as WriteResult.Written).text
    }
}
