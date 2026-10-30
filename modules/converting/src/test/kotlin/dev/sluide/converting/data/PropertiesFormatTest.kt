package dev.sluide.converting.data

import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.JsonNode

class PropertiesFormatTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """a=1|{"a":1}""",
            """a.b=1¶a.c=x|{"a":{"b":1,"c":"x"}}""",
            """a[0]=x¶a[1]=y|{"a":["x","y"]}""",
            """a[1]=y¶a[0]=x|{"a":["x","y"]}""",
            """a[0].b=1¶a[0].c=2¶a[1].b=3|{"a":[{"b":1,"c":2},{"b":3}]}""",
            """a[0][1]=y¶a[0][0]=x|{"a":[["x","y"]]}""",
            """a[b.c]=1|{"a":{"b.c":1}}""",
            """x.[a.b].c=1|{"x":{"a.b":{"c":1}}}""",
            """[a.b]=1|{"a.b":1}""",
            """logging.level[com.example]=DEBUG|{"logging":{"level":{"com.example":"DEBUG"}}}""",
            """b=1¶a=2|{"b":1,"a":2}""",
            """a = b|{"a":"b"}""",
            """a: b|{"a":"b"}""",
            """a b c|{"a":"b c"}""",
            """a   =   b  ¶x=1|{"a":"b  ","x":1}""",
            """a=|{"a":""}""",
            """a|{"a":""}""",
            """a\=b=c|{"a=b":"c"}""",
            """a\ b=c|{"a b":"c"}""",
            """a=b\¶    c|{"a":"bc"}""",
            """a=b\\¶c=d|{"a":"b\\","c":"d"}""",
            """a=\u00e4\t\n\x\\|{"a":"ä\t\nx\\"}""",
            """a=true¶b=false¶c=1.50¶d=0123¶e=12345678901234567890¶f=1e3|""" +
                """{"a":true,"b":false,"c":1.50,"d":"0123","e":12345678901234567890,"f":"1e3"}""",
            """a=${'$'}{x:1}|{"a":"${'$'}{x:1}"}""",
            """a=1¦¶b=2¦c=3|{"a":1,"b":2,"c":3}""",
        ],
    )
    fun `text reads as a tree`(
        text: String,
        json: String,
    ) {
        assertEquals(tree(json), read(unescape(text)).content.documents.single())
    }

    @Test
    fun `values stay text without type inference`() {
        val read =
            PropertiesFormat.read(
                "a=1\nb=true\nc=\n",
                OptionValues().with("inferTypes", false),
            ) as ReadResult.Read

        assertEquals(tree("""{"a":"1","b":"true","c":""}"""), read.content.documents.single())
    }

    @Test
    fun `separator lines split documents`() {
        val read = read("a=1\n#---\nb=2\n!---  \nc=3\n#--- not\n")

        assertEquals(
            listOf(tree("""{"a":1}"""), tree("""{"b":2}"""), tree("""{"c":3}""")),
            read.content.documents,
        )
        assertEquals(listOf("1 comment dropped"), read.notes)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "# one¶! two¶   #three¶a=1¶#---¶b=2|3 comments dropped",
            "a=1¶a=2¶b=3¶b=4¶c[0]=1¶c[0]=2|3 duplicate keys, the last value kept",
            "a=1¶a=2|1 duplicate key, the last value kept",
        ],
    )
    fun `notes count comments and duplicate keys`(
        text: String,
        note: String,
    ) {
        assertEquals(listOf(note), read(unescape(text)).notes)
    }

    @Test
    fun `the last value of a repeated key wins and keeps the first position`() {
        val read = read("a=1\nb=2\na=3\n")

        assertEquals(
            """{"a":3,"b":2}""",
            read.content.documents
                .single()
                .toString(),
        )
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "a=1¶a.b=2|4|Key a has both a value and children",
            "a.b=2¶a=1|6|Key a has both a value and children",
            "a.b.c=1¶a.b=2|8|Key a.b has both a value and children",
            "a[0]=1¶a.x=2|7|Key a is both a list and an object",
            "a.x=2¶a[0]=1|6|Key a is both a list and an object",
            "a[0]=1¶a[2]=2|7|List a has no item 1",
            "a[20000000]=1|0|Key a[20000000] leaves out items 0 to 19999999",
            "a[0]=1¶b[5]=1|7|Key b[5] leaves out items 0 to 4",
            "a[99999999999]=1|0|Key a[99999999999] has the index 99999999999, which is too large",
            "a[-1]=1|0|Key a[-1] has [-1], which is not a list index",
            "a[0]=1¶b[3].c=2|7|Key b[3] leaves out items 0 to 2",
            "a[2]=1¶a[0]=2|0|List a has no item 1",
            "a=1¶b=\\u12G4|6|Malformed \\u escape",
            "=x|0|Key is empty",
            "a[0=1|0|Key a[0 misses a ]",
            "a[0]b=1|0|Key a[0]b has text after a ]",
            "a..b=1|0|Key a..b has an empty part",
            "a.=1|0|Key a. has an empty part",
        ],
    )
    fun `invalid properties fail with a message and the offset of the line`(
        text: String,
        offset: Int,
        message: String,
    ) {
        val failed = PropertiesFormat.read(unescape(text), OptionValues()) as ReadResult.Failed

        assertEquals(message, failed.message)
        assertEquals(offset, failed.offset)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"a":1,"b":{"c":"x","d":[1,{"e":true}]}}|a=1¶b.c=x¶b.d[0]=1¶b.d[1].e=true""",
            """{"a.b":1,"c":{"d.e":2},"":3}|[a.b]=1¶c[d.e]=2¶[]=3""",
            """{"a b":"x y","k=:#!":1}|a\ b=x y¶k\=\:\#\!=1""",
            """{"a":" lead","b":"trail ","c":"x y"}|a=\ lead¶b=trail ¶c=x y""",
            """{"a":"l1\nl2\r\t\f\\","b":"\u0001"}|a=l1\nl2\r\t\f\\¶b=\u0001""",
            """{"a":1.50,"b":-3,"c":true,"d":12345678901234567890,"e":1E+3}|""" +
                """a=1.50¶b=-3¶c=true¶d=12345678901234567890¶e=1E+3""",
            """{"b":1,"a":[]}|b=1¶a=""",
            """{}|""",
        ],
    )
    fun `trees are flattened to key lines`(
        json: String,
        lines: String?,
    ) {
        val expected = unescape(lines.orEmpty())

        assertEquals(if (expected.isEmpty()) "" else expected + "\n", write(tree(json)))
    }

    @Test
    fun `null and empty values are written as empty values with notes`() {
        val written =
            PropertiesFormat.write(
                DataContent(listOf(tree("""{"a":null,"b":{"c":null},"d":{},"e":[],"f":[null]}"""))),
                OptionValues(),
            ) as WriteResult.Written

        assertEquals("a=\nb.c=\nd=\ne=\nf[0]=\n", written.text)
        assertEquals(
            listOf("3 nulls written as empty values", "2 empty objects or lists written as empty values"),
            written.notes,
        )
    }

    @Test
    fun `a single null and empty value use the singular`() {
        val written =
            PropertiesFormat.write(
                DataContent(listOf(tree("""{"a":null,"b":{}}"""))),
                OptionValues(),
            ) as WriteResult.Written

        assertEquals(
            listOf("1 null written as an empty value", "1 empty object or list written as an empty value"),
            written.notes,
        )
    }

    @Test
    fun `documents are joined by separator lines`() {
        assertEquals(
            "a=1\n#---\nb=2\n",
            writeAll(listOf(tree("""{"a":1}"""), tree("""{"b":2}"""))),
        )
    }

    @Test
    fun `sort keys sorts every level before flattening`() {
        assertEquals(
            "a=1\nb.x=1\nb.y=2\nc=3\n",
            write(tree("""{"c":3,"b":{"y":2,"x":1},"a":1}"""), sortKeys = true),
        )
    }

    @Test
    fun `non-ASCII characters are escaped per UTF-16 unit, or kept`() {
        val node = tree("""{"kü":"ü😀 x"}""")

        assertEquals("k\\u00FC=\\u00FC\\uD83D\\uDE00 x\n", write(node))
        assertEquals("kü=ü😀 x\n", write(node, escapeNonAscii = false))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """[1]|Properties need an object at the top, not an array""",
            """"x"|Properties need an object at the top, not a value""",
        ],
    )
    fun `a top level that is not an object fails`(
        json: String,
        message: String,
    ) {
        val failed = PropertiesFormat.write(DataContent(listOf(tree(json))), OptionValues()) as WriteResult.Failed

        assertEquals(message, failed.message)
    }

    @Test
    fun `a key with a closing bracket and a dot cannot be written`() {
        val failed =
            PropertiesFormat.write(
                DataContent(listOf(tree("""{"a.b]":1}"""))),
                OptionValues(),
            ) as WriteResult.Failed

        assertTrue(failed.message.contains("a.b]"), failed.message)
    }

    @Test
    fun `a plain key may contain a closing bracket`() {
        val node = tree("""{"a]":1,"p":{"q]":2}}""")

        assertEquals("a]=1\np.q]=2\n", write(node))
        assertEquals(node, read(write(node)).content.documents.single())
    }

    @Test
    fun `empty documents are dropped with a note`() {
        val written =
            PropertiesFormat.write(
                DataContent(listOf(tree("{}"), tree("""{"a":1}"""), tree("{}"))),
                OptionValues(),
            ) as WriteResult.Written

        assertEquals("a=1\n", written.text)
        assertEquals(listOf("2 empty documents dropped"), written.notes)
        assertEquals(
            listOf("1 empty document dropped"),
            (PropertiesFormat.write(DataContent(listOf(tree("{}"))), OptionValues()) as WriteResult.Written).notes,
        )
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"a":{"b":[1,{"c":"x"},[2,3]]},"d.e":{"f g":"h"},"i":[{"j":[{"k":1}]}]}""",
            """{"s":" lead\nnext\t\\ \u0000 ü 😀 ","e":"","key=:#! x":"v","n":"0123"}""",
            """{"a.b":{"[x":1},"[":2,"":3}""",
        ],
    )
    fun `trees survive a round trip through properties`(json: String) {
        val original = tree(json)

        for (escape in listOf(true, false)) {
            val text = write(original, escapeNonAscii = escape)
            assertEquals(original, read(text).content.documents.single(), text)
        }
    }

    @Test
    fun `the Spring sample gives separated documents that read back the same`() {
        val yaml = checkNotNull(javaClass.getResource("spring-application.yml")).readText()
        val documents = (YamlFormat.read(yaml, OptionValues()) as ReadResult.Read).content.documents
        val written = PropertiesFormat.write(DataContent(documents), OptionValues()) as WriteResult.Written

        assertTrue(written.text.contains("\n#---\n"), written.text)
        assertTrue(written.text.contains("spring.datasource.hikari.maximum-pool-size=10\n"), written.text)
        assertTrue(written.text.contains("logging.level[com.example.orders]=DEBUG\n"), written.text)
        assertEquals(documents, read(written.text).content.documents)
    }

    @Test
    fun `properties of Spring Boot give the same YAML tree`() {
        val text = "server.port=8080\nspring.profiles[0]=a\nspring.profiles[1]=b\nlogging.level[com.x]=DEBUG\n"
        val documents = read(text).content.documents
        val yaml = (YamlFormat.write(DataContent(documents), OptionValues()) as WriteResult.Written).text

        assertEquals(documents, (YamlFormat.read(yaml, OptionValues()) as ReadResult.Read).content.documents)
        assertTrue(yaml.contains("com.x: DEBUG"), yaml)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "a=1¶b=2|60",
            "[a.b]=1¶c=2|60",
            "a = 1¶# c¶b.c[0]=x|60",
            "a=1¶#---¶b=2|60",
            "a=1¶b: 2|40",
            "a: 1|40",
            "a=1¶b c|",
            "{\"a\": 1|",
            "[\"a\": 1]|",
            "a=1¶\"b\"=2|",
            "a b|",
            "hello world|",
            "# only|",
            "[section]¶a=1|",
            "a=1¶a.b=2|",
            "- a¶- b|",
        ],
    )
    fun `detection needs a separator on every line`(
        text: String,
        score: Int?,
    ) {
        assertEquals(score, PropertiesFormat.detect(unescape(text)))
    }

    @Test
    fun `reading and writing the Spring dialect offers its options`() {
        assertEquals(listOf("inferTypes"), PropertiesFormat.readOptions.map { it.id })
        assertEquals(listOf("sortKeys", "escapeNonAscii"), PropertiesFormat.writeOptions.map { it.id })
    }

    /** Cells mark a line break as `¶` and a carriage return as `¦`, as backslash escapes are what is tested. */
    private fun unescape(text: String) = text.replace('¶', '\n').replace('¦', '\r')

    private fun read(text: String) = PropertiesFormat.read(text, OptionValues()) as ReadResult.Read

    private fun tree(json: String): JsonNode =
        (JsonFormat.read(json, OptionValues()) as ReadResult.Read).content.documents.single()

    private fun write(
        node: JsonNode,
        sortKeys: Boolean = false,
        escapeNonAscii: Boolean = true,
    ): String = writeAll(listOf(node), sortKeys, escapeNonAscii)

    private fun writeAll(
        nodes: List<JsonNode>,
        sortKeys: Boolean = false,
        escapeNonAscii: Boolean = true,
    ): String {
        val options = OptionValues(mapOf("sortKeys" to sortKeys, "escapeNonAscii" to escapeNonAscii))
        return (PropertiesFormat.write(DataContent(nodes), options) as WriteResult.Written).text
    }
}
