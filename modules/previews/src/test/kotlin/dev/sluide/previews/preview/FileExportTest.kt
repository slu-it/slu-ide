package dev.sluide.previews.preview

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileExportTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `writing replaces the target and leaves nothing else behind`() {
        val target = dir.resolve("notes.html").apply { writeText("old") }
        writeReplacing(target) { Files.writeString(it, "new") }
        assertEquals("new", target.readText())
        assertEquals(listOf(target), dir.listDirectoryEntries())
    }

    @Test
    fun `a failed write keeps the target and deletes the temporary file`() {
        val target = dir.resolve("notes.html").apply { writeText("old") }
        assertFailsWith<IOException> {
            writeReplacing(target) {
                Files.writeString(it, "half")
                throw IOException("Disk full")
            }
        }
        assertEquals("old", target.readText())
        assertEquals(listOf(target), dir.listDirectoryEntries())
    }

    @Test
    fun `a replaced target keeps its permissions`() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val target = dir.resolve("logo.png").apply { writeText("old") }
        val permissions = PosixFilePermissions.fromString("rw-rw-r--")
        Files.setPosixFilePermissions(target, permissions)
        writeReplacing(target) { Files.writeString(it, "new") }
        assertEquals(permissions, Files.getPosixFilePermissions(target))
    }

    @Test
    fun `a target that is a link is written where it points`() {
        val real = dir.resolve("real.html").apply { writeText("old") }
        val link = Files.createSymbolicLink(dir.resolve("notes.html"), real)
        writeReplacing(link) { Files.writeString(it, "new") }
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("new", real.readText())
    }
}
