package dev.sluide.codecs.conversion

import dev.sluide.codecs.codec.Ascii85Codec
import dev.sluide.codecs.codec.Ascii85Variant
import dev.sluide.codecs.codec.Base32Codec
import dev.sluide.codecs.codec.Base58Codec
import dev.sluide.codecs.codec.Base64Codec
import dev.sluide.codecs.codec.CodecId
import dev.sluide.codecs.codec.Decoded
import dev.sluide.codecs.codec.HexCodec
import dev.sluide.codecs.codec.HexSeparator
import dev.sluide.codecs.codec.HtmlEntities
import dev.sluide.codecs.codec.JavaString
import dev.sluide.codecs.codec.JsonString
import dev.sluide.codecs.codec.UnicodeEscapes
import dev.sluide.codecs.codec.UnicodeForm
import dev.sluide.codecs.codec.UrlCodec
import dev.sluide.codecs.codec.UrlMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class CodecSettingsTest {
    private fun encoded(
        settings: CodecSettings,
        bytes: ByteArray = "hello world, hello world, hello world, hello world, hello world, hello world".toByteArray(),
    ) = ((settings.byteCodec(null))?.encode(bytes) as Decoded.Value).value

    @Test
    fun `defaults`() {
        val s = CodecSettings()
        assertEquals(CodecId.BASE64, s.codec)
        assertEquals(Charsets.UTF_8, s.charset)
        assertFalse(s.lineByLine)
        assertEquals(Base64Options(urlSafe = false, padding = true, wrap76 = false, dataUri = false), s.base64)
        assertEquals(Ascii85Variant.ADOBE, s.ascii85)
        assertEquals(UrlMode.COMPONENT, s.url)
    }

    @ParameterizedTest
    @EnumSource(CodecId::class)
    fun `each codec builds exactly one kind of codec, or none for jwt and hash`(id: CodecId) {
        val s = CodecSettings(codec = id)
        val byte = s.byteCodec(null)
        val text = s.textCodec()
        when (id) {
            CodecId.BASE64 -> assertInstanceOf(Base64Codec::class.java, byte)
            CodecId.BASE32 -> assertInstanceOf(Base32Codec::class.java, byte)
            CodecId.BASE58 -> assertInstanceOf(Base58Codec::class.java, byte)
            CodecId.ASCII85 -> assertInstanceOf(Ascii85Codec::class.java, byte)
            CodecId.HEX -> assertInstanceOf(HexCodec::class.java, byte)
            CodecId.URL -> assertInstanceOf(UrlCodec::class.java, text)
            CodecId.HTML -> assertInstanceOf(HtmlEntities::class.java, text)
            CodecId.JSON -> assertInstanceOf(JsonString::class.java, text)
            CodecId.JAVA -> assertInstanceOf(JavaString::class.java, text)
            CodecId.UNICODE -> assertInstanceOf(UnicodeEscapes::class.java, text)
            CodecId.JWT, CodecId.HASH -> Unit
        }
        assertEquals(
            id in setOf(CodecId.BASE64, CodecId.BASE32, CodecId.BASE58, CodecId.ASCII85, CodecId.HEX),
            byte != null,
        )
        assertEquals(
            id in setOf(CodecId.URL, CodecId.HTML, CodecId.JSON, CodecId.JAVA, CodecId.UNICODE),
            text != null,
        )
    }

    @Test
    fun `options reach the codecs`() {
        assertEquals("6869", encoded(CodecSettings(codec = CodecId.HEX), "hi".toByteArray()))
        assertEquals(
            "68:69".uppercase(),
            encoded(
                CodecSettings(codec = CodecId.HEX, hex = HexOptions(true, HexSeparator.COLON)),
                "hi".toByteArray(),
            ),
        )
        assertEquals("aGk", encoded(CodecSettings(base64 = Base64Options(padding = false)), "hi".toByteArray()))
        assertEquals(
            "_-8",
            encoded(CodecSettings(base64 = Base64Options(urlSafe = true, padding = false)), byteArrayOf(-1, -17)),
        )
        assertEquals(
            "data:text/plain;base64,aGk=",
            (
                CodecSettings(
                    base64 = Base64Options(dataUri = true),
                ).byteCodec("text/plain")?.encode("hi".toByteArray()) as Decoded.Value
            ).value,
        )
        val unicode = CodecSettings(codec = CodecId.UNICODE, unicode = UnicodeOptions(UnicodeForm.U_PLUS, true))
        assertEquals("U+0068", (unicode.textCodec()?.encode("h") as Decoded.Value).value)
    }

    @Test
    fun `the data URI mime is ignored without the option`() {
        assertEquals(
            "aGk=",
            (CodecSettings().byteCodec("text/plain")?.encode("hi".toByteArray()) as Decoded.Value).value,
        )
    }

    @Test
    fun `wrap76 wraps, but is ignored in line mode`() {
        val wrapped = CodecSettings(base64 = Base64Options(wrap76 = true))
        assertTrue(encoded(wrapped).contains("\n"))
        assertFalse(encoded(wrapped.copy(lineByLine = true)).contains("\n"))
    }

    @Test
    fun `which controls apply to which codec`() {
        val byte = CodecSettings(codec = CodecId.BASE32)
        assertTrue(byte.lineByLineApplies && byte.charsetApplies && byte.compressionApplies)
        val url = CodecSettings(codec = CodecId.URL)
        assertTrue(url.lineByLineApplies && url.charsetApplies)
        assertFalse(url.compressionApplies)
        val json = CodecSettings(codec = CodecId.JSON)
        assertTrue(json.lineByLineApplies)
        assertFalse(json.charsetApplies || json.compressionApplies)
        val hash = CodecSettings(codec = CodecId.HASH)
        assertTrue(hash.charsetApplies)
        assertFalse(hash.lineByLineApplies || hash.compressionApplies)
        val jwt = CodecSettings(codec = CodecId.JWT)
        assertFalse(jwt.lineByLineApplies || jwt.charsetApplies || jwt.compressionApplies)
        assertNull(jwt.byteCodec(null))
    }
}
