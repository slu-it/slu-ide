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
    fun `Windows-1252 is text`() {
        val file = dir.resolve("latin1.txt").apply { writeBytes(byteArrayOf(0x47, 0x72, 0xFC.toByte(), 0x65)) }
        assertTrue(isTextFile(file))
    }

    @Test
    fun `truncated character at the very end of a short file is no UTF-8`() {
        // With a control byte, so that it is no Windows-1252 text either.
        val bytes = "a\u0001b".toByteArray() + byteArrayOf(0xE2.toByte())
        val file = dir.resolve("broken.txt").apply { writeBytes(bytes) }
        assertFalse(isTextFile(file))
    }

    @Test
    fun `multi-byte character cut off by the sample size is still text`() {
        val euro = "€".toByteArray() // 3 bytes
        // With a control byte, so that it is text as UTF-8 only, not as Windows-1252.
        val prefix = ByteArray(TEXT_SAMPLE_SIZE - 1) { if (it == 0) 1 else 'a'.code.toByte() }
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
    fun `text sample is the start of a text file and null otherwise`() {
        val long = dir.resolve("long.txt").apply { writeText("a".repeat(TEXT_SAMPLE_SIZE + 10)) }
        assertEquals(TEXT_SAMPLE_SIZE, readTextSample(long)?.size)
        val binary = dir.resolve("data.bin").apply { writeBytes(byteArrayOf(0x23, 0x21, 0x00)) }
        assertNull(readTextSample(binary))
        assertNull(readTextSample(dir.resolve("missing")))
    }

    @Test
    fun `first line is read from the sample without its line break`() {
        fun firstLineOf(text: String) =
            readTextSample(dir.resolve("script").apply { writeText(text) })?.let(::firstLine)

        assertEquals("#!/bin/sh", firstLineOf("#!/bin/sh\necho hi\n"))
        assertEquals("#!/bin/sh", firstLineOf("#!/bin/sh\r\necho hi\r\n"))
        assertEquals("only line", firstLineOf("only line"))
        assertEquals("", firstLineOf(""))
        assertEquals("a".repeat(TEXT_SAMPLE_SIZE), firstLineOf("a".repeat(TEXT_SAMPLE_SIZE + 10)))
    }

    @Test
    fun `reads a text file as the code views do, and nothing that is neither UTF-8 nor Windows-1252`() {
        fun textOf(vararg bytes: Int) =
            readTextFile(dir.resolve("file.txt").apply { writeBytes(ByteArray(bytes.size) { bytes[it].toByte() }) })

        assertEquals("aä", textOf(0x61, 0xE4))
        // 0x81 is undefined in Windows-1252.
        assertNull(textOf(0x61, 0x81))
    }
}
