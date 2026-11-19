package dev.sluide.codeintelligence.edits

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading and writing closed files for a workspace edit: which files are refused and why, the BOM and the size limit,
 * and the atomic write that keeps permissions, goes through links and leaves nothing behind when it fails.
 */
class ClosedFilesTest {
    @TempDir
    lateinit var root: Path

    private val project get() = root.resolve("project").createDirectories().toRealPath()

    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private fun refusal(action: () -> Unit) = assertFailsWith<EditRefusedException> { action() }.message

    private fun file(
        name: String,
        bytes: ByteArray,
    ): Path = project.resolve(name).also { it.writeBytes(bytes) }

    private fun sparse(
        name: String,
        size: Long,
    ): Path = project.resolve(name).also { RandomAccessFile(it.toFile(), "rw").use { raf -> raf.setLength(size) } }

    private fun setPermissions(
        path: Path,
        permissions: String,
    ) = Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))

    /** Permission bits only bind users other than root, which a container may run tests as. */
    private fun assumeEnforced(path: Path) {
        setPermissions(path, "r--r--r--")
        assumeTrue(!Files.isWritable(path), "the file system does not enforce permissions for this user")
    }

    @Test
    fun `a file that is no regular file is refused as missing`() {
        assertEquals("does not exist", refusal { readClosedFile(project.resolve("gone.py"), project) })
        val directory = project.resolve("pkg").createDirectories()
        assertEquals("does not exist", refusal { readClosedFile(directory, project) })
    }

    @Test
    fun `a file is read only inside an existing project folder, by real paths`() {
        val inside = file("a.py", "x".toByteArray())
        assertEquals("x", readClosedFile(inside, project).text)

        assertEquals("outside the project", refusal { readClosedFile(inside, null) })
        assertEquals("outside the project", refusal { readClosedFile(inside, root.resolve("missing")) })
        val notAFolder = file("folder.txt", ByteArray(0))
        assertEquals("outside the project", refusal { readClosedFile(inside, notAFolder) })

        // A sibling whose name starts with the project's is not inside it.
        val sibling =
            root
                .resolve("project2")
                .createDirectories()
                .resolve("b.py")
                .also { it.writeText("y") }
        assertEquals("outside the project", refusal { readClosedFile(sibling, project) })
        // A link inside the project to a file outside is outside.
        val link = Files.createSymbolicLink(project.resolve("link.py"), sibling)
        assertEquals("outside the project", refusal { readClosedFile(link, project) })
    }

    @Test
    fun `a read-only, an unreadable and a non-UTF-8 file are refused`() {
        val readOnly = file("ro.py", "x".toByteArray())
        assumeEnforced(readOnly)
        assertEquals("read-only", refusal { readClosedFile(readOnly, project) })

        val writeOnly = file("wo.py", "x".toByteArray())
        setPermissions(writeOnly, "-w-------")
        assumeTrue(!Files.isReadable(writeOnly), "the file system does not enforce permissions for this user")
        assertEquals("access denied", refusal { readClosedFile(writeOnly, project) })

        val latin = file("latin.py", byteArrayOf('a'.code.toByte(), 0xE9.toByte()))
        assertEquals("not UTF-8", refusal { readClosedFile(latin, project) })
    }

    @Test
    fun `a file of exactly the limit is read, one byte more is refused`() {
        val limit = sparse("limit.py", MAX_CLOSED_FILE_BYTES)
        assertEquals(MAX_CLOSED_FILE_BYTES.toInt(), readClosedFile(limit, project).bytes.size)
        val over = sparse("over.py", MAX_CLOSED_FILE_BYTES + 1)
        assertEquals("larger than 10 MiB", refusal { readClosedFile(over, project) })
    }

    @Test
    fun `a BOM is told and left out of the text, also when it is all there is`() {
        val withBom = readClosedFile(file("bom.py", bom + "ä".toByteArray()), project)
        assertTrue(withBom.bom)
        assertEquals("ä", withBom.text)
        assertEquals(5, withBom.bytes.size, "the bytes are those on disk")
        val only = readClosedFile(file("only.py", bom), project)
        assertTrue(only.bom)
        assertEquals("", only.text)
        val without = readClosedFile(file("plain.py", "ä".toByteArray()), project)
        assertFalse(without.bom)
        assertEquals("ä", without.text)
    }

    @Test
    fun `the text on disk as an editor holds it has no BOM and LF breaks, and none for what cannot be read`() {
        assertEquals("a\nb\nc\n", readDiskText(file("crlf.py", bom + "a\r\nb\rc\n".toByteArray())))
        assertEquals("", readDiskText(file("only.py", bom)))
        assertNull(readDiskText(file("latin.py", byteArrayOf(0xE9.toByte()))))
        assertNull(readDiskText(project.resolve("gone.py")))
        assertEquals(MAX_CLOSED_FILE_BYTES.toInt(), readDiskText(sparse("limit.py", MAX_CLOSED_FILE_BYTES))?.length)
        assertNull(readDiskText(sparse("over.py", MAX_CLOSED_FILE_BYTES + 1)))
    }

    @Test
    fun `writing keeps the BOM the file had and goes through a link to its target`() {
        val target = file("target.py", bom + "old".toByteArray())
        val link = Files.createSymbolicLink(project.resolve("link.py"), target)
        writeClosedFile(readClosedFile(link, project), "new ä")
        assertContentEquals(bom + "new ä".toByteArray(), target.readBytes())
        assertTrue(Files.isSymbolicLink(link), "the link stays a link")

        val plain = file("plain.py", "old".toByteArray())
        writeClosedFile(readClosedFile(plain, project), "new")
        assertContentEquals("new".toByteArray(), plain.readBytes())
    }

    @Test
    fun `a file that changed since it was read is not overwritten`() {
        val file = file("a.py", "old".toByteArray())
        val read = readClosedFile(file, project)
        file.writeText("changed by someone else")
        assertEquals("changed on disk meanwhile", refusal { writeClosedFile(read, "mine") })
        assertEquals("changed by someone else", file.toFile().readText())
    }

    @Test
    fun `a file that went away or became read-only is refused when written`() {
        val gone = file("gone.py", "old".toByteArray())
        val readGone = readClosedFile(gone, project)
        Files.delete(gone)
        assertEquals("does not exist", refusal { writeClosedFile(readGone, "x") })

        val locked = file("locked.py", "old".toByteArray())
        val readLocked = readClosedFile(locked, project)
        assumeEnforced(locked)
        assertEquals("read-only", refusal { writeClosedFile(readLocked, "x") })
    }

    @Test
    fun `a folder that cannot take the temporary file refuses the write and leaves the file`() {
        val folder = project.resolve("sealed").createDirectories()
        val file = folder.resolve("a.py").also { it.writeText("old") }
        val read = readClosedFile(file, project)
        setPermissions(folder, "r-xr-xr-x")
        try {
            assumeTrue(!Files.isWritable(folder), "the file system does not enforce permissions for this user")
            assertEquals("access denied", refusal { writeClosedFile(read, "new") })
        } finally {
            setPermissions(folder, "rwxr-xr-x")
        }
        assertEquals("old", file.toFile().readText())
    }

    @Test
    fun `another failure of the write is refused with the system's own words`() {
        // The temporary file's name is longer than the file's: a name at the limit cannot get one.
        val file = file("a".repeat(240) + ".py", "old".toByteArray())
        val read = readClosedFile(file, project)
        val reason = checkNotNull(refusal { writeClosedFile(read, "new") })
        assertTrue(reason.endsWith("File name too long"), reason)
        assertEquals("old", file.toFile().readText())
    }

    @Test
    fun `a stale temporary file of an earlier write does not block the next one`() {
        val file = file("a.py", "old".toByteArray())
        val read = readClosedFile(file, project)
        // The name an earlier write left behind if its temporary names did not differ.
        project.resolve(".a.py-null.tmp").writeText("stale")
        writeClosedFile(read, "new")
        assertEquals("new", file.toFile().readText())
        assertEquals("stale", project.resolve(".a.py-null.tmp").toFile().readText())
    }

    @Test
    fun `a write keeps the permissions of the file and leaves no temporary file`() {
        val file = file("run.sh", "old".toByteArray())
        setPermissions(file, "rwx------")
        writeClosedFile(readClosedFile(file, project), "new")
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals(listOf("run.sh"), project.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `an IOException is told as its own words, access denied and missing as such, none by its class`() {
        assertEquals("access denied", ioReason(java.nio.file.AccessDeniedException("/x")))
        assertEquals("does not exist", ioReason(java.nio.file.NoSuchFileException("/x")))
        assertEquals("disk full", ioReason(IOException("disk full")))
        assertEquals("IOException", ioReason(IOException()))
    }

    @Test
    fun `a temporary file that cannot be removed is told as suppressed by the error that led to it`() {
        val stuck = project.resolve("stuck").createDirectories()
        stuck.resolve("inside").writeText("x")
        val cause = IOException("move failed")
        removeQuietly(stuck, cause)
        assertTrue(cause.suppressed.single() is java.nio.file.DirectoryNotEmptyException)
        val gone = project.resolve("gone.tmp").also { it.writeText("x") }
        removeQuietly(gone, cause)
        assertFalse(Files.exists(gone))
        assertEquals(1, cause.suppressed.size)
    }

    @Test
    fun `a move that fails removes the temporary file and keeps the error`() {
        // Moving a file over an empty folder fails (the rename of a file onto a directory).
        val target = project.resolve("dir").createDirectories()
        assertFailsWith<IOException> { writeAtomically(target, "x".toByteArray()) }
        assertEquals(listOf("dir"), project.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a file system without POSIX permissions is not asked for them, and its failure is an IOException`() {
        val zip = root.resolve("fs.zip")
        val options = mapOf("create" to "true", "enablePosixFileAttributes" to "false")
        FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), options).use { fs ->
            val existing = fs.getPath("/a.py").also { it.writeText("old") }
            // The zip provider refuses to move over an existing file: an IOException the caller turns into a refusal.
            // Asking this file store for POSIX permissions would instead throw an UnsupportedOperationException.
            assertFailsWith<IOException> { writeAtomically(existing, "new".toByteArray()) }
            assertEquals(listOf("a.py"), fs.getPath("/").listDirectoryEntries().map { it.name })
            assertEquals("old", Files.readString(existing))
        }
    }

    @Test
    fun `a path that is not a regular file gets a new file with the default permissions, not the link's`() {
        val target = file("target.py", "old".toByteArray())
        val link = Files.createSymbolicLink(project.resolve("link.py"), target)
        val reference = project.resolve("reference")
        Files.newByteChannel(reference, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).close()
        writeAtomically(link, "new".toByteArray())
        assertEquals(Files.getPosixFilePermissions(reference), Files.getPosixFilePermissions(link))
        assertEquals("new", link.toFile().readText())
    }
}
