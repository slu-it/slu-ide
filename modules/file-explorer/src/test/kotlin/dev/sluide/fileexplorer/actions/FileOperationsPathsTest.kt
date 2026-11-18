package dev.sluide.fileexplorer.actions

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The checks of names and paths, creating along paths, renaming and moving: the cases besides [FileOperationsTest]. */
class FileOperationsPathsTest {
    @TempDir
    lateinit var folder: Path

    // A lone surrogate has no encoding in any file system's charset: Path.resolve fails (checked in jshell on Linux).
    private val unstorable = "a\uD800b"

    private fun assumeUnstorable() {
        assumeTrue(runCatching { folder.resolve(unstorable) }.isFailure, "this file system stores any name")
    }

    @Test
    fun `a name the file system cannot store is refused, as a name and as a part of a path`() {
        assumeUnstorable()
        val message = "The name contains characters the file system cannot store."

        assertEquals(message, FileOperations.nameProblem(unstorable, folder))
        assertEquals(message, FileOperations.nameProblem(unstorable, folder, renaming = folder.resolve("x")))
        assertEquals(message, FileOperations.pathProblem(unstorable, folder, windows = false))
        assertEquals(
            "“$unstorable” contains characters the file system cannot store.",
            FileOperations.pathProblem("ok/$unstorable/x", folder, windows = false),
        )
        assertEquals(
            "“$unstorable” contains characters the file system cannot store.",
            FileOperations.pathProblem("$unstorable/x", folder, windows = false),
        )
    }

    @Test
    fun `a single name is judged as a name, so an empty one or one with spaces gets the message for names`() {
        assertEquals("Enter a name.", FileOperations.pathProblem("", folder, windows = false))
        assertEquals(
            "The name must not start or end with a space.",
            FileOperations.pathProblem(" a", folder, windows = false),
        )
        assertEquals(
            "The name must not start or end with a space.",
            FileOperations.pathProblem("a ", folder, windows = false),
        )
    }

    @Test
    fun `a colon in a part after the first is refused on Windows only`() {
        val message = "“C:”: The name must not contain :."

        assertEquals(message, FileOperations.pathProblem("a/C:/x", folder, windows = true))
        assertEquals(message, FileOperations.pathProblem("a\\C:\\x", folder, windows = true))
        assertEquals("“b:c”: The name must not contain :.", FileOperations.pathProblem("a/b:c", folder, windows = true))
        assertNull(FileOperations.pathProblem("a/C:/x", folder, windows = false))
    }

    @Test
    fun `a dangling link on the way is no folder and as the last part it is taken`() {
        folder.resolve("dangling").createSymbolicLinkPointingTo(folder.resolve("missing"))

        assertEquals(
            "“dangling” exists and is not a folder.",
            FileOperations.pathProblem("dangling/x", folder, windows = false),
        )
        assertEquals(
            "“dangling” already exists in this folder.",
            FileOperations.pathProblem("dangling/", folder, windows = false),
        )
    }

    @Test
    fun `a path in a new folder is free, whatever the last part is called`() {
        folder
            .resolve("a")
            .createDirectories()
            .resolve("taken.txt")
            .createFile()
        folder.resolve("taken.txt").createFile()

        assertNull(FileOperations.pathProblem("new/taken.txt", folder, windows = false))
        assertEquals(
            "“a/taken.txt” already exists in this folder.",
            FileOperations.pathProblem("a/taken.txt", folder, windows = false),
        )
    }

    @Test
    fun `a backslash separates the parts of a path to create on Windows only`() {
        val file = FileOperations.createPath(folder, "a\\b.txt", isFolder = false, windows = true)

        assertEquals(folder.resolve("a/b.txt"), file)
        assertTrue(file.isRegularFile())
        val refused =
            assertFailsWith<FileOperationException> {
                FileOperations.createPath(folder, "c\\d.txt", isFolder = false, windows = false)
            }
        assertEquals("The name must not contain / or \\.", refused.message)
        assertEquals(listOf("a"), folder.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `the parents of a path to create are made, with a backslash on Windows only, and the last part is left out`() {
        val file = FileOperations.createParents(folder, "a\\b\\c.txt", windows = true)

        assertEquals(folder.resolve("a/b/c.txt"), file)
        assertTrue(folder.resolve("a/b").isDirectory())
        assertEquals(emptyList(), folder.resolve("a/b").listDirectoryEntries())
        val refused = assertFailsWith<FileOperationException> { FileOperations.createParents(folder, "d\\e", false) }
        assertEquals("The name must not contain / or \\.", refused.message)
        assertTrue(!folder.resolve("d").exists())
    }

    @Test
    fun `the parents of a path that is refused are not made`() {
        folder.resolve("taken").createFile()

        val dots = assertFailsWith<FileOperationException> { FileOperations.createParents(folder, "x/../y", false) }
        val taken = assertFailsWith<FileOperationException> { FileOperations.createParents(folder, "taken", false) }

        assertEquals("“..” cannot be used as a name.", dots.message)
        assertEquals("“taken” already exists in this folder.", taken.message)
        assertTrue(!folder.resolve("x").exists())
    }

    @Test
    fun `a folder that differs from an existing one only in case is created as typed where the case matters`() {
        folder.resolve("SRC").createDirectories()
        // On a case-insensitive file system src is SRC, which the other tests cover: nothing to see here.
        assumeTrue(!folder.resolve("src").exists())

        val file = FileOperations.createPath(folder, "src/x.txt", isFolder = false, windows = false)
        val parents = FileOperations.createParents(folder, "src/y/z.txt", windows = false)

        assertEquals(folder.resolve("src/x.txt"), file)
        assertEquals(folder.resolve("src/y/z.txt"), parents)
        assertEquals(emptyList(), folder.resolve("SRC").listDirectoryEntries())
    }

    @Test
    fun `an entry without a folder cannot be renamed or moved`() {
        val lonely = Path.of("lonely")

        val rename = assertFailsWith<IllegalStateException> { FileOperations.rename(lonely, "other") }
        val move = assertFailsWith<IllegalStateException> { FileOperations.move(lonely, folder) }

        assertEquals("lonely has no folder", rename.message)
        assertEquals("lonely has no folder", move.message)
    }

    @Test
    fun `renaming an entry to its own name changes nothing, also if it is gone, another name says it is gone`() {
        val gone = folder.resolve("gone.txt")

        assertEquals(gone, FileOperations.rename(gone, "gone.txt"))

        val failure = assertFailsWith<FileOperationException> { FileOperations.rename(gone, "other.txt") }
        assertEquals("Could not rename “gone.txt”: $gone does not exist.", failure.message)
    }

    @Test
    fun `moving into a root folder names it by its path in the message`() {
        // /tmp exists in every Unix root; a folder named like it is taken there.
        assumeTrue(Files.isDirectory(Path.of("/tmp")))
        val tmp = folder.resolve("tmp").createDirectories()

        val failure = assertFailsWith<FileOperationException> { FileOperations.move(tmp, Path.of("/")) }

        assertEquals("“tmp” already exists in “/”.", failure.message)
        assertTrue(tmp.isDirectory())
    }

    @Test
    fun `a rename over a temporary name goes over a name in the same folder that is new each time`() {
        val file = folder.resolve("a.txt").apply { writeText("content") }
        val steps = mutableListOf<Pair<Path, Path>>()
        val record: (Path, Path) -> Unit = { from, to ->
            steps += from to to
            Files.move(from, to)
        }

        assertEquals(folder.resolve("A.txt"), FileOperations.renameOverTemporary(file, folder.resolve("A.txt"), record))
        val back = FileOperations.renameOverTemporary(folder.resolve("A.txt"), file, record)

        assertEquals(file, back)
        assertEquals("content", file.readText())
        assertEquals(listOf("a.txt"), folder.listDirectoryEntries().map { it.name })
        val temporaries = steps.filter { it.first.parent == folder && it.first.name.startsWith(".") }.map { it.first }
        val pattern = Regex("\\.[aA]\\.txt\\.[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.renaming")
        assertEquals(2, temporaries.size)
        assertTrue(temporaries.all { pattern.matches(it.name) }, temporaries.toString())
        assertNotEquals(temporaries[0].name.substring(7), temporaries[1].name.substring(7))
        assertEquals(listOf(folder.resolve("A.txt"), file), steps.filter { it.second.name[0] != '.' }.map { it.second })
    }

    @Test
    fun `a rename over a temporary name moves by itself with Files move when no mover is given`() {
        val file = folder.resolve("a.txt").apply { writeText("content") }

        val result = FileOperations.renameOverTemporary(file, folder.resolve("A.txt"))

        assertEquals(folder.resolve("A.txt"), result)
        assertEquals(listOf("A.txt"), folder.listDirectoryEntries().map { it.name })
        assertEquals("content", result.readText())
    }

    @Test
    fun `the message of a rename that cannot be undone gives the reason of each kind of failure`() {
        val cases: List<Pair<IOException, String>> =
            listOf(
                NoSuchFileException("x") to "x does not exist.",
                FileAlreadyExistsException("x") to "x already exists.",
                AccessDeniedException("x") to "permission denied for x.",
                FileSystemException("x", null, "busy") to "x: busy",
                FileSystemException("x") to "x",
                FileSystemException(null, null, "busy") to "busy",
                IOException("plain") to "plain",
                IOException() to "IOException",
            )
        cases.forEachIndexed { index, (failure, reason) ->
            val sub = folder.resolve("case$index").createDirectories()
            val file = sub.resolve("a.txt").apply { writeText("content") }
            var calls = 0
            val mover: (Path, Path) -> Unit = { from, to ->
                calls++
                if (calls == 2) throw failure
                if (calls == 3) throw IOException("cannot move back")
                Files.move(from, to)
            }

            val thrown =
                assertFailsWith<FileOperationException> {
                    FileOperations.renameOverTemporary(file, sub.resolve("A.txt"), mover)
                }

            val temporary = sub.listDirectoryEntries().single()
            assertEquals("Could not rename “a.txt”, and it was left as $temporary: $reason", thrown.message)
        }
    }
}
