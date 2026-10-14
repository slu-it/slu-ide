package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertEquals

class LineEndingTest {
    @Test
    fun `detects the ending of a file`() {
        assertEquals(LineEnding.LF, LineEnding.of("a\nb\n"))
        assertEquals(LineEnding.CRLF, LineEnding.of("a\r\nb\r\n"))
        assertEquals(LineEnding.CR, LineEnding.of("a\rb\r"))
    }

    @Test
    fun `takes LF for texts without line breaks`() {
        assertEquals(LineEnding.LF, LineEnding.of(""))
        assertEquals(LineEnding.LF, LineEnding.of("one line"))
    }

    @Test
    fun `takes the ending of most lines in mixed files and LF on ties`() {
        assertEquals(LineEnding.CRLF, LineEnding.of("a\r\nb\r\nc\n"))
        assertEquals(LineEnding.LF, LineEnding.of("a\nb\nc\r\n"))
        assertEquals(LineEnding.LF, LineEnding.of("a\r\nb\n"))
        assertEquals(LineEnding.CR, LineEnding.of("a\rb\rc\n"))
    }

    @Test
    fun `normalizes all endings to LF`() {
        assertEquals("a\nb\nc\nd", LineEnding.normalize("a\r\nb\rc\nd"))
    }

    @Test
    fun `restores the ending of a normalized text`() {
        val text = "a\r\n\r\nb\r\n"
        assertEquals(text, LineEnding.CRLF.restore(LineEnding.normalize(text)))
        assertEquals("a\rb", LineEnding.CR.restore("a\nb"))
        assertEquals("a\nb", LineEnding.LF.restore("a\nb"))
    }
}
