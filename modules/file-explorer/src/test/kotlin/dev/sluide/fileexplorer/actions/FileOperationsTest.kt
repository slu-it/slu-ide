package dev.sluide.fileexplorer.actions

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileOperationsTest {
    @TempDir
    lateinit var folder: Path

    private fun problem(
        name: String,
        renaming: Path? = null,
    ) = FileOperations.nameProblem(name, folder, renaming)

    @Test
    fun `accepts ordinary names, including dot-files and spaces inside`() {
        listOf("a.txt", ".gitignore", "My Notes.md", "...", "a.b.c").forEach { assertNull(problem(it), it) }
    }

    @Test
    fun `refuses empty names, paths and special names`() {
        assertEquals("Enter a name.", problem(""))
        assertEquals("The name must not start or end with a space.", problem("   "))
        assertEquals("The name must not start or end with a space.", problem(" a"))
        assertEquals("The name must not start or end with a space.", problem("a\t"))
        assertEquals("The name must not contain / or \\.", problem("a/b"))
        assertEquals("The name must not contain / or \\.", problem("a\\b"))
        assertEquals("The name must not contain a NUL character.", problem("a\u0000b"))
        assertEquals("“.” cannot be used as a name.", problem("."))
        assertEquals("“..” cannot be used as a name.", problem(".."))
    }

    @Test
    fun `refuses names that are taken, also by a dangling link`() {
        folder.resolve("taken.txt").createFile()
        folder.resolve("dangling").createSymbolicLinkPointingTo(folder.resolve("nowhere"))

        assertEquals("“taken.txt” already exists in this folder.", problem("taken.txt"))
        assertEquals("“dangling” already exists in this folder.", problem("dangling"))
    }

    @Test
    fun `a renamed entry may keep its name or change its case, but not take another's`() {
        val file = folder.resolve("Main.kt").createFile()
        folder.resolve("Other.kt").createFile()

        assertNull(problem("Main.kt", renaming = file))
        assertNull(problem("main.kt", renaming = file))
        assertEquals("“Other.kt” already exists in this folder.", problem("Other.kt", renaming = file))
    }

    @Test
    fun `creates files and folders`() {
        val file = FileOperations.createFile(folder, "new.txt")
        val sub = FileOperations.createFolder(folder, "sub")

        assertEquals(folder.resolve("new.txt"), file)
        assertTrue(file.isRegularFile())
        assertEquals("", file.readText())
        assertEquals(folder.resolve("sub"), sub)
        assertTrue(sub.isDirectory())
    }

    @Test
    fun `refuses to create with a bad or taken name and does not touch existing files`() {
        folder.resolve("taken.txt").writeText("keep")

        val taken = assertFailsWith<FileOperationException> { FileOperations.createFile(folder, "taken.txt") }
        assertEquals("“taken.txt” already exists in this folder.", taken.message)
        assertFailsWith<FileOperationException> { FileOperations.createFolder(folder, "a/b") }
        assertEquals("keep", folder.resolve("taken.txt").readText())
        assertEquals(listOf("taken.txt"), folder.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `reports why creating failed`() {
        val gone = folder.resolve("gone")

        val failure = assertFailsWith<FileOperationException> { FileOperations.createFile(gone, "a.txt") }

        assertEquals("Could not create “a.txt”: ${gone.resolve("a.txt")} does not exist.", failure.message)
    }

    @Test
    fun `renames files and folders with their content in the same folder`() {
        val file = folder.resolve("old.txt").apply { writeText("content") }
        val sub = folder.resolve("sub").createDirectories()
        sub.resolve("inner.txt").writeText("inner")

        val renamedFile = FileOperations.rename(file, "new.txt")
        val renamedSub = FileOperations.rename(sub, "renamed")

        assertEquals(folder.resolve("new.txt"), renamedFile)
        assertEquals("content", renamedFile.readText())
        assertEquals("inner", renamedSub.resolve("inner.txt").readText())
        assertEquals(listOf("new.txt", "renamed"), folder.listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun `renaming only the case changes the name`() {
        val file = folder.resolve("readme.md").apply { writeText("content") }

        val renamed = FileOperations.rename(file, "README.md")

        assertEquals(listOf("README.md"), folder.listDirectoryEntries().map { it.name })
        assertEquals("content", renamed.readText())
    }

    @Test
    fun `renaming to the same name does nothing`() {
        val file = folder.resolve("same.txt").createFile()

        assertEquals(file, FileOperations.rename(file, "same.txt"))
        assertTrue(file.exists())
    }

    @Test
    fun `refuses to rename onto another entry`() {
        val file = folder.resolve("a.txt").apply { writeText("a") }
        folder.resolve("b.txt").writeText("b")

        assertFailsWith<FileOperationException> { FileOperations.rename(file, "b.txt") }
        assertFailsWith<FileOperationException> { FileOperations.rename(file, "../a.txt") }

        assertEquals("a", file.readText())
        assertEquals("b", folder.resolve("b.txt").readText())
    }

    @Test
    fun `a hard link with the name in another case is another entry`() {
        // It leads to the same file, like the name in another case on a case-insensitive file system.
        val file = folder.resolve("a.txt").apply { writeText("content") }
        assumeFalse(Files.exists(folder.resolve("A.TXT")), "case-insensitive file system")
        Files.createLink(folder.resolve("A.txt"), file)

        assertEquals("“A.txt” already exists in this folder.", problem("A.txt", renaming = file))
        assertFailsWith<FileOperationException> { FileOperations.rename(file, "A.txt") }

        assertEquals(listOf("A.txt", "a.txt"), folder.listDirectoryEntries().map { it.name }.sorted())
        assertEquals("content", file.readText())
    }

    /** Moves like [Files.move], but fails at the calls numbered in [failing] (counted from 1). */
    private class FailingMove(
        private vararg val failing: Int,
    ) : (Path, Path) -> Unit {
        var calls = 0

        override fun invoke(
            from: Path,
            to: Path,
        ) {
            calls++
            if (calls in failing) throw AccessDeniedException(to.toString())
            Files.move(from, to)
        }
    }

    @Test
    fun `a rename over a temporary name moves the entry back if the second step fails`() {
        val file = folder.resolve("a.txt").apply { writeText("content") }

        val failure =
            assertFailsWith<AccessDeniedException> {
                FileOperations.renameOverTemporary(file, folder.resolve("A.txt"), FailingMove(2))
            }

        assertEquals(folder.resolve("A.txt").toString(), failure.file)
        assertEquals(listOf("a.txt"), folder.listDirectoryEntries().map { it.name })
        assertEquals("content", file.readText())
    }

    @Test
    fun `a rename over a temporary name that cannot move back says where the entry was left`() {
        val file = folder.resolve("a.txt").apply { writeText("content") }

        val failure =
            assertFailsWith<FileOperationException> {
                FileOperations.renameOverTemporary(file, folder.resolve("A.txt"), FailingMove(2, 3))
            }

        val temporary = folder.listDirectoryEntries().single()
        assertTrue(temporary.name.startsWith(".a.txt."), temporary.name)
        assertEquals("content", temporary.readText())
        assertEquals(
            "Could not rename “a.txt”, and it was left as $temporary: permission denied for ${folder.resolve(
                "A.txt",
            )}.",
            failure.message,
        )
        val original = failure.cause as AccessDeniedException
        assertEquals(folder.resolve("A.txt").toString(), original.file)
        assertEquals(listOf(file.toString()), original.suppressed.map { (it as AccessDeniedException).file })
    }

    @Test
    fun `renames a link, not its target`() {
        val target = folder.resolve("target.txt").apply { writeText("content") }
        val link = folder.resolve("link").createSymbolicLinkPointingTo(target)

        val renamed = FileOperations.rename(link, "renamed")

        assertTrue(Files.isSymbolicLink(renamed))
        assertEquals("content", target.readText())
    }

    @Test
    fun `deletes files and folders with everything in them`() {
        val file = folder.resolve("a.txt").createFile()
        val sub = folder.resolve("sub/deeper").createDirectories().parent
        sub.resolve("deeper/b.txt").createFile()
        sub.resolve(".hidden").createFile()

        FileOperations.delete(file)
        FileOperations.delete(sub)

        assertEquals(emptyList(), folder.listDirectoryEntries())
    }

    @Test
    fun `deletes links without following them`() {
        val outside = folder.resolve("outside").createDirectories()
        outside.resolve("keep.txt").createFile()
        val sub = folder.resolve("sub").createDirectories()
        sub.resolve("linked-folder").createSymbolicLinkPointingTo(outside)
        val link = folder.resolve("link").createSymbolicLinkPointingTo(outside)

        FileOperations.delete(sub)
        FileOperations.delete(link)

        assertFalse(sub.exists())
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        assertTrue(outside.resolve("keep.txt").exists())
    }

    @Test
    fun `reports why deleting failed`() {
        val gone = folder.resolve("gone.txt")

        val failure = assertFailsWith<FileOperationException> { FileOperations.delete(gone) }

        assertEquals("Could not delete “gone.txt”: $gone does not exist.", failure.message)
    }

    @Test
    fun `moves files and folders with their content into another folder, keeping their names`() {
        val file = folder.resolve("a.txt").apply { writeText("content") }
        val sub = folder.resolve("sub").createDirectories()
        sub.resolve("inner.txt").writeText("inner")
        val target = folder.resolve("target").createDirectories()

        val movedFile = FileOperations.move(file, target)
        val movedSub = FileOperations.move(sub, target)

        assertEquals(target.resolve("a.txt"), movedFile)
        assertEquals(target.resolve("sub"), movedSub)
        assertEquals("content", movedFile.readText())
        assertEquals("inner", movedSub.resolve("inner.txt").readText())
        assertEquals(listOf("target"), folder.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `refuses to move onto an entry of the same name and replaces nothing`() {
        val file = folder.resolve("a.txt").apply { writeText("mine") }
        val target = folder.resolve("target").createDirectories()
        target.resolve("a.txt").writeText("theirs")
        target.resolve("dangling").createSymbolicLinkPointingTo(folder.resolve("nowhere"))
        val link = folder.resolve("dangling").createFile()

        val failure = assertFailsWith<FileOperationException> { FileOperations.move(file, target) }
        assertFailsWith<FileOperationException> { FileOperations.move(link, target) }

        assertEquals("“a.txt” already exists in “target”.", failure.message)
        assertEquals("mine", file.readText())
        assertEquals("theirs", target.resolve("a.txt").readText())
        assertTrue(link.isRegularFile())
        assertTrue(Files.isSymbolicLink(target.resolve("dangling")))
    }

    @Test
    fun `refuses to move a folder into itself or a folder inside it`() {
        val sub = folder.resolve("sub").createDirectories()
        val deeper = sub.resolve("a/b").createDirectories()

        val itself = assertFailsWith<FileOperationException> { FileOperations.move(sub, sub) }
        assertFailsWith<FileOperationException> { FileOperations.move(sub, deeper) }

        assertEquals("“sub” cannot be moved into itself.", itself.message)
        assertTrue(deeper.isDirectory())
        assertEquals(listOf("sub"), folder.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `moves a link, not its target`() {
        val outside = folder.resolve("outside").createDirectories()
        outside.resolve("keep.txt").writeText("content")
        val link = folder.resolve("link").createSymbolicLinkPointingTo(outside)
        val target = folder.resolve("target").createDirectories()

        val moved = FileOperations.move(link, target)

        assertTrue(Files.isSymbolicLink(moved))
        assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
        assertEquals(listOf("keep.txt"), outside.listDirectoryEntries().map { it.name })
        assertEquals("content", moved.resolve("keep.txt").readText())
    }

    @Test
    fun `moving into the folder it is in already does nothing`() {
        val file = folder.resolve("a.txt").createFile()

        assertEquals(file, FileOperations.move(file, folder))
        assertTrue(file.exists())
    }

    @Test
    fun `reports why moving failed`() {
        val gone = folder.resolve("gone.txt")
        val target = folder.resolve("target").createDirectories()

        val failure = assertFailsWith<FileOperationException> { FileOperations.move(gone, target) }

        assertEquals("Could not move “gone.txt”: $gone does not exist.", failure.message)
    }

    @Test
    fun `nothing is moved to another drive, neither a folder nor a file`() {
        // /dev/shm is a file system of its own on Linux.
        val shm = Path.of("/dev/shm")
        assumeTrue(shm.isDirectory() && Files.isWritable(shm))
        val other = Files.createTempDirectory(shm, "sluide-move")
        try {
            assumeTrue(Files.getFileStore(other) != Files.getFileStore(folder))
            val sub = folder.resolve("sub").createDirectories()
            sub.resolve("inner.txt").writeText("inner")
            val file = folder.resolve("a.txt").apply { writeText("a") }

            val failure = assertFailsWith<FileOperationException> { FileOperations.move(sub, other) }
            val fileFailure = assertFailsWith<FileOperationException> { FileOperations.move(file, other) }

            assertEquals("Could not move “sub”: it cannot be moved to another drive.", failure.message)
            assertEquals("Could not move “a.txt”: it cannot be moved to another drive.", fileFailure.message)
            assertEquals("inner", sub.resolve("inner.txt").readText())
            assertEquals("a", file.readText())
            assertEquals(emptyList(), other.listDirectoryEntries())
        } finally {
            FileOperations.delete(other)
        }
    }

    @ParameterizedTest(name = "{0} (folder: {1}) next to [{2}] -> {3}")
    @CsvSource(
        delimiter = '|',
        value = [
            "notes.txt | false |  | notes.txt",
            "notes.txt | false | notes.txt | notes-copy.txt",
            "archive.tar.gz | false | archive.tar.gz | archive-copy.tar.gz",
            ".env | false | .env | .env-copy",
            ".env.local | false | .env.local | .env-copy.local",
            "Makefile | false | Makefile | Makefile-copy",
            "my.dir | true | my.dir | my.dir-copy",
            "notes.txt | false | notes.txt notes-copy.txt | notes-copy2.txt",
            "notes.txt | false | notes.txt notes-copy.txt notes-copy2.txt | notes-copy3.txt",
            "notes.txt | false | notes.txt notes-copy2.txt | notes-copy.txt",
            "notes-copy.txt | false | notes-copy.txt | notes-copy2.txt",
            "notes-copy.txt | false | notes-copy.txt notes-copy2.txt | notes-copy3.txt",
            "notes-copy3.txt | false | notes-copy3.txt notes-copy.txt | notes-copy2.txt",
            "notes-copy10.txt | false | notes-copy10.txt | notes-copy2.txt",
            "notes-copy1.txt | false | notes-copy1.txt | notes-copy1-copy.txt",
            "notes-copy02.txt | false | notes-copy02.txt | notes-copy02-copy.txt",
            "dir-copy | true | dir-copy | dir-copy2",
            "notes.txt | false | NOTES.TXT Notes-Copy.txt | notes-copy2.txt",
        ],
    )
    fun `a copy is named as its entry if that is free, else with the first free copy suffix before the extension`(
        name: String,
        isFolder: Boolean,
        taken: String?,
        expected: String,
    ) {
        // Compared in any case, as copy() does.
        val names =
            taken
                .orEmpty()
                .split(' ')
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }

        assertEquals(expected, FileOperations.copyName(name, isFolder) { it.lowercase() in names })
    }

    @Test
    fun `copies a file next to itself under a copy name, and into another folder under its own name`() {
        val file = folder.resolve("notes.txt").apply { writeText("content") }
        val target = folder.resolve("target").createDirectories()

        val beside = FileOperations.copy(file, folder)
        val again = FileOperations.copy(file, folder)
        val elsewhere = FileOperations.copy(file, target)

        assertEquals(folder.resolve("notes-copy.txt"), beside)
        assertEquals(folder.resolve("notes-copy2.txt"), again)
        assertEquals(target.resolve("notes.txt"), elsewhere)
        listOf(file, beside, again, elsewhere).forEach { assertEquals("content", it.readText()) }
    }

    @Test
    fun `a copy never replaces an entry, also one differing only in case`() {
        val file = folder.resolve("a.txt").apply { writeText("mine") }
        val target = folder.resolve("target").createDirectories()
        target.resolve("A.TXT").writeText("theirs")
        target.resolve("a-copy.txt").createSymbolicLinkPointingTo(folder.resolve("nowhere"))

        val copy = FileOperations.copy(file, target)

        assertEquals(target.resolve("a-copy2.txt"), copy)
        assertEquals("mine", copy.readText())
        assertEquals("theirs", target.resolve("A.TXT").readText())
        assertTrue(Files.isSymbolicLink(target.resolve("a-copy.txt")))
    }

    @Test
    fun `copies a folder with everything in it, links as links, keeping permissions and times`() {
        assumeTrue(Files.getFileAttributeView(folder, PosixFileAttributeView::class.java) != null)
        val outside = folder.resolve("outside").createDirectories()
        outside.resolve("keep.txt").writeText("outside")
        val src = folder.resolve("src").createDirectories()
        val script = src.resolve("bin/run.sh").also { it.parent.createDirectories() }.apply { writeText("echo") }
        script.setPosixFilePermissions(script.getPosixFilePermissions() + PosixFilePermission.OWNER_EXECUTE)
        val time = FileTime.fromMillis(1_000_000_000_000)
        script.setLastModifiedTime(time)
        src.resolve("link").createSymbolicLinkPointingTo(outside)
        // A read-only folder still gets its content.
        val locked = src.resolve("locked").createDirectories()
        locked.resolve("inner.txt").writeText("inner")
        locked.setPosixFilePermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        src.setLastModifiedTime(time)
        val target = folder.resolve("target").createDirectories()

        try {
            val copy = FileOperations.copy(src, target)

            assertEquals(target.resolve("src"), copy)
            assertEquals("echo", copy.resolve("bin/run.sh").readText())
            assertTrue(PosixFilePermission.OWNER_EXECUTE in copy.resolve("bin/run.sh").getPosixFilePermissions())
            assertEquals(time, copy.resolve("bin/run.sh").getLastModifiedTime())
            assertEquals(time, copy.getLastModifiedTime())
            assertTrue(Files.isSymbolicLink(copy.resolve("link")))
            assertEquals(outside, copy.resolve("link").readSymbolicLink())
            assertEquals(listOf("keep.txt"), outside.listDirectoryEntries().map { it.name })
            assertEquals("inner", copy.resolve("locked/inner.txt").readText())
            assertEquals(locked.getPosixFilePermissions(), copy.resolve("locked").getPosixFilePermissions())
        } finally {
            // Else the temporary folder cannot be cleaned up.
            listOf(locked, target.resolve("src/locked")).filter { it.exists() }.forEach {
                it.setPosixFilePermissions(it.getPosixFilePermissions() + PosixFilePermission.OWNER_WRITE)
            }
        }
    }

    @Test
    fun `copies a link itself, not its target`() {
        val outside = folder.resolve("outside").createDirectories()
        val link = folder.resolve("link").createSymbolicLinkPointingTo(outside)
        val target = folder.resolve("target").createDirectories()

        val copy = FileOperations.copy(link, target)

        assertTrue(Files.isSymbolicLink(copy))
        assertEquals(outside, copy.readSymbolicLink())
    }

    @Test
    fun `refuses to copy a folder into itself or a folder inside it, before copying anything`() {
        val sub = folder.resolve("sub").createDirectories()
        val deeper = sub.resolve("a/b").createDirectories()

        val itself = assertFailsWith<FileOperationException> { FileOperations.copy(sub, sub) }
        assertFailsWith<FileOperationException> { FileOperations.copy(sub, deeper) }

        assertEquals("“sub” cannot be copied into itself.", itself.message)
        assertEquals(listOf("a"), sub.listDirectoryEntries().map { it.name })
        assertEquals(emptyList(), deeper.listDirectoryEntries())
    }

    @Test
    fun `a folder reached through a link inside the entry counts as inside it, for copies and moves`() {
        val releases = folder.resolve("releases").createDirectories()
        releases.resolve("v1").createDirectories()
        val current = folder.resolve("current").createSymbolicLinkPointingTo(releases.resolve("v1"))
        // A link to a folder is an entry of its own: it may go into the folder it points to.
        val link = folder.resolve("link").createSymbolicLinkPointingTo(releases)

        val copy = assertFailsWith<FileOperationException> { FileOperations.copy(releases, current) }
        val move = assertFailsWith<FileOperationException> { FileOperations.move(releases, current) }

        assertEquals("“releases” cannot be copied into itself.", copy.message)
        assertEquals("“releases” cannot be moved into itself.", move.message)
        assertEquals(listOf("v1"), releases.listDirectoryEntries().map { it.name })
        assertEquals(emptyList(), releases.resolve("v1").listDirectoryEntries())
        assertFalse(FileOperations.isAtOrBelow(releases, link))
        assertTrue(Files.isSymbolicLink(FileOperations.copy(link, releases)))
    }

    @Test
    fun `reports a source that is gone`() {
        val gone = folder.resolve("gone.txt")

        val failure = assertFailsWith<FileOperationException> { FileOperations.copy(gone, folder) }

        assertEquals("Could not copy “gone.txt”: $gone does not exist.", failure.message)
    }
}
