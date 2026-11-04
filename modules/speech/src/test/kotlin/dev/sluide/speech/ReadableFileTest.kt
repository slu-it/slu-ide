package dev.sluide.speech

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadableFileTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a UTF-8 file is read, Markdown by its name`() {
        val md = Files.writeString(dir.resolve("a.md"), "# Grüße")
        val txt = Files.writeString(dir.resolve("a.txt"), "plain")

        assertEquals(ReadableFile.Text("# Grüße", true), readableFile(md))
        assertEquals(ReadableFile.Text("plain", false), readableFile(txt))
    }

    @Test
    fun `a file over 1 MiB is refused`() {
        val big = Files.write(dir.resolve("big.txt"), ByteArray(MAX_READ_BYTES + 1) { 'a'.code.toByte() })

        assertEquals(ReadableFile.Unreadable("big.txt is larger than 1 MiB."), readableFile(big))
    }

    @Test
    fun `a file of exactly 1 MiB is read`() {
        val exact = Files.write(dir.resolve("exact.txt"), ByteArray(MAX_READ_BYTES) { 'a'.code.toByte() })

        assertEquals(MAX_READ_BYTES, (readableFile(exact) as ReadableFile.Text).text.length)
    }

    @Test
    fun `a file that is not UTF-8 is refused`() {
        val latin = Files.write(dir.resolve("l.txt"), byteArrayOf(0x41, 0xE9.toByte(), 0x42))

        assertEquals(ReadableFile.Unreadable("l.txt is not UTF-8 text."), readableFile(latin))
    }

    @Test
    fun `a missing file gives a readable message`() {
        val result = readableFile(dir.resolve("none.txt"))

        assertEquals(ReadableFile.Unreadable("none.txt cannot be read: no such file"), result)
    }
}
