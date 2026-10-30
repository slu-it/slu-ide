package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class JsonStringTest {
    @Test
    fun `encodes quotes backslash and control characters but not the slash`() {
        assertEquals(
            Decoded.Value("\\\"q\\\" \\\\ a/b \\b\\f\\n\\r\\t \\u0001\\u001F"),
            JsonString().encode("\"q\" \\ a/b \b\u000C\n\r\t \u0001\u001F"),
        )
    }

    @Test
    fun `non-ASCII is written as UTF-16 units only with the option`() {
        assertEquals(Decoded.Value("é😀"), JsonString().encode("é😀"))
        assertEquals(Decoded.Value("\\u00E9\\uD83D\\uDE00"), JsonString(true).encode("é😀"))
    }

    @ParameterizedTest
    @CsvSource("false", "true")
    fun `a text with emoji and escapes survives a round trip`(escapeNonAscii: Boolean) {
        val text = "tab\t \"quoted\" \\ 😀 é \u0007"
        val codec = JsonString(escapeNonAscii)
        val encoded = (codec.encode(text) as Decoded.Value).value
        assertEquals(Decoded.Value(text), codec.decode(encoded))
    }

    @Test
    fun `decodes every escape kind`() {
        assertEquals(
            Decoded.Value("\" \\ / \b \u000C \n \r \t A"),
            JsonString().decode("\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u0041"),
        )
    }

    @Test
    fun `one pair of surrounding quotes is stripped`() {
        assertEquals(Decoded.Value("a\"b"), JsonString().decode("\"a\\\"b\""))
        assertEquals(Decoded.Value("\"\""), JsonString().decode("\"\"\"\""))
        assertEquals(Decoded.Value("\""), JsonString().decode("\""))
    }

    @ParameterizedTest
    @CsvSource("ab\\qc,2", "\\u12,0", "\\u12G4,0", "x\\,1")
    fun `an invalid escape is an error at the backslash`(
        text: String,
        position: Int,
    ) {
        assertEquals(position, (JsonString().decode(text) as Decoded.Error).position)
    }

    @Test
    fun `error positions count the stripped opening quote`() {
        assertEquals(Decoded.Error("Invalid escape '\\q'", 3), JsonString().decode("\"ab\\q\""))
    }

    @Test
    fun `non-ASCII digits are no hex digits in a unicode escape`() {
        assertEquals(
            Decoded.Error("Invalid unicode escape", 0),
            JsonString().decode("\\u\uFF10\uFF10\uFF14\uFF11"),
        )
    }
}
