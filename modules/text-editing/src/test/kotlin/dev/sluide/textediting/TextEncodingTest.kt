package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextEncodingTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `decodes valid UTF-8 strictly`() {
        assertEquals(DecodedText("ä€", TextEncoding.UTF_8), decodeText("ä€".toByteArray()))
        assertEquals(DecodedText("", TextEncoding.UTF_8), decodeText(ByteArray(0)))
    }

    @Test
    fun `decodes what is not UTF-8 as Windows-1252`() {
        // "Grüße €" with ü, ß and € (0x80) of Windows-1252.
        val bytes = bytes(0x47, 0x72, 0xFC, 0xDF, 0x65, 0x20, 0x80)
        assertEquals(DecodedText("Grüße €", TextEncoding.WINDOWS_1252), decodeText(bytes))
        assertEquals(DecodedText("aaé", TextEncoding.WINDOWS_1252), decodeText(bytes(0x61, 0x61, 0xE9)))
    }

    @Test
    fun `bytes undefined in Windows-1252 decode to nothing`() {
        for (undefined in listOf(0x81, 0x8D, 0x8F, 0x90, 0x9D)) {
            assertNull(decodeText(bytes(0x61, 0xE4, undefined)), "0x%X".format(undefined))
        }
    }

    @Test
    fun `decodes leniently with replacement characters what does not decode`() {
        assertEquals("ä", decodeTextLeniently(bytes(0xE4)))
        assertEquals("a${Char(0xFFFD)}", decodeTextLeniently(bytes(0x61, 0x81)))
    }

    @Test
    fun `UTF-8 and Windows-1252 samples are text`() {
        assertTrue(
            isTextSample("fun main() = println(\"Grüße ✓\")\r\n\t\u000C\u001B[0m".toByteArray(), complete = true),
        )
        assertTrue(isTextSample(bytes(0x47, 0x72, 0xFC, 0x65, 0x0A), complete = true))
        assertTrue(isTextSample(ByteArray(0), complete = true))
    }

    @Test
    fun `a NUL byte is not text`() {
        assertFalse(isTextSample(bytes(0x50, 0x4B, 0x03, 0x04, 0x00, 0x14), complete = true))
        assertFalse(isTextSample(bytes(0x61, 0x00), complete = false))
    }

    @Test
    fun `control bytes in what is not UTF-8 are not text`() {
        assertFalse(isTextSample(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), complete = true))
        assertFalse(isTextSample(bytes(0xE4, 0x01), complete = true))
        // Valid UTF-8 is text, whatever controls it has.
        assertTrue(isTextSample(bytes(0x61, 0x01), complete = true))
    }

    @Test
    fun `a UTF-8 character cut off at the end is text only in an incomplete sample`() {
        // A control byte, so that it is not Windows-1252 text either.
        val cut = "\u0001€".toByteArray().copyOf(3)
        assertTrue(isTextSample(cut, complete = false))
        assertFalse(isTextSample(cut, complete = true))
    }
}
