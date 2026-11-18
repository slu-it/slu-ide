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

    @Test
    fun `escapes inside a value, outside its strings, are dropped too, with their parameters and final byte`() {
        assertEquals(
            listOf("""{"a":1}""", """{"b":2}"""),
            split("{\"a\":\u001b[K1}\u001b[1;31m{\"b\":\u001b[2J2\u001b[0m}"),
        )
        // The final byte of a sequence is any of '@' to '~', the ends included.
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), split("\u001b[@{\"a\":1}\u001b[3~{\"b\":2}"))
    }

    @Test
    fun `DEL is no final byte of a sequence, so the sequence runs on into the next character`() {
        // The final byte is 0x40 to 0x7E; the brace (0x7B) here ends the sequence and is not read as a value start.
        assertEquals(emptyList(), split("\u001b[\u007f{\"a\":1}"))
        assertEquals(listOf("""{"b":2}"""), split("\u001b[\u007fm{\"b\":2}"))
    }

    @Test
    fun `an escape that is not a bracket sequence takes one character, which is not a value start`() {
        assertEquals(listOf("""{"a":1}"""), split("\u001b={\"a\":1}"))
        assertEquals(listOf("""{"a":1}"""), split("\u001b{{\"a\":1}"))
    }

    @Test
    fun `a raw escape character inside a string belongs to the string`() {
        // Hostile: real JSON writers escape it as \u001b, but a label may carry anything the CLI did not escape.
        assertEquals(listOf("{\"a\":\"x\u001b[Hy\"}"), split("{\"a\":\"x\u001b[Hy\"}"))
    }

    @Test
    fun `a value longer than the limit is dropped, the one at the limit is kept, what follows is read`() {
        val values = mutableListOf<String>()
        val splitter = JsonValueSplitter(maxValueChars = 10) { values += it }

        splitter.feed("[" + " ".repeat(9) + "]")
        splitter.feed("[" + " ".repeat(10) + "]")
        splitter.feed("{\"b\":1}")

        assertEquals(listOf("[" + " ".repeat(9) + "]", """{"b":1}"""), values)
    }
}
