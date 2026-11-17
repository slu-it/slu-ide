package dev.sluide.fileviewers

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RevealAroundTest {
    /** Lines `l0` to `l20`, each three characters but the first ten. */
    private val text = (0..20).joinToString("\n") { "l$it" }

    private fun lineStart(line: Int) = text.indexOf("l$line")

    private fun lineEnd(line: Int) = lineStart(line) + "l$line".length

    @Test
    fun `the range holds the line with five lines above and below by default`() {
        assertEquals(lineStart(5) until lineEnd(15), revealAround(text, 10))
    }

    @ParameterizedTest
    @CsvSource(
        "0, 2, 0, 2", // the first line: nothing above
        "1, 2, 0, 3", // clipped above
        "20, 2, 18, 20", // the last line: nothing below
        "19, 2, 17, 20", // clipped below
        "10, 0, 10, 10", // no context: the line alone
    )
    fun `the context is clipped at both ends`(
        line: Int,
        context: Int,
        firstLine: Int,
        lastLine: Int,
    ) {
        assertEquals(lineStart(firstLine) until lineEnd(lastLine), revealAround(text, line, context))
    }

    @Test
    fun `a line past the end or before the start has no range`() {
        assertNull(revealAround(text, 21))
        assertNull(revealAround("one\ntwo", 5))
        assertNull(revealAround("one", -1))
    }

    @Test
    fun `an empty text has one empty line`() {
        assertEquals(0 until 0, revealAround("", 0))
        assertNull(revealAround("", 1))
    }

    @Test
    fun `a text ending with a line break has an empty last line`() {
        assertEquals(2 until 2, revealAround("a\n", 1, 0))
        assertEquals(0 until 2, revealAround("a\n", 1))
    }
}
