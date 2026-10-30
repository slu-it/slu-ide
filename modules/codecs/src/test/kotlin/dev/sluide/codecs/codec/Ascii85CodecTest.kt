package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class Ascii85CodecTest {
    private val adobe = Ascii85Codec()
    private val z85 = Ascii85Codec(Ascii85Variant.Z85)

    @ParameterizedTest
    @CsvSource("'Man ',<~9jqo^~>", "sure.,<~F*2M7/c~>", "'',<~~>")
    fun `Adobe vectors both ways`(
        plain: String,
        encoded: String,
    ) {
        assertEquals(Decoded.Value(encoded), adobe.encode(plain.toByteArray()))
        assertEquals(plain, String((adobe.decode(encoded) as Decoded.Value).value))
    }

    @Test
    fun `Adobe zero group uses z only for full groups`() {
        assertEquals(Decoded.Value("<~z~>"), adobe.encode(ByteArray(4)))
        assertEquals(Decoded.Value("<~!!~>"), adobe.encode(ByteArray(1)))
        assertEquals(listOf<Byte>(0, 0, 0, 0), (adobe.decode("<~z~>") as Decoded.Value).value.toList())
    }

    @Test
    fun `Adobe decode without delimiters and with whitespace`() {
        assertEquals("Man ", String((adobe.decode(" 9jqo\n^ ") as Decoded.Value).value))
    }

    @Test
    fun `Adobe decode errors`() {
        assertEquals(Decoded.Error("Invalid Ascii85 character 'v'", 2), adobe.decode("<~v~>"))
        assertEquals(Decoded.Error("'z' inside an Ascii85 group", 3), adobe.decode("<~9z~>"))
        assertEquals(Decoded.Error("Ascii85 group is too large", 6), adobe.decode("<~uuuuu~>"))
        assertEquals(Decoded.Error("Ascii85 ends in the middle of a group", 7), adobe.decode("<~9jqo^9~>"))
    }

    @Test
    fun `Z85 spec vector both ways`() {
        val bytes = byteArrayOf(0x86.toByte(), 0x4F, 0xD2.toByte(), 0x6F, 0xB5.toByte(), 0x59, 0xF7.toByte(), 0x5B)
        assertEquals(Decoded.Value("HelloWorld"), z85.encode(bytes))
        assertEquals(bytes.toList(), (z85.decode("HelloWorld") as Decoded.Value).value.toList())
    }

    @Test
    fun `Z85 errors`() {
        assertEquals(Decoded.Error("Z85 needs a multiple of 4 bytes"), z85.encode(ByteArray(3)))
        assertEquals(Decoded.Error("Invalid Z85 character '~'", 2), z85.decode("He~lo"))
        assertEquals(Decoded.Error("Z85 needs a multiple of 5 characters", 3), z85.decode("Hell"))
    }
}
