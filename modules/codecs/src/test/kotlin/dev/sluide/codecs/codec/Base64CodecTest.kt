package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class Base64CodecTest {
    private fun enc(
        codec: Base64Codec,
        bytes: ByteArray,
    ) = (codec.encode(bytes) as Decoded.Value).value

    private fun dec(
        codec: Base64Codec,
        text: String,
    ) = (codec.decode(text) as Decoded.Value).value

    @ParameterizedTest
    @CsvSource(
        "'',''",
        "f,Zg==",
        "fo,Zm8=",
        "foo,Zm9v",
        "foob,Zm9vYg==",
        "fooba,Zm9vYmE=",
        "foobar,Zm9vYmFy",
    )
    fun `RFC 4648 vectors both ways`(
        plain: String,
        encoded: String,
    ) {
        assertEquals(encoded, enc(Base64Codec(), plain.toByteArray()))
        assertEquals(plain, String(dec(Base64Codec(), encoded)))
    }

    @Test
    fun `URL-safe alphabet without padding`() {
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte())
        assertEquals("+/8=", enc(Base64Codec(), bytes))
        assertEquals("-_8", enc(Base64Codec(urlSafe = true, padding = false), bytes))
    }

    @Test
    fun `wrap76 breaks lines at 76 characters with LF`() {
        val text = enc(Base64Codec(wrap76 = true), ByteArray(100) { it.toByte() })
        val lines = text.split("\n")
        assertEquals(listOf(76, 60), lines.map { it.length })
    }

    @Test
    fun `data URI prefix is encoded and decoded and only the payload is wrapped`() {
        val bytes = ByteArray(100) { it.toByte() }
        val text = enc(Base64Codec(wrap76 = true, dataUriMime = "image/png"), bytes)
        assertEquals(true, text.startsWith("data:image/png;base64,"))
        assertEquals(2, text.removePrefix("data:image/png;base64,").split("\n").size)
        assertEquals(bytes.toList(), dec(Base64Codec(), text).toList())
        assertEquals("hi", String(dec(Base64Codec(), "DATA:text/plain;BASE64,aGk=")))
    }

    @Test
    fun `lenient decode ignores whitespace, mixes alphabets and accepts missing padding`() {
        assertEquals("foob", String(dec(Base64Codec(), " Zm9v\r\nYg ")))
        assertEquals(listOf(0xFB.toByte(), 0xFF.toByte()), dec(Base64Codec(), "-/8").toList())
        assertEquals(listOf(0xFB.toByte(), 0xFF.toByte()), dec(Base64Codec(), "+_8=").toList())
    }

    @Test
    fun `invalid character reports its position in the original input`() {
        assertEquals(Decoded.Error("Invalid Base64 character '!'", 4), Base64Codec().decode("Zm 9!"))
    }

    @Test
    fun `one leftover character is an error at that character`() {
        assertEquals(Decoded.Error("Base64 ends in the middle of a byte", 4), Base64Codec().decode("Zm9vY"))
    }

    @Test
    fun `data after padding is an error`() {
        assertEquals(Decoded.Error("Base64 has data after '='", 3), Base64Codec().decode("Zg=a"))
    }

    @Test
    fun `a data URI after leading whitespace is recognised and positions stay in the original`() {
        assertEquals("hi", String(dec(Base64Codec(), " \ndata:;base64,aGk=")))
        assertEquals(Decoded.Error("Invalid Base64 character '*'", 16), Base64Codec().decode(" \ndata:;base64,Z*"))
    }

    @Test
    fun `position counts the data URI prefix`() {
        val prefix = "data:;base64,"
        assertEquals(prefix.length + 1, (Base64Codec().decode("${prefix}Z*") as Decoded.Error).position)
    }

    @Test
    fun `DataUri parses mime and defaults it`() {
        assertEquals(DataUri.Parsed("text/plain", true, 13), DataUri.parse("data:;base64,aGk="))
        assertEquals(DataUri.Parsed("text/html", false, 15), DataUri.parse("data:text/html,<b>"))
        assertNull(DataUri.parse("hello"))
        assertNull(DataUri.parse("data:text/plain"))
    }
}
