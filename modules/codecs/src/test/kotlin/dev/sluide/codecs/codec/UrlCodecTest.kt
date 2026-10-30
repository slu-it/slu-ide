package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class UrlCodecTest {
    @ParameterizedTest
    @CsvSource("COMPONENT,a%20b%26c%3D%C3%BC", "FORM,a+b%26c%3D%C3%BC")
    fun `encodes and decodes a text with space ampersand equals and umlaut`(
        mode: UrlMode,
        encoded: String,
    ) {
        val codec = UrlCodec(mode)
        assertEquals(Decoded.Value(encoded), codec.encode("a b&c=ü"))
        assertEquals(Decoded.Value("a b&c=ü"), codec.decode(encoded))
    }

    @Test
    fun `component keeps tilde and encodes star, form does the opposite`() {
        assertEquals(Decoded.Value("~%2A"), UrlCodec(UrlMode.COMPONENT).encode("~*"))
        assertEquals(Decoded.Value("%7E*"), UrlCodec(UrlMode.FORM).encode("~*"))
    }

    @Test
    fun `plus is a space only in form mode`() {
        assertEquals(Decoded.Value("a+b"), UrlCodec(UrlMode.COMPONENT).decode("a+b"))
        assertEquals(Decoded.Value("a b"), UrlCodec(UrlMode.FORM).decode("a+b"))
        assertEquals(Decoded.Value("%2B"), UrlCodec(UrlMode.FORM).encode("+"))
    }

    @Test
    fun `supplementary characters are encoded as one UTF-8 sequence`() {
        assertEquals(Decoded.Value("%F0%9F%98%80"), UrlCodec().encode("😀"))
        assertEquals(Decoded.Value("😀"), UrlCodec().decode("%F0%9F%98%80"))
    }

    @Test
    fun `ISO-8859-1 uses one byte and rejects characters it cannot hold`() {
        val codec = UrlCodec(charset = Charsets.ISO_8859_1)
        assertEquals(Decoded.Value("%FC"), codec.encode("ü"))
        assertEquals(Decoded.Value("ü"), codec.decode("%FC"))
        assertEquals(Decoded.Error("'€' cannot be written in ISO-8859-1", 2), codec.encode("ab€"))
    }

    @ParameterizedTest
    @CsvSource("%4,0", "ab%zz,2", "abc%,3", "%41%4,3")
    fun `a malformed percent escape is an error at the percent sign`(
        text: String,
        position: Int,
    ) {
        assertEquals(Decoded.Error("Invalid percent escape", position), UrlCodec().decode(text))
    }

    @Test
    fun `bytes invalid in the charset are an error at the first percent of the run`() {
        assertEquals(Decoded.Error("Bytes are not valid UTF-8", 1), UrlCodec().decode("a%C3%28"))
    }

    @Test
    fun `UTF-16 is written big-endian without a byte order mark per character`() {
        val codec = UrlCodec(charset = Charsets.UTF_16)
        assertEquals(Decoded.Value("%00%E9%00%E9"), codec.encode("\u00e9\u00e9"))
        assertEquals(Decoded.Value("\u00e9\u00e9"), codec.decode("%00%E9%00%E9"))
    }

    @Test
    fun `non-ASCII digits are no hex digits in a percent escape`() {
        assertEquals(Decoded.Error("Invalid percent escape", 0), UrlCodec().decode("%\u0664\u0661"))
    }
}
