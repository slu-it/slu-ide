package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class HexCodecTest {
    @ParameterizedTest
    @CsvSource(
        "'',''",
        "f,66",
        "fo,666f",
        "foo,666f6f",
        "foob,666f6f62",
        "fooba,666f6f6261",
        "foobar,666f6f626172",
    )
    fun `RFC 4648 vectors both ways`(
        plain: String,
        hex: String,
    ) {
        assertEquals(Decoded.Value(hex), HexCodec().encode(plain.toByteArray()))
        assertEquals(plain, String((HexCodec().decode(hex) as Decoded.Value).value))
    }

    @Test
    fun `upper case and separators`() {
        val bytes = byteArrayOf(0xAB.toByte(), 0x01)
        assertEquals(Decoded.Value("AB01"), HexCodec(upperCase = true).encode(bytes))
        assertEquals(Decoded.Value("ab 01"), HexCodec(separator = HexSeparator.SPACE).encode(bytes))
        assertEquals(Decoded.Value("AB:01"), HexCodec(true, HexSeparator.COLON).encode(bytes))
    }

    @ParameterizedTest
    @ValueSource(strings = ["48 69", "48:69", "48-69", "48,69", "0x4869", "0x48 0x69", "0X48, 0X69"])
    fun `decode ignores separators and 0x prefixes`(text: String) {
        assertEquals("Hi".toByteArray().toList(), (HexCodec().decode(text) as Decoded.Value).value.toList())
    }

    @ParameterizedTest
    @CsvSource("'0x1 0x2',3", "'a:b:c:d',1", "a0xb,1", "'1 2',1")
    fun `a separator or prefix after one digit is an error at that position`(
        text: String,
        position: Int,
    ) {
        assertEquals(Decoded.Error("Hex ends in the middle of a byte", position), HexCodec().decode(text))
    }

    @Test
    fun `errors carry positions`() {
        assertEquals(Decoded.Error("Invalid hex character 'g'", 3), HexCodec().decode("48 g9"))
        assertEquals(Decoded.Error("Hex ends in the middle of a byte", 6), HexCodec().decode("48 69 6"))
    }
}
