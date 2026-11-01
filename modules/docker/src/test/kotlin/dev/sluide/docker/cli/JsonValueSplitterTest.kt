package dev.sluide.docker.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonValueSplitterTest {
    private fun split(text: String): List<String> {
        val values = mutableListOf<String>()
        JsonValueSplitter { values += it }.feed(text)
        return values
    }

    @Test
    fun `one object per line, a pretty array, and text between them`() {
        assertEquals(
            listOf("""{"a":1}""", """{"b":2}""", "[\n {\"c\": [3]}\n]"),
            split("{\"a\":1}\n{\"b\":2}\nWARNING: noise\n[\n {\"c\": [3]}\n]\n"),
        )
    }

    @Test
    fun `braces, quotes and escapes inside strings do not count`() {
        assertEquals(listOf("""{"a":"}{[\"]\\"}"""), split("""{"a":"}{[\"]\\"}"""))
    }

    @Test
    fun `terminal escapes between and around values are dropped`() {
        assertEquals(
            listOf("""{"a":1}""", """{"b":2}"""),
            split("\u001b[H{\"a\":1}\u001b[K\n\u001b[K\n\u001b[J\u001b[H{\"b\":2}\u001b[K\n"),
        )
    }

    @Test
    fun `objects are found from the stream's arrays and lines, unparsable values skipped`() {
        assertEquals(
            listOf(1, 2, 3),
            jsonObjects("[{\"n\":1},{\"n\":2}]\n{\"n\":3}\n{broken}").map { it.get("n").asInt() },
        )
    }
}
