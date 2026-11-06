package dev.sluide.terminal

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

private const val ESC = "\u001b"
private const val BEL = "\u0007"
private const val HELLO = "aGVsbG8="

class Osc52FilterTest {
    private val copied = mutableListOf<String>()

    /**
     * What JediTerm gets when the program's output arrives in [chunks]: [Osc52Filter.read] in a loop until the end, as
     * the connector does, so the output overwrites the input in the same buffer. A new filter each time.
     */
    private fun filtered(chunks: List<String>): String {
        val filter = Osc52Filter(copied::add)
        val remaining = ArrayDeque(chunks)
        val buf = CharArray(chunks.maxOf { it.length } + Osc52Filter.MAX_HELD + 3)
        val output = StringBuilder()
        while (true) {
            val count =
                filter.read(buf, 2, buf.size - 2) { chars, from, length ->
                    val chunk = remaining.removeFirstOrNull() ?: return@read -1
                    check(chunk.length <= length) { "chunk of ${chunk.length} for $length" }
                    chunk.toCharArray().copyInto(chars, from)
                    chunk.length
                }
            if (count < 0) return output.toString()
            check(count > 0) { "JediTerm takes 0 for the end" }
            output.appendRange(buf, 2, 2 + count)
        }
    }

    private fun filtered(output: String) = filtered(listOf(output))

    @ParameterizedTest
    @ValueSource(strings = [BEL, "$ESC\\", "\u009c"])
    fun `OSC 52 is taken out and its text copied`(terminator: String) {
        assertEquals("beforeafter", filtered("before$ESC]52;c;$HELLO${terminator}after"))
        assertEquals(listOf("hello"), copied)
    }

    @Test
    fun `other sequences pass unchanged and in order`() {
        val output =
            "a$ESC]0;title${BEL}b$ESC]520;x${BEL}c$ESC]12345;x${BEL}d" +
                "$ESC[31me$ESC[0m${ESC}7f${ESC}8$ESC${ESC}g"

        assertEquals(output, filtered(output))
        assertEquals(listOf(), copied)
    }

    @Test
    fun `an ESC inside the payload aborts it and is output with what follows`() {
        assertEquals("a$ESC[31mb", filtered("a$ESC]52;c;aGVs$ESC[31mb"))
        assertEquals("", filtered("$ESC]52;c;aGVs$ESC]52;c;$HELLO$BEL"))
        assertEquals(listOf("hello"), copied, "the second sequence copies")
    }

    @Test
    fun `an oversized payload is consumed without copying`() {
        val oversized = "$ESC]52;c;${"A".repeat(OSC_52_MAX_PAYLOAD + 100)}$BEL"

        assertEquals("ab", filtered("a${oversized}b$ESC]52;c;$HELLO$BEL"))
        assertEquals(listOf("hello"), copied, "only the next sequence copies")
    }

    @Test
    fun `long targets do not let a cut-off payload through`() {
        // 19 targets and a payload at the limit overflow the room by 4: what is kept would be valid base64.
        val payload = "QUFB".repeat(OSC_52_MAX_PAYLOAD / 4)
        val longTargets = "$ESC]52;${"c".repeat(19)};$payload$BEL"

        assertEquals("ab", filtered("a${longTargets}b"))
        assertEquals(listOf(), copied)
    }

    @Test
    fun `a query is not answered and copies nothing`() {
        assertEquals("ab", filtered("a$ESC]52;c;?${BEL}b"))
        assertEquals(listOf(), copied)
    }

    @Test
    fun `output split anywhere gives the same result`() {
        val output =
            "x$ESC]0;t${BEL}y$ESC]52;c;$HELLO${BEL}z$ESC]52;p;$HELLO$ESC\\$ESC[1m" +
                "$ESC]52;c;aGVs${ESC}7w$ESC]520;$BEL$ESC]5"
        val whole = filtered(output)
        val wholeCopies = copied.toList()
        assertEquals("x$ESC]0;t${BEL}yz$ESC[1m${ESC}7w$ESC]520;$BEL$ESC]5", whole)
        assertEquals(listOf("hello", "hello"), wholeCopies)

        for (split in 1 until output.length) {
            copied.clear()
            assertEquals(whole, filtered(listOf(output.take(split), output.drop(split))), "split at $split")
            assertEquals(wholeCopies, copied, "split at $split")
        }
        copied.clear()
        assertEquals(whole, filtered(output.map { it.toString() }), "one character per read")
        assertEquals(wholeCopies, copied, "one character per read")
    }

    @Test
    fun `a read of only a clipboard write is skipped, not taken for the end`() {
        assertEquals("ab", filtered(listOf("a", "$ESC]52;c;$HELLO$BEL", "b")))
        assertEquals(listOf("hello"), copied)
    }

    @ParameterizedTest
    @ValueSource(strings = [ESC, "$ESC]", "$ESC]5", "$ESC]52"])
    fun `a possible start held back at the end is output`(start: String) {
        assertEquals("a$start", filtered("a$start"))
        assertEquals(listOf(), copied)
    }

    @Test
    fun `a payload cut off by the end is dropped`() {
        assertEquals("a", filtered("a$ESC]52;c;$HELLO"))
        assertEquals(listOf(), copied)
    }

    @Test
    fun `flush returns the held-back start and leaves nothing behind`() {
        val filter = Osc52Filter(copied::add)
        val output = CharArray(16)
        val input = "a$ESC]5".toCharArray()

        assertEquals(1, filter.filter(input, 0, input.size, output, 0))
        assertEquals("$ESC]5", filter.flush().concatToString())
        assertEquals("", filter.flush().concatToString())
    }
}
