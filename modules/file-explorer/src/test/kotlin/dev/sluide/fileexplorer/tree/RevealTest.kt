package dev.sluide.fileexplorer.tree

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Finding the item of a path for the explorer's reveal; no display needed. */
class RevealTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var scratches: Path

    @TempDir
    lateinit var outside: Path

    private fun tops(vararg folders: Path) = folders.map { FileTreeItem(it) }

    @Test
    fun `a nested file is found with the folders down to it, nothing is expanded`() {
        val file = scratches.resolve("one/two/f.txt").also { it.parent.createDirectories() }.also { it.createFile() }
        val tops = tops(project, scratches)

        val (folders, item) = assertNotNull(findForReveal(tops, file))

        assertEquals(
            listOf(scratches, scratches.resolve("one"), scratches.resolve("one/two")),
            folders.map { it.value },
        )
        assertEquals(file, item.value)
        assertFalse(folders.any { it.isExpanded })
    }

    @Test
    fun `a file created just before, in a loaded folder, is found by reading the folder again`() {
        val tops = tops(scratches)
        tops.single().children
        val file = scratches.resolve("fresh.txt").createFile()

        val (_, item) = assertNotNull(findForReveal(tops, file))

        assertEquals(file, item.value)
    }

    @Test
    fun `a top-level folder is found itself, without folders`() {
        val tops = tops(project, scratches)

        val (folders, item) = assertNotNull(findForReveal(tops, scratches))

        assertEquals(emptyList(), folders)
        assertSame(tops[1], item)
    }

    @Test
    fun `a path outside the roots or missing is not found`() {
        val tops = tops(project, scratches)

        assertNull(findForReveal(tops, outside.resolve("x.txt").createFile()))
        assertNull(findForReveal(tops, scratches.resolve("missing.txt")))
        assertNull(findForReveal(tops, scratches.resolve("missing/deeper.txt")))
    }

    @Test
    fun `the deepest top-level folder holding the path is used`() {
        val inner = project.resolve("inner").createDirectories()
        val file = inner.resolve("f.txt").createFile()
        val tops = tops(project, inner)

        val (folders, item) = assertNotNull(findForReveal(tops, file))

        assertEquals(listOf(tops[1]), folders)
        assertSame(tops[1], item.parent)
    }

    @Test
    fun `a path with dots is normalised`() {
        val file = scratches.resolve("sub/f.txt").also { it.parent.createDirectories() }.also { it.createFile() }

        val found = findForReveal(tops(scratches), scratches.resolve("sub/../sub/./f.txt"))

        assertEquals(file, found?.second?.value)
    }

    @Test
    fun `a symbolic link is walked through`() {
        val real = outside.resolve("real").createDirectories()
        real.resolve("f.txt").createFile()
        val link = scratches.resolve("link")
        try {
            Files.createSymbolicLink(link, real)
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "no symbolic links: $e")
        } catch (e: IOException) {
            assumeTrue(false, "no symbolic links: $e")
        }

        val found = findForReveal(tops(scratches), link.resolve("f.txt"))

        assertEquals(link.resolve("f.txt"), found?.second?.value)
    }

    @Test
    fun `nothing is found below a root that was no folder when it was opened`() {
        val later = scratches.resolve("later")
        val tops = tops(later)
        later.createDirectories()
        val file = later.resolve("f.txt").createFile()

        assertNull(findForReveal(tops, file))
    }
}
