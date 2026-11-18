package dev.sluide.fileexplorer.tree

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectoryListingTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `includes hidden files and folders`() {
        root.resolve(".hidden-file").createFile()
        root.resolve(".hidden-dir").createDirectories()
        root.resolve("visible.txt").createFile()

        assertEquals(
            listOf(".hidden-dir", ".hidden-file", "visible.txt"),
            listDirectory(root).map { it.name },
        )
    }

    @Test
    fun `leaves out the git folder and file but not other git dot-files`() {
        root.resolve(".git").createDirectories()
        root.resolve(".github").createDirectories()
        root.resolve(".gitignore").createFile()
        val submodule = root.resolve("submodule").createDirectories()
        submodule.resolve(".git").createFile()
        submodule.resolve("README.md").createFile()

        assertEquals(listOf(".github", "submodule", ".gitignore"), listDirectory(root).map { it.name })
        assertEquals(listOf("README.md"), listDirectory(submodule).map { it.name })
    }

    @Test
    fun `lists folders before files and sorts by name ignoring case`() {
        root.resolve("b.txt").createFile()
        root.resolve("A.txt").createFile()
        root.resolve("zeta").createDirectories()
        root.resolve("Alpha").createDirectories()

        assertEquals(listOf("Alpha", "zeta", "A.txt", "b.txt"), listDirectory(root).map { it.name })
    }

    @Test
    fun `names that differ only in case are listed in a fixed order, upper case first`() {
        root.resolve("a.txt").createFile()
        val upper = root.resolve("A.txt")
        // On a case-insensitive file system (the macOS default) this is the same file: nothing to order then.
        assumeTrue(!Files.exists(upper))
        upper.createFile()
        root.resolve("b").createDirectories()
        root.resolve("B").createDirectories()

        assertEquals(listOf("B", "b", "A.txt", "a.txt"), listDirectory(root).map { it.name })
    }

    @Test
    fun `lists only direct children`() {
        root.resolve("outer/inner").createDirectories()
        root.resolve("outer/inner/deep.txt").createFile()

        assertEquals(listOf("outer"), listDirectory(root).map { it.name })
    }

    @Test
    fun `returns empty list for missing directory and for a file`() {
        assertTrue(listDirectory(root.resolve("missing")).isEmpty())
        assertTrue(listDirectory(root.resolve("f.txt").createFile()).isEmpty())
    }

    @Test
    fun `returns empty list for unreadable directory`() {
        val locked = root.resolve("locked").createDirectories()
        val file = locked.toFile()
        // Permissions are not enforced for root or on some file systems; skip then.
        if (!file.setReadable(false) || Files.isReadable(locked)) return
        try {
            assertTrue(listDirectory(locked).isEmpty())
        } finally {
            file.setReadable(true)
        }
    }
}
