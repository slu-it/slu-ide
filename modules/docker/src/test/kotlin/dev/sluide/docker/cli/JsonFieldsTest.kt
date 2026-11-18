package dev.sluide.docker.cli

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.JsonNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonFieldsTest {
    private fun json(text: String): JsonNode = dockerJson.readTree(text)

    @Test
    fun `field finds the first name that is there, an exact spelling before a loose one`() {
        val node = json("""{"Id":"a","ID":"b","other":1}""")

        assertEquals("b", node.field("ID")?.asString(), "the exact spelling wins over Id, which comes first")
        assertEquals("a", node.field("id")?.asString(), "no exact spelling: the first that matches without case")
        assertEquals(1, node.field("missing", "OTHER")?.asInt(), "names are tried in order")
        assertEquals("b", node.field("ID", "other")?.asString(), "an earlier name beats a later one")
        assertNull(node.field("missing"))
        assertNull(node.field())
    }

    @Test
    fun `a JSON null counts as missing and ends the search, exact or loose`() {
        assertNull(json("""{"a":null,"A":"x"}""").field("a"), "the exact null is the answer, not the loose A")
        assertNull(json("""{"A":null}""").field("a"))
        assertNull(json("""{"a":null,"b":1}""").field("a", "b"), "the first name that is there ends the search")
        assertEquals(1, json("""{"b":1}""").field("a", "b")?.asInt())
    }

    @Test
    fun `text is the scalar as text, nothing for blanks, objects and arrays`() {
        val node = json("""{"s":"x","n":5,"b":true,"blank":" ","empty":"","o":{"a":1},"a":["x"],"z":null}""")

        assertEquals("x", node.text("s"))
        assertEquals("5", node.text("n"))
        assertEquals("true", node.text("b"))
        for (name in listOf("blank", "empty", "o", "a", "z", "missing")) assertNull(node.text(name), name)
        assertEquals("x", json("""{"first":" ","second":"x"}""").text("second"))
    }

    @Test
    fun `long is a number or a number written as text, nothing else`() {
        val node =
            json(
                """{"n":5,"f":2.0,"g":2.5,"s":" 7 ","bad":"x","t":true,"o":{"a":1},"a":[1],"big":"9999999999999999999","huge":1e30,"digits":123456789012345678901234567890}""",
            )

        assertEquals(5L, node.long("n"))
        assertEquals(2L, node.long("f"), "a JSON number with a fraction part is still a number")
        assertEquals(2L, node.long("g"), "a fraction is cut off")
        assertEquals(7L, node.long("s"))
        for (name in listOf("bad", "t", "o", "a", "big", "huge", "digits", "missing")) assertNull(node.long(name), name)
    }

    @ParameterizedTest
    @CsvSource(
        "true, true",
        "false, false",
        "'\"true\"', true",
        "'\" False \"', false",
        "'\"TRUE\"', true",
        "'\"yes\"', ",
        "'\"1\"', ",
        "1, ",
        "'{}', ",
        "'[true]', ",
        "null, ",
    )
    fun `bool is a truth value or one written as text, nothing else`(
        value: String,
        expected: Boolean?,
    ) {
        assertEquals(expected, json("""{"b":$value}""").bool("b"))
    }

    @Test
    fun `labels from an object keep every key, a value that is no text as its JSON`() {
        val labels = json("""{"L":{"a":"1","b":2,"c":true,"d":{"x":[1]},"e":""}}""").labels("L")

        assertEquals(mapOf("a" to "1", "b" to "2", "c" to "true", "d" to """{"x":[1]}""", "e" to ""), labels)
    }

    @ParameterizedTest
    @CsvSource(
        "'', ''",
        "'   ', ''",
        "'a=1', 'a=1'",
        "'a=1,b=2', 'a=1;b=2'",
        "'a=1,x,y,b=2', 'a=1,x,y;b=2'",
        "'flag,a=1', 'flag=;a=1'",
        "'flag,other', 'flag=,other'",
        "'a=1,=x', 'a=1;=x'",
        "'=v', '=v'",
        "'k=a=b', 'k=a=b'",
    )
    fun `Docker's label text is key=value pairs, a part without = continues the value before it`(
        text: String,
        expected: String,
    ) {
        val labels = json("""{"Labels":"$text"}""").labels("Labels")

        val shown = labels.entries.joinToString(";") { "${it.key}=${it.value}" }
        assertEquals(expected, shown)
    }

    @Test
    fun `labels are empty without the field, with a JSON null or an array`() {
        assertEquals(emptyMap(), json("{}").labels("Labels"))
        assertEquals(emptyMap(), json("""{"Labels":null}""").labels("Labels"))
        assertEquals(emptyMap(), json("""{"Labels":["a=1"]}""").labels("Labels"))
    }

    @Test
    fun `texts are an array's scalars or a comma separated text, trimmed, blanks dropped`() {
        val node =
            json(
                """{"a":[" x ","","  ",1,{"o":1},["n"],"y"],"t":"p, q ,,r","e":"","n":5,"o":{"a":"b"}}""",
            )

        assertEquals(listOf("x", "1", "y"), node.texts("a"))
        assertEquals(listOf("p", "q", "r"), node.texts("t"))
        assertEquals(listOf("5"), node.texts("n"))
        assertEquals(emptyList(), node.texts("e"))
        assertEquals(emptyList(), node.texts("o"), "an object is no list of texts")
        assertEquals(emptyList(), node.texts("missing"))
        assertEquals(listOf("x", "1", "y"), node.texts("missing", "a"))
    }

    @Test
    fun `a command is an array of texts or a single text, nothing for a blank one`() {
        val node = json("""{"a":["sh","-c",1,{"o":1}],"t":"nginx -g","blank":"  ","empty":[],"o":{"a":1},"n":null}""")

        assertEquals(listOf("sh", "-c", "1"), node.command("a"))
        assertEquals(listOf("nginx -g"), node.command("t"))
        assertEquals(emptyList(), node.command("blank"))
        assertEquals(emptyList(), node.command("empty"))
        assertEquals(emptyList(), node.command("o"))
        assertEquals(emptyList(), node.command("n"))
        assertEquals(emptyList(), node.command("missing"))
    }

    @Test
    fun `scalarText is the text of a scalar, nothing for objects and arrays`() {
        val node = json("""{"s":"x","n":1.5,"o":{},"a":[]}""")

        assertEquals(listOf("x", "1.5", null, null), listOf("s", "n", "o", "a").map { node.get(it).scalarText() })
    }
}
