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
import java.math.BigInteger

class TomlFormatTest {
    @Test
    fun `tables, arrays of tables and values are read exactly`() {
        val text =
            """
            title = "x"
            big = 12345678901234567890123
            price = 1.50
            when = 1979-05-27T07:32:00Z
            day = 1979-05-27
            [a.b]
            c = [1, 2]
            [[items]]
            n = 1
            [[items]]
            n = 2
            """.trimIndent()

        val node = read(text).content.documents.single()

        assertEquals(
            json(
                """{"title":"x","big":12345678901234567890123,"price":1.50,"when":"1979-05-27T07:32:00Z",""" +
                    """"day":"1979-05-27","a":{"b":{"c":[1,2]}},"items":[{"n":1},{"n":2}]}""",
            ),
            node,
        )
        assertTrue(node["price"].isBigDecimal)
        assertEquals("1.50", node["price"].decimalValue().toPlainString())
    }

    @Test
    fun `nan and infinity are read as doubles`() {
        val node = read("a = nan\nb = inf\nc = -inf\n").content.documents.single()

        assertTrue(node["a"].doubleValue().isNaN())
        assertEquals(Double.POSITIVE_INFINITY, node["b"].doubleValue())
        assertEquals(Double.NEGATIVE_INFINITY, node["c"].doubleValue())
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "# one\\na = 1 # two\\nb = \"# not\"|2",
            "a = '''\\n# not\\n'''\\nb = \"\"\"\\n# not \\\" # not\\n\"\"\" # yes|1",
            "a = 'x # not'\\nb = \"q\\\"# not\" #yes|1",
            "a = 1|0",
        ],
    )
    fun `comments are counted outside strings`(
        text: String,
        count: Int,
    ) {
        val notes = read(text.replace("\\n", "\n")).notes

        val expected =
            when (count) {
                0 -> emptyList()
                else -> listOf(counted(count, "comment dropped", "comments dropped"))
            }

        assertEquals(expected, notes)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "a = 1\\na = 2|11",
            "[a]\\nb = 1\\n[a]\\nc = 2|12",
            "a = 1\\nb = |9",
            "a = [1, 2|9",
            "a 1|1",
        ],
    )
    fun `invalid TOML fails with the offset of Jackson's location`(
        text: String,
        offset: Int,
    ) {
        val failed = TomlFormat.read(text.replace("\\n", "\n"), OptionValues()) as ReadResult.Failed

        assertEquals(offset, failed.offset, failed.message)
    }

    @ParameterizedTest
    @CsvSource(
        "1234567890123456789",
        "-9223372036854775808",
        "9223372036854775807",
        "+1234567890123456789",
        "1_234_567_890_123_456_789",
    )
    fun `integers of 19 digits are read exactly`(literal: String) {
        val text = "a = $literal\nb = [$literal]\nc = { d = $literal }\ne = \"$literal\"\n"
        val node = read(text).content.documents.single()
        val expected = BigInteger(literal.replace("_", "").removePrefix("+"))

        assertEquals(expected, node["a"].bigIntegerValue())
        assertEquals(expected, node["b"][0].bigIntegerValue())
        assertEquals(expected, node["c"]["d"].bigIntegerValue())
        assertEquals(literal, node["e"].asString())
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "[1234567890123456789]\\nx = 1",
            "[[1234567890123456789]]\\nx = 1",
            "[ 1234567890123456789 ]\\nx = 1",
            "1234567890123456789 . x = 1",
            "1234567890123456789 = 1",
        ],
    )
    fun `bare keys of 19 digits stay keys`(text: String) {
        val node = read(text.replace("\\n", "\n")).content.documents.single()

        assertEquals(listOf("1234567890123456789"), node.propertyNames().toList())
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "1234567890123456789 = 1\\n1234567890123456789 = 2",
            "[1234567890123456789]\\n[1234567890123456789]",
            "1234567890123456789 = 1\\n\"1234567890123456789\" = 2",
            "\"1234567890123456789\" = 1\\n1234567890123456789 = 2",
            "+1234567890123456789 = 1",
            "+1234567890123456789 . a = 1",
        ],
    )
    fun `repeated or signed bare keys of 19 digits fail`(text: String) {
        assertTrue(TomlFormat.read(text.replace("\\n", "\n"), OptionValues()) is ReadResult.Failed)
    }

    @Test
    fun `a literal with a leading zero is still refused`() {
        assertTrue(TomlFormat.read("a = 0123456789012345678", OptionValues()) is ReadResult.Failed)
    }

    @Test
    fun `an empty document reads as an empty object`() {
        assertEquals(json("{}"), read("# nothing\n").content.documents.single())
    }

    @Test
    fun `tables are written as a person writes them`() {
        val node =
            json(
                """{"title":"x","tags":["a","b"],"owner":{"name":"Tom","dob":"1979"},""" +
                    """"db":{"ports":[1,2],"conn":{"max":5}},""" +
                    """"servers":[{"ip":"1","roles":["a"]},{"ip":"2","cfg":{"x":true}}],""" +
                    """"empty":{},"mixed":[1,{"a":2},[3]]}""",
            )

        assertEquals(
            """
            title = "x"
            tags = ["a", "b"]
            mixed = [1, { a = 2 }, [3]]

            [owner]
            name = "Tom"
            dob = "1979"

            [db]
            ports = [1, 2]

            [db.conn]
            max = 5

            [[servers]]
            ip = "1"
            roles = ["a"]

            [[servers]]
            ip = "2"

            [servers.cfg]
            x = true

            [empty]

            """.trimIndent(),
            write(node),
        )
    }

    @Test
    fun `a table without values of its own has no header`() {
        assertEquals("[a.b]\nc = 1\n", write(json("""{"a":{"b":{"c":1}}}""")))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"a-b_1":1}|a-b_1 = 1""",
            """{"a b":1}|"a b" = 1""",
            """{"a.b":1}|"a.b" = 1""",
            """{"":1}|"" = 1""",
            """{"ü":1}|"ü" = 1""",
            """{"a":"q\"\\"}|a = "q\"\\"""",
            """{"a":"t\tb\u0001"}|a = "t\tb\u0001"""",
            """{"a":1.0}|a = 1.0""",
            """{"a":1E+3}|a = 1E+3""",
            """{"a":-0.000001}|a = -0.000001""",
            """{"a":true}|a = true""",
            """{"a":[]}|a = []""",
        ],
    )
    fun `keys and scalars are written`(
        json: String,
        line: String,
    ) {
        assertEquals("$line\n", write(json(json)))
    }

    @Test
    fun `multi-line strings are written as triple quoted strings`() {
        val node = json("""{"a":"x\ny\n","b":"q\"\"\"\"w\nz\"","c":"\nlead\r\nend"}""")

        assertEquals(
            "a = \"\"\"\nx\ny\n\"\"\"\nb = \"\"\"\nq\"\"\\\"\"w\nz\\\"\"\"\"\nc = \"\"\"\n\nlead\\r\nend\"\"\"\n",
            write(node),
        )
    }

    @Test
    fun `nulls are dropped and big integers become strings, with notes`() {
        val written =
            TomlFormat.write(
                DataContent(
                    listOf(
                        json(
                            """{"a":null,"b":[1,null,2],"c":{"d":null},"e":12345678901234567890,"f":9223372036854775807}""",
                        ),
                    ),
                ),
                OptionValues(),
            ) as WriteResult.Written

        assertEquals(
            "b = [1, 2]\ne = \"12345678901234567890\"\nf = 9223372036854775807\n\n[c]\n",
            written.text,
        )
        assertEquals(listOf("3 nulls dropped", "1 integer beyond 64 bit written as a string"), written.notes)
    }

    @Test
    fun `NaN and infinities are written as nan and inf`() {
        val node = read("a = nan\nb = inf\nc = -inf\n").content.documents.single()

        assertEquals("a = nan\nb = inf\nc = -inf\n", write(node))
    }

    @Test
    fun `sort keys sorts every level`() {
        assertEquals(
            "a = 1\nb = 2\n\n[c]\nx = 1\ny = 2\n",
            write(json("""{"b":2,"c":{"y":2,"x":1},"a":1}"""), sortKeys = true),
        )
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """[1]|TOML needs an object at the top, not an array""",
            """"x"|TOML needs an object at the top, not a value""",
            """null|TOML needs an object at the top, not a value""",
        ],
    )
    fun `a top level that is not an object fails`(
        json: String,
        message: String,
    ) {
        val failed = TomlFormat.write(DataContent(listOf(json(json))), OptionValues()) as WriteResult.Failed

        assertEquals(message, failed.message)
    }

    @Test
    fun `several documents fail`() {
        val failed =
            TomlFormat.write(
                DataContent(listOf(json("{}"), json("{}"), json("{}"))),
                OptionValues(),
            ) as WriteResult.Failed

        assertEquals("TOML holds one document, not 3", failed.message)
    }

    @Test
    fun `an empty object is written as nothing`() {
        assertEquals("", write(json("{}")))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"a":1,"b":{"c":[1,2.50,"x",true],"d":{"e":{"f":"g"}}},"items":[{"n":1,"sub":{"k":[{"z":1},{"z":2}]}},{"n":2}]}""",
            """{"s":"line1\nline2 \"q\" \\ \t ü 😀","e":"","k.ey":{"a b":1},"m":[[1,2],[{"a":1}],{"b":[]}]}""",
            """{"dec":1.50,"neg":-5,"long":9223372036854775807,"x":1E+3}""",
            """{"a":{},"b":[],"c":{"d":{}}}""",
        ],
    )
    fun `JSON survives a round trip through TOML`(json: String) {
        val tree = json(json)
        val toml = write(tree)

        assertEquals(tree, read(toml).content.documents.single(), toml)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "[server]\\nport = 1|90",
            "a = 1|90",
            "a=1|50",
            "server.port=8080|50",
            "a=1\\n[t]|90",
            "a = 1\\nb=2|90",
            "[[x]]\\n[x.y]|90",
            "\"a b\".c = 1|90",
            "[a]|90",
            "# only|",
            "a: 1|",
            "{\"a\": 1}|",
            "hello|",
        ],
    )
    fun `detection needs a header or an assignment`(
        text: String,
        score: Int?,
    ) {
        assertEquals(score, TomlFormat.detect(text.replace("\\n", "\n")))
    }

    private fun read(text: String) = TomlFormat.read(text, OptionValues()) as ReadResult.Read

    private fun json(json: String): JsonNode =
        (JsonFormat.read(json, OptionValues()) as ReadResult.Read).content.documents.single()

    private fun write(
        node: JsonNode,
        sortKeys: Boolean = false,
    ): String =
        (
            TomlFormat.write(
                DataContent(listOf(node)),
                OptionValues(mapOf("sortKeys" to sortKeys)),
            ) as WriteResult.Written
        ).text
}
