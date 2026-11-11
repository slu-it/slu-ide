package dev.sluide.fileexplorer.tree

import dev.sluide.fileexplorer.generated.GeneratedFolderFilter
import javafx.scene.control.TreeItem
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    @Test
    fun `expanding to a new nested path loads the new folders and expands them`() {
        val top = FileTreeItem(project)
        top.children
        val file = project.resolve("a/b/c.txt").also { it.parent.createDirectories() }.createFile()

        val item = assertNotNull(expandTo(top, file))

        assertEquals(file, item.value)
        val a = top.children.single()
        assertTrue(top.isExpanded && a.isExpanded && a.children.single().isExpanded)
        assertNull(expandTo(top, outside.resolve("x")))
    }

    // Compact Middle Folders (ADR 0096)

    /** A compacting top-level item of [folder] below a hidden root. */
    private fun compactTop(folder: Path) =
        FileTreeItem(folder, compact = { true }).also { TreeItem<Path>().children.add(it) }

    @Test
    fun `a file below a compacted row is found through it, a middle folder is found as the row`() {
        val file = project.resolve("src/main/kotlin/App.kt").also { it.parent.createDirectories() }.createFile()
        val top = compactTop(project)

        val (folders, item) = assertNotNull(findForReveal(listOf(top), file))
        val row = folders.last()
        assertEquals(listOf(project, project.resolve("src/main/kotlin")), folders.map { it.value })
        assertEquals(file, item.value)

        val (middleFolders, middle) = assertNotNull(findForReveal(listOf(top), project.resolve("src/main")))
        assertEquals(listOf(top), middleFolders)
        assertSame(row, middle)
        assertSame(row, findForReveal(listOf(top), project.resolve("src/main/kotlin"))?.second)
    }

    @Test
    fun `a row that no longer stands for its folders is read again on the way, split or joined`() {
        val app = project.resolve("src/main/kotlin/App.kt").also { it.parent.createDirectories() }.createFile()
        val top = compactTop(project)
        assertEquals(project.resolve("src/main/kotlin"), top.children.single().value)
        val readme = project.resolve("src/main/README.md").createFile()

        val (folders, item) = assertNotNull(findForReveal(listOf(top), readme))
        assertEquals(listOf(project, project.resolve("src/main")), folders.map { it.value })
        assertEquals(readme, item.value)

        readme.deleteExisting()
        val (joined, _) = assertNotNull(findForReveal(listOf(top), app))
        assertEquals(listOf(project, project.resolve("src/main/kotlin")), joined.map { it.value })
    }

    @Test
    fun `expanding to a path created in an empty folder takes the compacted row the folder now belongs to`() {
        val empty = project.resolve("empty").createDirectories()
        project.resolve("other.txt").createFile()
        val top = compactTop(project)
        val emptyRow = top.children.first() as FileTreeItem
        val file = empty.resolve("a/b/c.txt").also { it.parent.createDirectories() }.createFile()

        val item = assertNotNull(expandTo(emptyRow, file))

        assertEquals(file, item.value)
        val row = item.parent as FileTreeItem
        assertEquals("empty/a/b", row.displayName)
        assertSame(top, row.parent)
        assertTrue(row.isExpanded)
    }

    @Test
    fun `a file in a hidden generated folder is found by unhiding the folder`() {
        project.resolve("package.json").createFile()
        val file = project.resolve("node_modules/x/index.js").also { it.parent.createDirectories() }.createFile()
        val venvFile = project.resolve(".venv/lib/site.py").also { it.parent.createDirectories() }.createFile()
        project.resolve(".venv/pyvenv.cfg").createFile()
        val filter = GeneratedFolderFilter { true }
        val top = FileTreeItem(project, generated = filter)
        assertEquals(listOf("package.json"), top.children.map { it.value.fileName.toString() })
        val revealed = mutableListOf<Path>()
        val unhide: (Path) -> Unit = {
            revealed.add(it)
            filter.unhide(it)
        }

        assertNull(findForReveal(listOf(top), file))
        val (_, item) = assertNotNull(findForReveal(listOf(top), file, unhide))
        val (_, venvItem) = assertNotNull(findForReveal(listOf(top), venvFile, unhide))

        assertEquals(file, item.value)
        assertEquals(venvFile, venvItem.value)
        assertEquals(listOf(project.resolve("node_modules"), project.resolve(".venv")), revealed)
    }

    @Test
    fun `a hidden generated file as the target itself is unhidden and found`() {
        val file = project.resolve("src/.DS_Store").also { it.parent.createDirectories() }.createFile()
        val filter = GeneratedFolderFilter { true }
        val top = FileTreeItem(project, generated = filter)

        assertNull(findForReveal(listOf(top), file))
        val (_, item) = assertNotNull(findForReveal(listOf(top), file, filter::unhide))

        assertEquals(file, item.value)
    }

    @Test
    fun `a hidden generated file in a middle folder of a compacted row splits the row, and hiding joins it again`() {
        val folder = project.resolve("a/b").createDirectories()
        folder.resolve("x.txt").createFile()
        val file = project.resolve("a/.DS_Store").createFile()
        val filter = GeneratedFolderFilter { true }
        val top = FileTreeItem(project, compact = { true }, generated = filter)
        assertEquals(listOf(project.resolve("a"), folder), (top.children.single() as FileTreeItem).chain)

        val (folders, item) = assertNotNull(findForReveal(listOf(top), file, filter::unhide))

        assertEquals(file, item.value)
        assertEquals(listOf(top, top.children.single()), folders)
        assertEquals(listOf(project.resolve("a")), (top.children.single() as FileTreeItem).chain)

        top.refreshParentsOf(filter.clearRevealed())

        assertEquals(listOf(project.resolve("a"), folder), (top.children.single() as FileTreeItem).chain)
    }

    @Test
    fun `a revealed folder keeps its row when the parent is read again`() {
        project.resolve("package.json").createFile()
        val file = project.resolve("node_modules/x/index.js").also { it.parent.createDirectories() }.createFile()
        val filter = GeneratedFolderFilter { true }
        val top = FileTreeItem(project, generated = filter)

        findForReveal(listOf(top), file, filter::unhide)
        top.refresh()

        assertEquals(listOf("node_modules", "package.json"), top.children.map { it.value.fileName.toString() })
        assertNotNull((top.children.first() as FileTreeItem).generatedReason)
    }

    @Test
    fun `expanding to a file in a hidden folder unhides the folder`() {
        project.resolve("package.json").createFile()
        val file = project.resolve("node_modules/x/index.js").also { it.parent.createDirectories() }.createFile()
        val filter = GeneratedFolderFilter { true }
        val top = FileTreeItem(project, generated = filter)

        val item = assertNotNull(expandTo(top, file, filter::unhide))

        assertEquals(file, item.value)
        assertTrue(top.children.any { it.value.fileName.toString() == "node_modules" })
    }

    @Test
    fun `a path with no generated folder on the way is not unhidden, e g below the git folder`() {
        project.resolve("package.json").createFile()
        val config = project.resolve(".git/config").also { it.parent.createDirectories() }.createFile()
        val revealed = mutableListOf<Path>()

        assertNull(
            findForReveal(
                listOf(
                    FileTreeItem(
                        project,
                        generated =
                            GeneratedFolderFilter {
                                true
                            },
                    ),
                ),
                config,
            ) { revealed.add(it) },
        )

        assertTrue(revealed.isEmpty())
    }
}
