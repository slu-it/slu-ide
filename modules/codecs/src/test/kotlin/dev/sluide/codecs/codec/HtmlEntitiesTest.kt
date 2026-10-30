package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HtmlEntitiesTest {
    private fun decode(text: String) = (HtmlEntities().decode(text) as Decoded.Value).value

    @Test
    fun `markup characters round trip`() {
        val text = "<a href=\"x\">'&'</a>"
        val encoded = HtmlEntities().encode(text)
        assertEquals(Decoded.Value("&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;"), encoded)
        assertEquals(Decoded.Value(text), HtmlEntities().decode((encoded as Decoded.Value).value))
    }

    @Test
    fun `non-ASCII becomes whole code point references only with the option`() {
        assertEquals(Decoded.Value("é😀"), HtmlEntities().encode("é😀"))
        val encoded = HtmlEntities(true).encode("é😀")
        assertEquals(Decoded.Value("&#xE9;&#x1F600;"), encoded)
        assertEquals("é😀", decode((encoded as Decoded.Value).value))
    }

    @ParameterizedTest
    @CsvSource("&nbsp;, ", "&amp;,&", "&#65;,A", "&#x41;,A", "&#X41;,A", "&#x1F600;,😀")
    fun `named and numeric references are decoded`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, decode(text))
    }

    @Test
    fun `a named reference of two code points is decoded`() {
        assertEquals("≂̸", decode("&NotEqualTilde;"))
    }

    @Test
    fun `a legacy reference without semicolon is decoded`() {
        assertEquals("a & b", decode("a &amp b"))
    }

    @Test
    fun `an unknown named reference stays unchanged`() {
        assertEquals("&foo; &bar", decode("&foo; &bar"))
    }

    @Test
    fun `an out-of-range numeric reference is decoded as jsoup does`() {
        assertEquals("�", decode("&#x110000;"))
    }

    @Test
    fun `C1 controls stay literal with the option because HTML reads their references as windows-1252`() {
        assertEquals(Decoded.Value("\u0080&#xA0;"), HtmlEntities(true).encode("\u0080\u00a0"))
    }
}
