package dev.sluide.fileviewers.code

import dev.sluide.textediting.TextEncoding
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileEncodingTest {
    @Test
    fun `encodes in the encoding the file was read in`() {
        assertContentEquals(
            byteArrayOf(0x61, 0xE4.toByte(), 0x80.toByte()),
            FileEncoding(TextEncoding.WINDOWS_1252).encode("aä€"),
        )
        assertContentEquals("aä€".toByteArray(), FileEncoding(TextEncoding.UTF_8).encode("aä€"))
    }

    @Test
    fun `a character the encoding cannot store fails with its line`() {
        val e = assertFailsWith<IOException> { FileEncoding(TextEncoding.WINDOWS_1252).encode("a\nä\r\nb 😀 ✓\n") }
        assertEquals(
            "Line 3 contains '😀' (U+1F600), which Windows-1252 cannot store. Convert the file to UTF-8 first.",
            e.message,
        )
    }

    @Test
    fun `finds the first character an encoding cannot store and its line, whatever the line endings`() {
        assertEquals("✓" to 1, firstUnencodable("ä✓", TextEncoding.WINDOWS_1252))
        assertEquals("😀" to 3, firstUnencodable("a\nb\nc😀", TextEncoding.WINDOWS_1252))
        assertEquals("✓" to 3, firstUnencodable("a\r\nb\r\n✓", TextEncoding.WINDOWS_1252))
        assertEquals("✓" to 3, firstUnencodable("a\rb\r✓", TextEncoding.WINDOWS_1252))
        assertNull(firstUnencodable("Grüße €\n", TextEncoding.WINDOWS_1252))
        assertNull(firstUnencodable("😀✓", TextEncoding.UTF_8))
    }

    @Test
    fun `converting saves UTF-8 and stays pending until saved`() {
        val encoding = FileEncoding(TextEncoding.WINDOWS_1252)
        assertTrue(encoding.convertible)

        encoding.convert()
        assertEquals(TextEncoding.UTF_8, encoding.encoding)
        assertTrue(encoding.converting.get())
        assertFalse(encoding.convertible)
        assertContentEquals("ä".toByteArray(), encoding.encode("ä"))

        encoding.saved()
        assertFalse(encoding.converting.get())
    }

    @Test
    fun `a UTF-8 file is not converted`() {
        val encoding = FileEncoding(TextEncoding.UTF_8)
        assertFalse(encoding.convertible)
        encoding.convert()
        assertFalse(encoding.converting.get())
    }

    @Test
    fun `the encoding follows the file read again and drops a pending conversion`() {
        val encoding = FileEncoding(TextEncoding.WINDOWS_1252).apply { convert() }
        encoding.read(TextEncoding.WINDOWS_1252)
        assertEquals(TextEncoding.WINDOWS_1252, encoding.encoding)
        assertFalse(encoding.converting.get())

        encoding.read(null)
        assertEquals(TextEncoding.UTF_8, encoding.encoding, "not saved, nothing to convert")
        assertFalse(encoding.convertible)
    }

    @Test
    fun `an unchanged text on disk keeps a pending conversion until the file is UTF-8`() {
        val encoding = FileEncoding(TextEncoding.WINDOWS_1252).apply { convert() }
        encoding.unchanged(TextEncoding.WINDOWS_1252)
        assertTrue(encoding.converting.get())
        assertEquals(TextEncoding.UTF_8, encoding.encoding)

        encoding.unchanged(TextEncoding.UTF_8)
        assertFalse(encoding.converting.get(), "converted on disk")
    }

    @Test
    fun `an unchanged text on disk in another encoding takes it`() {
        val encoding = FileEncoding(TextEncoding.WINDOWS_1252)
        encoding.unchanged(TextEncoding.UTF_8)
        assertEquals(TextEncoding.UTF_8, encoding.encoding)

        encoding.unchanged(null)
        assertEquals(TextEncoding.UTF_8, encoding.encoding, "kept")
    }
}
