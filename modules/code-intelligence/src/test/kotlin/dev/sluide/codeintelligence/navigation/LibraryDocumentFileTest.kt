package dev.sluide.codeintelligence.navigation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The cache file of a library document: the server's text as it came, replaced in one step, bounded. */
class LibraryDocumentFileTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the text is written exactly as received, with its line endings and non-BMP characters, below new folders`() {
        val file = dir.resolve("java.base/java/lang/String.java")
        val text = "package java.lang;\r\n// 😀 é\n\r\nclass String {}\r"

        writeLibraryDocument(file, text)

        assertContentEquals(text.toByteArray(Charsets.UTF_8), file.readBytes())
    }

    @Test
    fun `an existing cache file is replaced and no temporary file stays`() {
        val file = dir.resolve("a/A.java").also { it.parent.createDirectories() }
        file.writeText("old text that is longer than the new one")

        writeLibraryDocument(file, "new")

        assertEquals("new", file.readBytes().toString(Charsets.UTF_8))
        assertEquals(listOf("A.java"), file.parent.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `a text over the limit is refused and writes nothing, one at the limit is written`() {
        val file = dir.resolve("a/A.java")

        writeLibraryDocument(file, "a".repeat(8), maxBytes = 8)
        assertEquals(8, file.readBytes().size)

        val big = dir.resolve("b/B.java")
        assertFailsWith<IOException> { writeLibraryDocument(big, "a".repeat(9), maxBytes = 8) }
        assertFalse(big.exists())
        assertFalse(big.parent.exists(), "not even the folder")
    }

    @Test
    fun `the limit counts UTF-8 bytes, not characters`() {
        val file = dir.resolve("A.java")

        assertFailsWith<IOException> { writeLibraryDocument(file, "é".repeat(5), maxBytes = 9) }
        assertFalse(file.exists())
    }

    @Test
    fun `a move that fails after the text was written leaves the target and no temporary file`() {
        val target = dir.resolve("A.java").createDirectories()
        target.resolve("child").createFile()

        assertFailsWith<IOException> { writeLibraryDocument(target, "x") }

        assertEquals(listOf("A.java"), dir.listDirectoryEntries().map { it.fileName.toString() })
        assertEquals(listOf("child"), target.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `a failed write leaves the earlier file and no temporary file`() {
        val blocker = dir.resolve("blocker").createFile()

        assertFailsWith<IOException> { writeLibraryDocument(blocker.resolve("A.java"), "x") }

        assertEquals(listOf("blocker"), dir.listDirectoryEntries().map { it.fileName.toString() })
    }
}
