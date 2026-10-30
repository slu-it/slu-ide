package dev.sluide.codecs.binary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.HexFormat

class HexDumpTest {
    @Test
    fun `a full line has the offset, two groups and the ASCII column`() {
        val bytes = HexFormat.of().parseHex("89504e470d0a1a0a0000000d49484452")
        assertEquals(
            "00000000  89 50 4e 47 0d 0a 1a 0a  00 00 00 0d 49 48 44 52  |.PNG........IHDR|",
            HexDump.format(bytes),
        )
    }

    @Test
    fun `the partial last line is padded so the ASCII column aligns`() {
        val lines = HexDump.format(ByteArray(20) { ('A'.code + it).toByte() }).split("\n")
        assertEquals("00000000  41 42 43 44 45 46 47 48  49 4a 4b 4c 4d 4e 4f 50  |ABCDEFGHIJKLMNOP|", lines[0])
        assertEquals("00000010  51 52 53 54                                       |QRST|", lines[1])
        assertEquals(lines[0].indexOf('|'), lines[1].indexOf('|'))
    }

    @Test
    fun `a last line of at most eight bytes has no second group`() {
        assertEquals(
            "00000000  68 69                                             |hi|",
            HexDump.format("hi".toByteArray()),
        )
    }

    @Test
    fun `non printable bytes show as dots and offsets are lowercase hex`() {
        val lines = HexDump.format(ByteArray(0x110) { 0x7f }).split("\n")
        assertEquals(17, lines.size)
        assertTrue(lines[16].startsWith("00000100  7f"))
        assertTrue(lines[0].endsWith("|................|"))
    }

    @Test
    fun `a dump over the limit ends with the number of bytes left out`() {
        val lines = HexDump.format(ByteArray(12_345 + 32), limit = 32).split("\n")
        assertEquals(3, lines.size)
        assertEquals("… 12 345 more bytes", lines[2])
    }

    @Test
    fun `empty input gives an empty dump`() {
        assertEquals("", HexDump.format(ByteArray(0)))
    }
}
