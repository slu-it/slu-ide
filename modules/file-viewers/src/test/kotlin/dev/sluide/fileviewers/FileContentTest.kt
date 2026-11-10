package dev.sluide.fileviewers

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** [DiskContent], the content views read and save by default, and which names plain text opens as content. */
class FileContentTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `disk content reads the file's bytes and writes them in place, truncating, creating a deleted file`() {
        val file = dir.resolve("a.txt").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val content = DiskContent(file)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), content.read())

        content.write(byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), file.readBytes())
        assertContentEquals(byteArrayOf(9), content.read())

        Files.delete(file)
        assertFailsWith<NoSuchFileException> { content.read() }
        content.write(byteArrayOf(7, 8))
        assertContentEquals(byteArrayOf(7, 8), file.readBytes())
        assertFalse(content.confidential)
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(
        "notes.txt, true",
        "NOTES.TXT, true",
        "a.b.Txt, true",
        "notes.md, false",
        "txt, false",
        "notes.txt.bak, false",
    )
    fun `plain text opens content of txt files only`(
        name: String,
        opens: Boolean,
    ) {
        assertEquals(opens, DefaultTextFileViewer().opensContent(name))
    }
}
