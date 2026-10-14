package dev.sluide.fileviewers

import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextFileDetectionTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `source code is text`() {
        val file = dir.resolve("Main.kt").apply { writeText("fun main() {\n    println(\"Grüße ✓\")\n}\n") }
        assertTrue(isTextFile(file))
    }

    @Test
    fun `empty file is text`() {
        val file = dir.resolve("empty").apply { writeBytes(byteArrayOf()) }
        assertTrue(isTextFile(file))
    }

    @Test
    fun `file with a NUL byte is not text`() {
        val file = dir.resolve("data.bin").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x14)) }
        assertFalse(isTextFile(file))
    }

    @Test
    fun `invalid UTF-8 is not text`() {
        val file = dir.resolve("latin1.txt").apply { writeBytes(byteArrayOf(0x47, 0x72, 0xFC.toByte(), 0x65)) }
        assertFalse(isTextFile(file))
    }

    @Test
    fun `truncated character at the very end of a short file is not text`() {
        val file = dir.resolve("broken.txt").apply { writeBytes("ab".toByteArray() + byteArrayOf(0xE2.toByte())) }
        assertFalse(isTextFile(file))
    }

    @Test
    fun `multi-byte character cut off by the sample size is still text`() {
        val euro = "€".toByteArray() // 3 bytes
        val prefix = ByteArray(TEXT_SAMPLE_SIZE - 1) { 'a'.code.toByte() }
        val file = dir.resolve("long.txt").apply { writeBytes(prefix + euro) }
        assertTrue(isTextFile(file))
    }

    @Test
    fun `oversized file is not text`() {
        val file = dir.resolve("huge.txt")
        RandomAccessFile(file.toFile(), "rw").use { it.setLength(MAX_TEXT_FILE_SIZE + 1) }
        assertFalse(isTextFile(file))
    }

    @Test
    fun `missing file and folder are not text`() {
        assertFalse(isTextFile(dir.resolve("missing.txt")))
        assertFalse(isTextFile(dir.resolve("folder").createDirectories()))
    }

    @Test
    fun `decodes valid UTF-8 strictly`() {
        assertEquals("ä€", decodeUtf8("ä€".toByteArray()))
        assertEquals("", decodeUtf8(ByteArray(0)))
        assertNull(decodeUtf8(ByteArray(9000) { 'a'.code.toByte() } + 0xE9.toByte()))
    }
}
