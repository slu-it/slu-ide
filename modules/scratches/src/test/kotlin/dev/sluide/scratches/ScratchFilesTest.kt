package dev.sluide.scratches

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScratchFilesTest {
    @TempDir
    lateinit var temp: Path

    private val kotlin = ScratchType("Kotlin", "kt")
    private val markdown = ScratchType("Markdown", "md")

    private fun scratches() = ScratchFiles(temp.resolve("scratches"))

    @Test
    fun `types are unique by name and extension`() {
        assertEquals(SCRATCH_TYPES.size, SCRATCH_TYPES.map { it.name }.toSet().size)
        assertEquals(SCRATCH_TYPES.size, SCRATCH_TYPES.map { it.extension }.toSet().size)
    }

    @Test
    fun `the constructor does not touch the disk and create makes the folder`() {
        val files = scratches()
        assertFalse(Files.exists(files.folder))
        val created = files.create(kotlin)
        assertTrue(Files.isDirectory(files.folder))
        assertEquals(files.folder.resolve("scratch.kt"), created)
    }

    @Test
    fun `create numbers files by the lowest free number`() {
        val files = scratches()
        assertEquals("scratch.kt", files.create(kotlin).fileName.toString())
        assertEquals("scratch_1.kt", files.create(kotlin).fileName.toString())
        assertEquals("scratch_2.kt", files.create(kotlin).fileName.toString())
        Files.delete(files.folder.resolve("scratch_1.kt"))
        assertEquals("scratch_1.kt", files.create(kotlin).fileName.toString())
        assertEquals("scratch_3.kt", files.create(kotlin).fileName.toString())
    }

    @Test
    fun `only names of the rule count`() {
        val files = scratches()
        files.ensureFolder()
        Files.createFile(files.folder.resolve("scratch_1.kt"))
        assertEquals("scratch.kt", files.create(kotlin).fileName.toString())
    }

    @Test
    fun `existing entries, folders too, are skipped`() {
        val files = scratches()
        files.ensureFolder()
        Files.createDirectory(files.folder.resolve("scratch.md"))
        Files.createFile(files.folder.resolve("scratch_1.md"))
        Files.createFile(files.folder.resolve("scratch_2.md"))
        assertEquals("scratch_3.md", files.create(markdown).fileName.toString())
    }

    @Test
    fun `create sets the last type`() {
        val files = scratches()
        assertNull(files.lastType)
        files.create(markdown)
        assertEquals(markdown, files.lastType)
    }

    @Test
    fun `contains is true strictly below the folder`() {
        val files = scratches()
        assertTrue(files.contains(files.folder.resolve("a.txt")))
        assertTrue(files.contains(files.folder.resolve("x/../a.txt")))
        assertFalse(files.contains(files.folder))
        assertFalse(files.contains(files.folder.resolve("..")))
        assertFalse(files.contains(files.folder.resolveSibling("scratches2/a.txt")))
        assertFalse(files.contains(temp.resolve("other.txt")))
        assertFalse(files.contains(files.folder.resolve("../other.txt")))
    }

    @Test
    fun `deleteIfEmpty deletes only an empty contained file`() {
        val files = scratches()
        val empty = files.create(kotlin)
        val full = files.create(kotlin).also { Files.writeString(it, "x") }
        val folder = Files.createDirectory(files.folder.resolve("dir"))
        val outside = Files.createFile(temp.resolve("outside.txt"))

        assertTrue(files.deleteIfEmpty(empty))
        assertFalse(Files.exists(empty))
        assertFalse(files.deleteIfEmpty(full))
        assertTrue(Files.exists(full))
        assertFalse(files.deleteIfEmpty(folder))
        assertTrue(Files.exists(folder))
        assertFalse(files.deleteIfEmpty(outside))
        assertTrue(Files.exists(outside))
        assertFalse(files.deleteIfEmpty(empty), "missing")
    }

    private fun symlink(
        link: Path,
        target: Path,
    ) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: UnsupportedOperationException) {
            Assumptions.abort("no symbolic links: $e")
        } catch (e: IOException) {
            Assumptions.abort("no symbolic links: $e")
        }
    }

    @Test
    fun `deleteIfEmpty does not follow a link`() {
        val files = scratches()
        files.ensureFolder()
        val target = Files.createFile(temp.resolve("target.txt"))
        val link = files.folder.resolve("link.txt")
        symlink(link, target)
        assertFalse(files.deleteIfEmpty(link))
        assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(target))
    }

    @Test
    fun `deleteIfEmpty keeps a file below a symlinked subfolder that leads out`() {
        val files = scratches()
        files.ensureFolder()
        val project = Files.createDirectory(temp.resolve("project"))
        val empty = Files.createFile(project.resolve("empty.txt"))
        symlink(files.folder.resolve("linked"), project)
        assertFalse(files.deleteIfEmpty(files.folder.resolve("linked/empty.txt")))
        assertTrue(Files.exists(empty))
    }

    @Test
    fun `deleteIfEmpty deletes an empty file in a real subfolder`() {
        val files = scratches()
        files.ensureFolder()
        val file = Files.createFile(Files.createDirectory(files.folder.resolve("sub")).resolve("a.txt"))
        assertTrue(files.deleteIfEmpty(file))
    }

    @Test
    fun `deleteIfEmpty answers false when the file cannot be deleted`() {
        val files = scratches()
        val file = files.create(kotlin)
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        Files.setPosixFilePermissions(files.folder, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            Assumptions.assumeFalse(Files.isWritable(files.folder), "running as root")
            assertFalse(files.deleteIfEmpty(file))
            assertTrue(Files.exists(file))
        } finally {
            Files.setPosixFilePermissions(files.folder, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `create works when the folder is a symlink`() {
        val real = Files.createDirectory(temp.resolve("real"))
        val link = temp.resolve("link")
        symlink(link, real)
        val files = ScratchFiles(link)
        assertEquals(link.resolve("scratch.kt"), files.create(kotlin))
        assertTrue(Files.exists(real.resolve("scratch.kt")))
        assertEquals(link.resolve("scratch_1.kt"), files.create(kotlin))
    }
}
