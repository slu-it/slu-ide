package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class Base32CodecTest {
    @ParameterizedTest
    @CsvSource(
        "'','',''",
        "f,MY======,CO======",
        "fo,MZXQ====,CPNG====",
        "foo,MZXW6===,CPNMU===",
        "foob,MZXW6YQ=,CPNMUOG=",
        "fooba,MZXW6YTB,CPNMUOJ1",
        "foobar,MZXW6YTBOI======,CPNMUOJ1E8======",
    )
    fun `RFC 4648 vectors both ways`(
        plain: String,
        base32: String,
        base32hex: String,
    ) {
        assertEquals(Decoded.Value(base32), Base32Codec().encode(plain.toByteArray()))
        assertEquals(Decoded.Value(base32hex), Base32Codec(hexAlphabet = true).encode(plain.toByteArray()))
        assertEquals(plain, String((Base32Codec().decode(base32) as Decoded.Value).value))
        assertEquals(plain, String((Base32Codec(hexAlphabet = true).decode(base32hex) as Decoded.Value).value))
    }

    @Test
    fun `no padding on encode, lenient decode`() {
        assertEquals(Decoded.Value("MZXW6"), Base32Codec(padding = false).encode("foo".toByteArray()))
        assertEquals("foo", String((Base32Codec().decode(" mzxw\n6 ") as Decoded.Value).value))
    }

    @Test
    fun `only ASCII letters are upper-cased`() {
        assertEquals(Decoded.Error("Invalid Base32 character '\u0131'", 0), Base32Codec().decode("\u0131".repeat(8)))
    }

    @Test
    fun `errors carry positions`() {
        assertEquals(Decoded.Error("Invalid Base32 character '1'", 3), Base32Codec().decode("MZX1"))
        assertEquals(Decoded.Error("Base32 ends in the middle of a byte", 2), Base32Codec().decode("MZX"))
        assertEquals(Decoded.Error("Base32 has data after '='", 3), Base32Codec().decode("MY=A"))
    }
}
