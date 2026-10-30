package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LineByLineTest {
    private val upper: (String) -> Decoded<String> = { Decoded.Value(it.uppercase()) }

    @Test
    fun `keeps LF and CRLF separators`() {
        assertEquals(LinesResult("A\r\nB\nC", emptyList(), null), LineByLine.convert("a\r\nb\nc", upper))
        assertEquals("A\n", LineByLine.convert("a\n", upper).text)
    }

    @Test
    fun `empty lines stay empty and are not converted`() {
        val seen = mutableListOf<String>()
        val result =
            LineByLine.convert("a\n\r\n\nb") {
                seen.add(it)
                Decoded.Value(it.uppercase())
            }
        assertEquals("A\n\r\n\nB", result.text)
        assertEquals(listOf("a", "b"), seen)
        assertEquals("", LineByLine.convert("", upper).text)
    }

    @Test
    fun `failed lines keep their text and are numbered from 1`() {
        val result =
            LineByLine.convert("ok\nbad\r\nfine\nbad") {
                if (it == "bad") Decoded.Error("no", 1) else Decoded.Value(it.uppercase())
            }
        assertEquals("OK\nbad\r\nFINE\nbad", result.text)
        assertEquals(listOf(2, 4), result.failedLines)
    }

    @Test
    fun `first error position is translated to the whole text`() {
        val failCd: (String) -> Decoded<String> = { if (it == "cd") Decoded.Error("no", 1) else Decoded.Value(it) }
        val result = LineByLine.convert("ab\r\ncd\nef", failCd)
        assertEquals(Decoded.Error("no", 5), result.firstError)
    }
}
