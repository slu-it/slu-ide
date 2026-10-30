package dev.sluide.converting.data

import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.WriteResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class CsvFormatTest {
    private val mapper = JsonMapper.builder().build()

    private fun tree(json: String): JsonNode = mapper.readTree(json)

    private fun read(
        text: String,
        format: DataFormat = CsvFormat,
        options: OptionValues = OptionValues(),
    ): ReadResult.Read<DataContent> = format.read(text, options) as ReadResult.Read

    private fun json(read: ReadResult.Read<DataContent>): JsonNode = read.content.documents.single()

    private fun write(
        json: String,
        format: DataFormat = CsvFormat,
        options: OptionValues = OptionValues(),
    ): WriteResult = format.write(DataContent(listOf(tree(json))), options)

    private fun written(
        json: String,
        format: DataFormat = CsvFormat,
        options: OptionValues = OptionValues(),
    ): WriteResult.Written = write(json, format, options) as WriteResult.Written

    @Test
    fun `quotes, doubled quotes, delimiters and line breaks inside quotes, CRLF and a BOM are read`() {
        val text = "\uFEFFa,b\r\n\"x, \"\"y\"\"\",\"line1\nline2\"\r\n\r\n"

        val read = read(text, options = OptionValues().with("inferTypes", false))

        assertEquals(tree("""[{"a":"x, \"y\"","b":"line1\nline2"}]""").toString(), json(read).toString())
        assertTrue(read.notes.isEmpty())
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """a,b\n1,2|[{"a":1,"b":2}]""",
            """a;b\n1;2|[{"a":1,"b":2}]""",
            """a,b;c\n1,2;3|[{"a":1,"b;c":"2;3"}]""",
            """a,b;c\n1,2,3|[{"a":1,"b;c":2,"column3":3}]""",
            """a;b,c\n1;2,3|[{"a;b":"1;2","c":3}]""",
            """a;b\n1,5;2|[{"a":"1,5","b":2}]""",
        ],
    )
    fun `CSV guesses its delimiter`(
        text: String,
        expected: String,
    ) {
        assertEquals(tree(expected).toString(), json(read(text.replace("\\n", "\n"))).toString())
    }

    @Test
    fun `TSV always reads tabs and CSV with a tab-less text keeps commas`() {
        assertEquals(tree("""[{"a":1,"b,c":2}]""").toString(), json(read("a\tb,c\n1\t2", TsvFormat)).toString())
    }

    @Test
    fun `without a header rows are arrays and types are inferred`() {
        val read = read("1,x,\ntrue,2.5,\"\"", options = OptionValues().with("csvHeader", false))

        assertEquals(tree("""[[1,"x",null],[true,2.5,null]]""").toString(), json(read).toString())
    }

    @Test
    fun `without inferTypes every cell is a string`() {
        val read = read("a,b\n1,\n", options = OptionValues().with("inferTypes", false))

        assertEquals(tree("""[{"a":"1","b":""}]""").toString(), json(read).toString())
    }

    @Test
    fun `empty and duplicate header cells are renamed`() {
        val read = read("a,,a,a\n1,2,3,4")

        assertEquals(tree("""[{"a":1,"column2":2,"a_2":3,"a_3":4}]""").toString(), json(read).toString())
        assertEquals(listOf("2 duplicate column names renamed"), read.notes)
    }

    @Test
    fun `ragged rows get nulls and extra columns`() {
        val read = read("a,b,c\n1\n1,2,3,4\n1,2,3")

        assertEquals(
            tree("""[{"a":1,"b":null,"c":null},{"a":1,"b":2,"c":3,"column4":4},{"a":1,"b":2,"c":3}]"""),
            json(read),
        )
        assertEquals(listOf("2 rows don't match the header's 3 columns"), read.notes)
    }

    @Test
    fun `an unclosed quote fails at its opening quote`() {
        val failed = CsvFormat.read("a,b\n1,\"oops\n2,3", OptionValues()) as ReadResult.Failed

        assertEquals(6, failed.offset)
        assertEquals(6, (TsvFormat.read("a\tb\n1\t\"oops", OptionValues()) as ReadResult.Failed).offset)
        assertEquals(7, (CsvFormat.read("\uFEFFa,b\n1,\"oops", OptionValues()) as ReadResult.Failed).offset)
    }

    @Test
    fun `blank text reads as no documents`() {
        assertTrue(read("\n\n").content.documents.isEmpty())
    }

    @Test
    fun `the union of keys is the header, missing and null values are empty`() {
        val written = written("""[{"a":1,"b":null},{"c":true,"a":2.5}]""")

        assertEquals("a,b,c\n1,,\n2.5,,true\n", written.text)
        assertTrue(written.notes.isEmpty())
    }

    @Test
    fun `nested values are flattened into columns`() {
        val written = written("""[{"a":{"b":1,"c":[5,{"x":6}]},"d":2},{"a":{"b":3},"d":4}]""")

        assertEquals("a.b,a.c[0],a.c[1].x,d\n1,5,6,2\n3,,,4\n", written.text)
        assertEquals(listOf("Nested values written as 3 columns"), written.notes)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "[[1,\"a b\",null],[2,3]]|1,a b,\\n2,3\\n",
            "[1,\"x\",null,true]|value\\n1\\nx\\n\"\"\\ntrue\\n",
            "[{}]|-",
            "{\"a\":1,\"b\":\"x\"}|a,b\\n1,x\\n",
            "[]|-",
        ],
    )
    fun `other shapes of a table`(
        json: String,
        expected: String,
    ) {
        assertEquals(expected.replace("\\n", "\n").replace("-", ""), written(json).text)
    }

    @ParameterizedTest
    @ValueSource(strings = ["""[{"a":1},2]""", """[{"a":1},[1]]""", """[[1],2]""", "3", "\"x\"", "null"])
    fun `what is not a list of records fails`(json: String) {
        assertEquals(WriteResult.Failed("CSV needs a list of records"), write(json))
    }

    @Test
    fun `several documents fail and none write nothing`() {
        val three = DataContent(listOf(tree("[]"), tree("[]"), tree("[]")))

        assertEquals(WriteResult.Failed("CSV holds one table, not 3 documents"), CsvFormat.write(three, OptionValues()))
        assertEquals(WriteResult.Written("", emptyList()), TsvFormat.write(DataContent(emptyList()), OptionValues()))
    }

    @Test
    fun `fields are quoted when needed, with the delimiter of the format`() {
        val json = """[{"a":"x,y","b":"say \"hi\"","c":" pad","d":"l1\nl2","e":"t\tab","f":"plain"}]"""

        assertEquals(
            "a,b,c,d,e,f\n\"x,y\",\"say \"\"hi\"\"\",\" pad\",\"l1\nl2\",t\tab,plain\n",
            written(json).text,
        )
        assertEquals(
            "a\tb\tc\td\te\tf\nx,y\t\"say \"\"hi\"\"\"\t\" pad\"\t\"l1\nl2\"\t\"t\tab\"\tplain\n",
            written(json, TsvFormat).text,
        )
    }

    @Test
    fun `sortKeys sorts the columns and the semicolon is an option`() {
        val options = OptionValues().with("sortKeys", true).with("csvDelimiter", ";")

        assertEquals("a;B;c\n1;2;x\n", written("""[{"c":"x","B":2,"a":1}]""", options = options).text)
        assertEquals("a;b\n\"1;2\";3\n", written("""[{"a":"1;2","b":3}]""", options = options).text)
    }

    @Test
    fun `TSV offers no delimiter option`() {
        assertTrue(CsvFormat.writeOptions.contains(DataOptions.csvDelimiter))
        assertFalse(TsvFormat.writeOptions.contains(DataOptions.csvDelimiter))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """[1,"x",null,true]|5""",
            """[{"a":1},{"a":null},{"a":2}]|4""",
            """[[1],[]]|2""",
            """[[null]]|1""",
        ],
    )
    fun `rows with one empty cell survive a round trip`(
        json: String,
        records: Int,
    ) {
        val csv = written(json).text

        val rows = read(csv, options = OptionValues().with("csvHeader", false))

        assertEquals(records, json(rows).size())
    }

    @Test
    fun `colliding flattened names are made unique and noted`() {
        val written = written("""[{"a.b":1,"a":{"b":2}}]""")

        assertEquals("a.b,a.b_2\n1,2\n", written.text)
        assertEquals(listOf("Nested values written as 1 column", "1 column name made unique"), written.notes)
    }

    @Test
    fun `a flattened column means the same in every record`() {
        val written = written("""[{"a.b":1,"a":{"b":2}},{"a":{"b":3},"a.b":4}]""")

        assertEquals("a.b,a.b_2\n1,2\n4,3\n", written.text)
    }

    @Test
    fun `empty objects and lists are empty cells and noted`() {
        val written = written("""[{"a":{},"b":[],"c":1}]""")

        assertEquals("a,b,c\n,,1\n", written.text)
        assertEquals(listOf("2 empty objects or lists written as empty cells"), written.notes)
    }

    @Test
    fun `nested cells of arrays are written as JSON and noted`() {
        val written = written("""[[1,{"a":2}],[[3],4]]""")

        assertEquals("1,\"{\"\"a\"\":2}\"\n[3],4\n", written.text)
        assertEquals(listOf("2 nested values written as JSON"), written.notes)
    }

    @Test
    fun `a header alone is an empty list and a quote inside a field is text`() {
        assertEquals("[]", json(read("a,b\n")).toString())
        assertEquals("""[{"a":"x\"y","b":1}]""", json(read("a,b\nx\"y,1")).toString())
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "a,b\\n1,2|csv|70",
            "a;b\\n1;2|csv|70",
            "a\tb\\n1\t2|tsv|70",
            "a,b,c\\n1,2\\n3,4,5|csv|30",
        ],
    )
    fun `detection scores`(
        text: String,
        id: String,
        score: Int,
    ) {
        val format = if (id == "csv") CsvFormat else TsvFormat

        assertEquals(score, format.detect(text.replace("\\n", "\n")))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "a,b,c",
            "just text\nmore text",
            "{\n  \"a\": 1,\n  \"b\": 2\n}",
            "{\n  \"a\": 1,\n  \"b\": 2,\n  \"c\": 3\n}",
            "a: 1\nb: 2",
            "a: 1, b: 2\nc: 3, d: 4",
            "- a, b\n- c, d",
            "a=1,b=2\nc=3,d=4",
            "key=value\nother=thing",
            "a,\"b\nc",
            "[[1, 2],\n [3, 4]]",
            "list[0]=a,b\nlist[1]=c,d",
            "full name: Ann, Bob\nlast name: Cy, Di",
            "key: first, second\n  continued, here",
        ],
    )
    fun `other formats and single lines score at most 30 for CSV`(text: String) {
        assertTrue((CsvFormat.detect(text) ?: 0) <= 30, text)
        assertNull(TsvFormat.detect(text))
    }

    @Test
    fun `CSV round trips through JSON`() {
        val csv = "name,age,note\nAnn,30,\"hi, there\"\nBob,,x\n"
        val data = read(csv)

        assertEquals(
            tree("""[{"name":"Ann","age":30,"note":"hi, there"},{"name":"Bob","age":null,"note":"x"}]"""),
            json(data),
        )
        assertEquals(csv, (CsvFormat.write(data.content, OptionValues()) as WriteResult.Written).text)
    }

    @Test
    fun `JSON records round trip through CSV with inferred types`() {
        val source = """[{"a":1,"b":"x","c":true},{"a":2.5,"b":"y","c":false}]"""
        val csv = written(source, TsvFormat).text

        assertEquals(tree(source).toString(), json(read(csv, TsvFormat)).toString())
    }
}
