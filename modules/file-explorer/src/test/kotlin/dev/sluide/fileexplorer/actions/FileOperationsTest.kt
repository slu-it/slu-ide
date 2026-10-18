package dev.sluide.fileexplorer.actions

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
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
}
