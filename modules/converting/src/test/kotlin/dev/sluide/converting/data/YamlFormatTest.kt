package dev.sluide.converting.data

import dev.sluide.converting.ConversionResult
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import dev.sluide.converting.convert
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.DoubleNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.StringNode

class YamlFormatTest {
    companion object {
        private val multiLineStrings =
            listOf("a\nb", "a\nb\n", "a\n\n", " lead\nx", " a\n\n b\n", "\nx", "\n\nx\n", "tab\tx\n  y\n", "a\n b\n  c")

        @JvmStatic
        fun multiLineCases(): List<Arguments> {
            val mapper = JsonMapper.builder().build()
            return listOf("2", "4").flatMap { indent ->
                multiLineStrings.flatMap { text ->
                    val string = mapper.writeValueAsString(text)
                    listOf("{\"v\":$string}", "[$string]", "{\"v\":[$string]}", "[{$string:[$string]}]").map {
                        Arguments.of(indent, it)
                    }
                }
            }
        }
    }

    private val family = DataFamily()

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "true|true", "True|true", "FALSE|false",
            "yes|\"yes\"", "no|\"no\"", "on|\"on\"", "off|\"off\"", "y|\"y\"", "NO|\"NO\"",
            "~|null", "null|null", "Null|null", "NULL|null", "''|null",
            "12|12", "-3|-3", "+7|7", "0o17|15", "0x1F|31", "0|0", "0644|420", "-012|-10", "089|\"089\"", "0.5|0.5",
            "12345678901234567890|12345678901234567890",
            "1.0|1.0", "1.50|1.50", ".5|0.5", "1e3|1E+3",
            "12:30|\"12:30\"", "2024-01-01|\"2024-01-01\"", "1_000|\"1_000\"", "0b11|\"0b11\"",
            "\"true\"|\"true\"", "!!str 12|\"12\"", "!!int \"12\"|12",
        ],
    )
    fun `plain scalars resolve by the YAML 1_2 core schema`(
        yaml: String,
        json: String,
    ) {
        assertEquals("""{"v":$json}""", toJson("v: $yaml"))
    }

    @Test
    fun `numbers of more than 1000 digits stay strings`() {
        val digits = "7".repeat(1001)

        val document = documents("[$digits, -$digits, 1.$digits, 0x$digits]").single()

        assertEquals(
            listOf(digits, "-$digits", "1.$digits", "0x$digits").map(StringNode::valueOf),
            (0..3).map { document[it] },
        )
    }

    @Test
    fun `integers with a leading zero are counted in a note`() {
        val read = read("mode: 0644\nother: 0755\nzip: 089\nzero: 0")

        assertEquals(listOf("2 numbers with a leading zero read as octal"), read.notes)
    }

    @Test
    fun `infinities and NaN read as doubles`() {
        val document = documents("a: .inf\nb: -.Inf\nc: .NaN").single()

        assertEquals(DoubleNode.valueOf(Double.POSITIVE_INFINITY), document["a"])
        assertEquals(DoubleNode.valueOf(Double.NEGATIVE_INFINITY), document["b"])
        assertTrue(document["c"].doubleValue().isNaN())
    }

    @Test
    fun `scalar keys of any type become their text`() {
        assertEquals(
            """{"1":"a","true":"b","~":"c","on":"d","1.5":"e","":"f"}""",
            toJson("1: a\ntrue: b\n~: c\non: d\n1.5: e\n'': f"),
        )
    }

    @Test
    fun `several documents read as several, documents without content are left out`() {
        val documents = documents("---\na: 1\n---\n---\n- 2\n--- ~\n---\n")

        assertEquals(listOf("""{"a":1}""", "[2]", "null"), documents.map { it.toString() })
    }

    @Test
    fun `aliases are expanded and merge keys resolved, explicit keys and earlier mappings winning`() {
        val yaml =
            """
            base: &base {a: 1, b: 2}
            more: &more {b: 3, c: 4}
            one:
              <<: *base
              b: 9
            two:
              <<: [*more, *base]
              d: 5
            list: [*base, *base]
            """.trimIndent()

        assertEquals(
            """{"base":{"a":1,"b":2},"more":{"b":3,"c":4},"one":{"a":1,"b":9},""" +
                """"two":{"b":3,"c":4,"a":1,"d":5},"list":[{"a":1,"b":2},{"a":1,"b":2}]}""",
            toJson(yaml),
        )
    }

    @Test
    fun `binary becomes Base64 text and other tags keep their value with a note`() {
        val read =
            read(
                "bin: !!binary |\n  SGVs\n  bG8=\nref: !Ref Bucket\nsub: !Sub '${'$'}{AWS::Region}'\nagain: !Ref Queue\nlist: !Each [1]",
            )

        assertEquals(
            """{"bin":"SGVsbG8=","ref":"Bucket","sub":"${'$'}{AWS::Region}","again":"Queue","list":[1]}""",
            read.content.documents
                .single()
                .toString(),
        )
        assertEquals(listOf("Tag !Ref dropped 2 times", "Tag !Sub dropped", "Tag !Each dropped"), read.notes)
    }

    @Test
    fun `comments are counted, a hash in a string is no comment`() {
        val read = read("# head\na: 'x # y' # tail\n\n# block\n#second\nb: 1\n")

        assertEquals(listOf("4 comments dropped"), read.notes)
    }

    @ParameterizedTest
    @ValueSource(strings = ["? # c\n  a\n: b", "a: &x # c\n  b: 1\nc: *x # d", "a: !Ref # c\n  x"])
    fun `comments SnakeYAML cannot count are still noted`(yaml: String) {
        assertTrue("Comments dropped" in read(yaml).notes, read(yaml).notes.toString())
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "a: 1\\na: 2|Duplicate key a|5",
            "a: 1\\n<<: {b: 1}\\n<<: {c: 1}|Duplicate key <<|16",
            "? [a]\\n: 1|A key must be a scalar, not a collection|2",
            "a: <<\\nb:\\n  <<: 1|A merge key takes a mapping or a list of mappings|15",
            "a: !!int x|Not an integer: x|3",
        ],
    )
    fun `invalid content fails at its position`(
        yaml: String,
        message: String,
        offset: Int,
    ) {
        assertEquals(ReadResult.Failed(message, offset), YamlFormat.read(yaml.replace("\\n", "\n"), OptionValues()))
    }

    @Test
    fun `a syntax error carries SnakeYAML's message at its char offset, counting emoji as two chars`() {
        val failed = YamlFormat.read("a: 😀\nb: [1, 2\nc: 3", OptionValues()) as ReadResult.Failed

        assertTrue(failed.message.isNotBlank())
        assertEquals("a: 😀\nb: [1, 2\nc".length, failed.offset)
    }

    @Test
    fun `an alias to its own anchor fails`() {
        assertEquals(
            ReadResult.Failed("An alias refers to its own anchor", 3),
            YamlFormat.read("a: &x\n  - *x", OptionValues()),
        )
    }

    @Test
    fun `aliases that nest deeper than the limit fail`() {
        val levels =
            (1..6).joinToString("\n", prefix = "a0: &a0 ${"[".repeat(100)}1${"]".repeat(100)}\n") { level ->
                "a$level: &a$level ${"[".repeat(100)}*a${level - 1}${"]".repeat(100)}"
            }

        val failed = YamlFormat.read(levels, OptionValues())

        assertEquals("The YAML is nested deeper than 500 levels", (failed as ReadResult.Failed).message)
    }

    @Test
    fun `alias bombs fail`() {
        val bomb =
            (1..9).joinToString("\n", prefix = "a0: &a0 [x, x, x, x, x, x, x, x, x]\n") { level ->
                "a$level: &a$level [${(1..9).joinToString { "*a${level - 1}" }}]"
            }

        assertEquals(
            ReadResult.Failed("The YAML expands to more than 100000 values", null),
            YamlFormat.read(bomb, OptionValues()),
        )
    }

    @Test
    fun `many merges of the same defaults read`() {
        val yaml =
            (1..60).joinToString("\n", prefix = ".defaults: &defaults\n  image: alpine\n  tags: [docker]\n") { job ->
                "job$job:\n  <<: *defaults\n  script: [make $job]"
            }

        val document = documents(yaml).single()

        assertEquals(61, document.size())
        assertEquals("""{"image":"alpine","tags":["docker"],"script":["make 60"]}""", document["job60"].toString())
    }

    @Test
    fun `reading fails when aliases expand beyond the value limit`() {
        val yaml = "a: &a [1, 2, 3]\nb: [*a, *a, *a]"

        assertTrue(YamlReader(maxNodes = 30).read(yaml) is ReadResult.Read)
        assertEquals(
            ReadResult.Failed("The YAML expands to more than 10 values", null),
            YamlReader(maxNodes = 10).read(yaml),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "yes", "no", "on", "off", "y", "n", "Y", "~", "null", "true", "False", "0123", "1e3", "12:30",
            "2024-01-01", "", " lead", "trail ", "a: b", "#x", "- x", "@x", "`x", "12", "1.5", ".inf", "0o17",
            "0x1F", "<<", "!x", "*x", "&x", "{x}", "[x]", "'x", "\"x", "%x", "|x", ">x", "?", "x #y",
        ],
    )
    fun `strings a YAML reader would misread are quoted and read back as strings`(text: String) {
        val node =
            JsonNodeFactory.instance
                .objectNode()
                .put("v", text)
                .put(text, "k")

        val yaml = write(node)

        // An empty key is written as an explicit key: `? ''`.
        val (value, key) = yaml.lines().map { it.removePrefix("v: ").removePrefix("? ") }
        assertTrue(value.startsWith("'") || value.startsWith("\""), yaml)
        assertTrue(key.startsWith("'") || key.startsWith("\""), yaml)
        assertEquals(node, documents(yaml).single())
    }

    @Test
    fun `numbers, booleans, null and plain text are written unquoted`() {
        val node =
            tree("""{"a":1,"b":1.0,"c":true,"d":null,"e":"plain text","f":12345678901234567890123,"g":-0.5,"h":1E+3}""")

        assertEquals(
            "a: 1\nb: 1.0\nc: true\nd: null\ne: plain text\nf: 12345678901234567890123\ng: -0.5\nh: 1E+3\n",
            write(node),
        )
        assertEquals(node, documents(write(node)).single())
    }

    @Test
    fun `NaN and infinities are written as YAML floats`() {
        val node =
            JsonNodeFactory.instance
                .objectNode()
                .set("a", DoubleNode.valueOf(Double.NaN))
                .set("b", DoubleNode.valueOf(Double.POSITIVE_INFINITY))
                .set("c", DoubleNode.valueOf(Double.NEGATIVE_INFINITY))

        assertEquals("a: .nan\nb: .inf\nc: -.inf\n", write(node))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = [
            "aNLb;v: |-NL  aNL  bNL",
            "aNLbNL;v: |NL  aNL  bNL",
            "aNLNL;v: |+NL  aNLNL",
            "' aNLb';v: \" a\\nb\"NL",
            "NLx;v: \"\\nx\"NL",
        ],
    )
    fun `multi-line strings are written as literal blocks unless they start with a space or a line break`(
        text: String,
        yaml: String,
    ) {
        // NL stands for a line break; `\n` in the expected YAML is an escape in a double-quoted string.
        assertEquals(
            yaml.replace("NL", "\n"),
            write(JsonNodeFactory.instance.objectNode().put("v", text.replace("NL", "\n"))),
        )
    }

    @ParameterizedTest
    @MethodSource("multiLineCases")
    fun `multi-line strings read back the same in mappings, lists and lists in mappings at both indents`(
        indent: String,
        json: String,
    ) {
        val node = tree(json)

        assertEquals(node, documents(write(node, indent = indent)).single(), write(node, indent = indent))
    }

    @ParameterizedTest
    @ValueSource(strings = ["\u0085", "\u2028", "\u2029"])
    fun `strings and keys with Unicode line breaks are double-quoted and read back the same`(lineBreak: String) {
        val node =
            JsonNodeFactory.instance
                .objectNode()
                .put("v", "a${lineBreak}b")
                .put("k${lineBreak}k", "x")

        val yaml = write(node)

        assertTrue(lineBreak !in yaml && "v: \"a\\" in yaml && "? \"k\\" in yaml, yaml)
        assertEquals(node, documents(yaml).single())
    }

    @ParameterizedTest
    @CsvSource("2", "4")
    fun `block style is indented by the indent option`(indent: Int) {
        val pad = " ".repeat(indent)
        val node = tree("""{"a":{"b":[1,{"c":2,"d":[]}],"e":{}},"f":"ü 😀"}""")

        assertEquals(
            "a:\n${pad}b:\n$pad  - 1\n$pad  - c: 2\n$pad    d: []\n${pad}e: {}\nf: ü 😀\n",
            write(node, indent = indent.toString()),
        )
    }

    @Test
    fun `one document has no separator, several are separated by three dashes`() {
        assertEquals("a: 1\n", write(tree("""{"a":1}""")))
        val written = YamlFormat.write(DataContent(listOf(tree("""{"a":1}"""), tree("[2]"), tree("3"))), OptionValues())

        assertEquals(WriteResult.Written("a: 1\n---\n- 2\n---\n3\n", emptyList()), written)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = [
            "2;- |-NL  aNL  bNL- x: |NL    cNL  z:NL    - 1NL",
            "4;- |-NL  aNL  bNL- x: |NL      cNL  z:NL    - 1NL",
        ],
    )
    fun `a list at the root starts at the left margin, block content included`(
        indent: String,
        yaml: String,
    ) {
        // NL stands for a line break.
        assertEquals(yaml.replace("NL", "\n"), write(tree("""["a\nb",{"x":"c\n","z":[1]}]"""), indent = indent))
    }

    @Test
    fun `sort keys sorts every mapping`() {
        assertEquals("a:\n  c: 1\n  d: 2\nb: 3\n", write(tree("""{"b":3,"a":{"d":2,"c":1}}"""), sortKeys = true))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "a: 1|80",
            "- a\\n- b|80",
            "a: 1\\n---\\nb: 2|85",
            "# config\\na: 1|80",
            "{\"a\": 1}|30",
            "# list\\n[1, 2]|30",
            "{a: 1}|30",
            "just text|10",
            "12|10",
        ],
    )
    fun `detection prefers mappings, sequences and several documents`(
        text: String,
        score: Int,
    ) {
        assertEquals(score, YamlFormat.detect(text.replace("\\n", "\n")))
    }

    @Test
    fun `text that does not read is not detected`() {
        assertEquals(null, YamlFormat.detect("a: [1"))
    }

    private fun read(yaml: String) = YamlFormat.read(yaml, OptionValues()) as ReadResult.Read

    private fun documents(yaml: String) = read(yaml).content.documents

    private fun tree(json: String): JsonNode =
        (JsonFormat.read(json, OptionValues()) as ReadResult.Read).content.documents.single()

    private fun toJson(yaml: String): String =
        (
            family.convert(
                yaml,
                YamlFormat,
                JsonFormat,
                OptionValues().with("compact", true),
            ) as ConversionResult.Converted
        ).text

    private fun write(
        node: JsonNode,
        indent: String = "2",
        sortKeys: Boolean = false,
    ): String {
        val options = OptionValues(mapOf("indent" to indent, "sortKeys" to sortKeys))
        return (YamlFormat.write(DataContent(listOf(node)), options) as WriteResult.Written).text
    }
}
