package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class UnicodeEscapesTest {
    @Test
    fun `backslash u writes surrogate pairs for supplementary characters`() {
        assertEquals(Decoded.Value("a\\u00E9\\uD83D\\uDE00"), UnicodeEscapes().encode("a\u00e9\uD83D\uDE00"))
    }

    @Test
    fun `braces write the whole code point without leading zeros`() {
        assertEquals(
            Decoded.Value("\\u{E9}\\u{1F600}"),
            UnicodeEscapes(UnicodeForm.BRACES).encode("\u00e9\uD83D\uDE00"),
        )
    }

    @Test
    fun `U plus pads to four digits and separates escapes only with all characters`() {
        assertEquals(Decoded.Value("U+00E9U+1F600"), UnicodeEscapes(UnicodeForm.U_PLUS).encode("\u00e9\uD83D\uDE00"))
        assertEquals(Decoded.Value("U+0048 U+0069"), UnicodeEscapes(UnicodeForm.U_PLUS, true).encode("Hi"))
        assertEquals(Decoded.Value("\\u0048\\u0069"), UnicodeEscapes(allCharacters = true).encode("Hi"))
    }

    @ParameterizedTest
    @EnumSource(UnicodeForm::class)
    fun `every form round trips with and without all characters`(form: UnicodeForm) {
        val text = "Hi \u00e9!\uD83D\uDE00 x"
        for (all in listOf(false, true)) {
            val codec = UnicodeEscapes(form, all)
            val encoded = (codec.encode(text) as Decoded.Value).value
            assertEquals(Decoded.Value(text), codec.decode(encoded))
        }
    }

    @Test
    fun `decodes all three forms mixed in one text`() {
        assertEquals(
            Decoded.Value("A\u00e9\uD83D\uDE00\uD83D\uDE00"),
            UnicodeEscapes().decode("\\u0041\\u{E9}U+1F600\\uD83D\\uDE00"),
        )
    }

    @Test
    fun `one space between two U plus escapes is dropped`() {
        assertEquals(Decoded.Value("Hi there"), UnicodeEscapes().decode("U+0048 U+0069 there"))
        assertEquals(Decoded.Value("H  i"), UnicodeEscapes().decode("U+0048  U+0069"))
    }

    @Test
    fun `a code point above 10FFFF is an error at the escape`() {
        assertEquals(Decoded.Error("Code point above U+10FFFF", 1), UnicodeEscapes().decode("a\\u{110000}"))
        assertEquals(Decoded.Error("Code point above U+10FFFF", 2), UnicodeEscapes().decode("aaU+FFFFFF"))
    }

    @Test
    fun `backslashes that start no escape stay unchanged`() {
        val text = "C:\\users\\u12 \\u{} \\u{zz} U+12 \\n"
        assertEquals(Decoded.Value(text), UnicodeEscapes().decode(text))
    }
}
