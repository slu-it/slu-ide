package dev.sluide.encryptedfiles

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

class AtomicFileWriteTest {
    @TempDir
    lateinit var dir: Path

    private fun assumePosix() =
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView::class.java), "POSIX only")

    @Test
    fun `replaces the content and leaves no temp file`() {
        val target = dir.resolve("notes.md.sluenc").also { it.writeBytes(ByteArray(100) { 1 }) }

        AtomicFileWrite.write(target, byteArrayOf(4, 5, 6))

        assertArrayEquals(byteArrayOf(4, 5, 6), target.readBytes())
        assertEquals(listOf("notes.md.sluenc"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `an existing file keeps its permissions`() {
        assumePosix()
        val target = dir.resolve("notes.md.sluenc").also { it.writeBytes(byteArrayOf(1)) }
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"))

        AtomicFileWrite.write(target, byteArrayOf(2))

        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
    }

    @Test
    fun `a new file is owner-only`() {
        assumePosix()
        val target = dir.resolve("new.txt.sluenc")

        AtomicFileWrite.write(target, byteArrayOf(2))

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
        assertArrayEquals(byteArrayOf(2), target.readBytes())
    }

    @Test
    fun `a failed move leaves no temp file`() {
        val target = Files.createDirectory(dir.resolve("taken.sluenc"))
        target.resolve("inside").writeBytes(byteArrayOf(1))

        assertThrows<IOException> { AtomicFileWrite.write(target, byteArrayOf(2)) }

        assertEquals(listOf("taken.sluenc"), dir.listDirectoryEntries().map { it.name })
        assertEquals(listOf("inside"), target.listDirectoryEntries().map { it.name })
    }
}
