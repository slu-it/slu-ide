package dev.sluide.codecs.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Base58CodecTest {
    private val codec = Base58Codec()

    @Test
    fun `text vector both ways`() {
        assertEquals(Decoded.Value("2NEpo7TZRRrLZSi2U"), codec.encode("Hello World!".toByteArray()))
        assertEquals("Hello World!", String((codec.decode("2NEpo7TZRRrLZSi2U") as Decoded.Value).value))
    }

    @Test
    fun `leading zero bytes become ones`() {
        val bytes = byteArrayOf(0, 0, 0x28, 0x7f, 0xb4.toByte(), 0xcd.toByte())
        assertEquals(Decoded.Value("11233QC4"), codec.encode(bytes))
        assertEquals(bytes.toList(), (codec.decode("11233QC4") as Decoded.Value).value.toList())
        assertEquals(listOf<Byte>(0, 0), (codec.decode("11") as Decoded.Value).value.toList())
    }

    @Test
    fun `empty is empty`() {
        assertEquals(Decoded.Value(""), codec.encode(ByteArray(0)))
        assertEquals(0, (codec.decode("  ") as Decoded.Value).value.size)
    }

    @Test
    fun `whitespace anywhere is ignored and positions point into the original`() {
        assertEquals("Hello World!", String((codec.decode(" 2NEpo\n7TZ RRrLZSi2U ") as Decoded.Value).value))
        assertEquals(Decoded.Error("Invalid Base58 character '0'", 6), codec.decode("1 2\n3 0"))
    }

    @Test
    fun `inputs over the limits are refused`() {
        assertEquals(true, codec.encode(ByteArray(1024)) is Decoded.Value)
        assertEquals(Decoded.Error("Base58 is limited to 1 024 bytes"), codec.encode(ByteArray(1025)))
        assertEquals(true, codec.decode("2".repeat(1400)) is Decoded.Value)
        assertEquals(Decoded.Error("Base58 is limited to 1 400 characters"), codec.decode("2".repeat(1401)))
        assertEquals(Decoded.Error("Base58 is limited to 1 400 characters"), codec.decode("2 ".repeat(1401)))
    }

    @Test
    fun `invalid character reports its position after trimming`() {
        assertEquals(Decoded.Error("Invalid Base58 character '0'", 3), codec.decode("  A0 "))
        assertEquals(Decoded.Error("Invalid Base58 character 'l'", 1), codec.decode("1l"))
    }
}
