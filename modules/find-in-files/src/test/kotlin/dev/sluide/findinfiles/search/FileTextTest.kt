package dev.sluide.findinfiles.search

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class FileTextTest {
    @TempDir
    lateinit var folder: Path

    private fun fileOf(vararg parts: ByteArray): Path =
        folder.resolve("f.txt").also {
            it.writeBytes(parts.reduce(ByteArray::plus))
        }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `an unsaved buffer of exactly the limit is text, one more is too large, with its line breaks normalized`() {
        val file = folder.resolve("buffer.txt")

        assertEquals(FileText.Text("ab\ncd"), readText(file, mapOf(file to "ab\r\ncd"), 6))
        assertEquals(FileText.Text("abc\ndef"), readText(file, mapOf(file to "abc\ndef"), 7))
        assertEquals(FileText.TooLarge, readText(file, mapOf(file to "abc\ndef!"), 7))
    }

    @Test
    fun `only the first 8 KiB decide whether a file is text`() {
        val text = ByteArray(TEXT_SAMPLE_BYTES + 10) { 'a'.code.toByte() }
        text[TEXT_SAMPLE_BYTES + 5] = 0

        // A NUL byte after the sample does not make a binary of it, the same as in the editor.
        assertEquals(FileText.Text(String(text)), readText(fileOf(text), emptyMap(), 100_000))
    }

    @Test
    fun `a UTF-8 character cut off by the end of a file of exactly the sample size makes it no text`() {
        // A control byte first, so that the bytes are not Windows-1252 text either.
        val filler = ByteArray(TEXT_SAMPLE_BYTES - 3) { 'a'.code.toByte() }
        val cut = fileOf(filler, bytes(0x01, 0xE2, 0x82))

        assertEquals(FileText.Unreadable, readText(cut, emptyMap(), 100_000))
    }

    @Test
    fun `a UTF-8 character cut off by the sample of a larger file is fine`() {
        val filler = ByteArray(TEXT_SAMPLE_BYTES - 1) { 'a'.code.toByte() }
        val file = fileOf(filler, "€".toByteArray(), "tail".toByteArray())

        assertEquals(FileText.Text(String(filler) + "€tail"), readText(file, emptyMap(), 100_000))
    }

    @Test
    fun `bytes that are text but neither UTF-8 nor Windows-1252 are unreadable`() {
        // 0x81 is no Windows-1252 character and no C0 control byte.
        assertEquals(FileText.Unreadable, readText(fileOf("a".toByteArray(), bytes(0x81)), emptyMap(), 100))
    }

    @Test
    fun `a file of exactly the limit is read, one byte more is too large`() {
        assertEquals(FileText.Text("abcd"), readText(fileOf("abcd".toByteArray()), emptyMap(), 4))
        assertEquals(FileText.TooLarge, readText(fileOf("abcde".toByteArray()), emptyMap(), 4))
    }
}
