package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class JavaStringTest {
    @Test
    fun `encodes the short escapes and other control characters`() {
        assertEquals(
            Decoded.Value("\\\\ \\\" \\t\\b\\n\\r\\f \\u0001"),
            JavaString().encode("\\ \" \t\b\n\r\u000C \u0001"),
        )
    }

    @Test
    fun `the options escape non-ASCII and the dollar sign`() {
        assertEquals(Decoded.Value("\$x \u00e9"), JavaString().encode("\$x \u00e9"))
        assertEquals(Decoded.Value("\\\$x \\u00E9"), JavaString(true, true).encode("\$x \u00e9"))
    }

    @ParameterizedTest
    @CsvSource("false,false", "true,true")
    fun `a text with emoji survives a round trip`(
        nonAscii: Boolean,
        dollar: Boolean,
    ) {
        val text = "a\tb \"c\" \\ \$d \uD83D\uDE00 \u00e9"
        val codec = JavaString(nonAscii, dollar)
        val encoded = (codec.encode(text) as Decoded.Value).value
        assertEquals(Decoded.Value(text), codec.decode(encoded))
    }

    @Test
    fun `decodes every escape kind and strips surrounding quotes`() {
        assertEquals(
            Decoded.Value("\t\b\n\r\u000C ' \" \\ \$ A"),
            JavaString().decode("\"\\t\\b\\n\\r\\f\\s\\'\\s\\\"\\s\\\\\\s\\\$\\s\\u0041\""),
        )
    }

    @ParameterizedTest
    @CsvSource("\\uuuu0041,A", "\\101,A", "\\377,\u00ff")
    fun `decodes unicode with several u and octal escapes`(
        text: String,
        expected: String,
    ) {
        assertEquals(Decoded.Value(expected), JavaString().decode(text))
    }

    @Test
    fun `octal escapes follow the Java length rules`() {
        assertEquals(Decoded.Value("\u0000"), JavaString().decode("\\0"))
        assertEquals(Decoded.Value("'7"), JavaString().decode("\\477"))
        assertEquals(Decoded.Value("\u00018"), JavaString().decode("\\18"))
    }

    @ParameterizedTest
    @CsvSource("ab\\qc,2", "\\u00,0", "a\\8,1")
    fun `an invalid escape is an error at the backslash`(
        text: String,
        position: Int,
    ) {
        assertEquals(position, (JavaString().decode(text) as Decoded.Error).position)
    }

    @Test
    fun `Kotlin mode writes form feed as a unicode escape because Kotlin has no f escape`() {
        assertEquals(Decoded.Value("\\f"), JavaString().encode("\u000C"))
        assertEquals(Decoded.Value("\\u000C"), JavaString(kotlinDollar = true).encode("\u000C"))
    }
}
