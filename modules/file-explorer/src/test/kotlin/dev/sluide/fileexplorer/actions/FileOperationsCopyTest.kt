package dev.sluide.fileexplorer.actions

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Whether a folder is the entry or inside it, and copies of links and read-only folders: besides [FileOperationsTest]. */
class FileOperationsCopyTest {
    @TempDir
    lateinit var folder: Path

    @Test
    fun `a folder is at or below an entry that is it or holds it, but not a sibling with the same start`() {
        val entry = folder.resolve("a").createDirectories()
        val inside = entry.resolve("b/c").createDirectories()
        val sibling = folder.resolve("ab").createDirectories()

        assertTrue(FileOperations.isAtOrBelow(entry, entry))
        assertTrue(FileOperations.isAtOrBelow(inside, entry))
        assertFalse(FileOperations.isAtOrBelow(sibling, entry))
        assertFalse(FileOperations.isAtOrBelow(entry, inside))
        assertFalse(FileOperations.isAtOrBelow(folder, entry))
    }

    @Test
    fun `paths that do not exist are compared as written`() {
        val entry = Path.of("/no/such/place/a")

        assertTrue(FileOperations.isAtOrBelow(entry.resolve("b"), entry))
        assertFalse(FileOperations.isAtOrBelow(Path.of("/no/such/place/ab"), entry))
        assertFalse(FileOperations.isAtOrBelow(Path.of("/no/such/other"), entry))
    }

    @Test
    fun `an entry without a folder holds what starts with it, and nothing else`() {
        assertTrue(FileOperations.isAtOrBelow(Path.of("x/y"), Path.of("x")))
        assertFalse(FileOperations.isAtOrBelow(folder, Path.of("x")))
        assertTrue(FileOperations.isAtOrBelow(folder, Path.of("/")))
    }

    @Test
    fun `a folder reached through a link to the entry is inside it, and a link is an entry of its own`() {
        val real = folder.resolve("real").createDirectories()
        val inner = real.resolve("inner").createDirectories()
        val link = folder.resolve("link").createSymbolicLinkPointingTo(real)

        assertTrue(FileOperations.isAtOrBelow(link.resolve("inner"), real))
        assertTrue(FileOperations.isAtOrBelow(inner, real))
        assertFalse(FileOperations.isAtOrBelow(real, inner))
        assertFalse(FileOperations.isAtOrBelow(real, link), "a link is an entry of its own")
    }

    @Test
    fun `a link to a folder is copied as a link, also into a folder reached through it`() {
        val real = folder.resolve("real").createDirectories()
        val sub = real.resolve("sub").createDirectories()
        val link = folder.resolve("link").createSymbolicLinkPointingTo(real)

        val copy = FileOperations.copy(link, link.resolve("sub"))

        assertEquals(link.resolve("sub/link"), copy)
        assertTrue(Files.isSymbolicLink(sub.resolve("link")))
        assertEquals(real, sub.resolve("link").readSymbolicLink())
    }

    @Test
    fun `a read-only folder is copied with its content and keeps its permissions`() {
        assumeTrue(Files.getFileAttributeView(folder, PosixFileAttributeView::class.java) != null)
        val source = folder.resolve("source").createDirectories()
        source.resolve("inner.txt").writeText("inner")
        val readOnly = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)
        source.setPosixFilePermissions(readOnly)
        val target = folder.resolve("target").createDirectories()
        val copy = target.resolve("source")

        try {
            assertEquals(copy, FileOperations.copy(source, target))

            assertEquals("inner", copy.resolve("inner.txt").readText())
            assertEquals(readOnly, copy.getPosixFilePermissions())
        } finally {
            // Else the temporary folder cannot be cleaned up.
            listOf(source, copy).filter { Files.exists(it) }.forEach {
                it.setPosixFilePermissions(it.getPosixFilePermissions() + PosixFilePermission.OWNER_WRITE)
            }
        }
    }
}
