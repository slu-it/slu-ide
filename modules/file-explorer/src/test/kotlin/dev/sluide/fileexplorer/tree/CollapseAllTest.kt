package dev.sluide.fileexplorer.tree

import javafx.scene.control.TreeItem
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Collapsing the explorer's tree and moving the selection off hidden rows; no display needed. */
class CollapseAllTest {
    @TempDir
    lateinit var project: Path

    @TempDir
    lateinit var scratches: Path

    private fun folder(
        top: FileTreeItem,
        vararg names: String,
    ): FileTreeItem {
        var item = top
        for (name in names) {
            item.isExpanded = true
            item = item.children.filterIsInstance<FileTreeItem>().first { it.value.fileName.toString() == name }
        }
        return item
    }

    @Test
    fun `the project stays expanded, nested folders and extra roots collapse`() {
        project.resolve("a/b").createDirectories()
        scratches.resolve("s").createDirectories()
        val proj = FileTreeItem(project)
        val extra = FileTreeItem(scratches)
        proj.isExpanded = true
        extra.isExpanded = true
        val a = folder(proj, "a")
        val b = folder(proj, "a", "b")
        b.isExpanded = true
        b.children
        val s = folder(extra, "s")
        s.isExpanded = true
        s.children

        collapseAll(listOf(proj, extra), proj, emptyList())

        assertTrue(proj.isExpanded)
        assertFalse(a.isExpanded)
        assertFalse(b.isExpanded)
        assertFalse(extra.isExpanded)
        assertFalse(s.isExpanded)
    }

    @Test
    fun `a hidden selected row becomes its nearest ancestor still shown`() {
        project.resolve("a/b").createDirectories()
        val file = project.resolve("a/b/f.txt").createFile()
        val proj = FileTreeItem(project)
        proj.isExpanded = true
        val a = folder(proj, "a")
        val b = folder(proj, "a", "b")
        b.isExpanded = true
        val f = b.children.first { it.value == file }

        val rows = collapseAll(listOf(proj), proj, listOf(f))

        assertEquals(listOf<TreeItem<Path>>(a), rows)
    }

    @Test
    fun `a selected row that stays shown stays, duplicates merge`() {
        project.resolve("a").createDirectories()
        val file = project.resolve("a/f.txt").createFile()
        val top = project.resolve("t.txt").createFile()
        val proj = FileTreeItem(project)
        proj.isExpanded = true
        val a = folder(proj, "a")
        a.isExpanded = true
        val f = a.children.first { it.value == file }
        val t = proj.children.first { it.value == top }

        val rows = collapseAll(listOf(proj), proj, listOf(t, f, a))

        assertEquals(2, rows.size)
        assertSame(t, rows[0])
        assertSame(a, rows[1])
    }

    @Test
    fun `a selected extra root stays selected when it collapses`() {
        val proj = FileTreeItem(project)
        val extra = FileTreeItem(scratches)
        extra.isExpanded = true
        extra.children

        val rows = collapseAll(listOf(proj, extra), proj, listOf(extra))

        assertEquals(listOf<TreeItem<Path>>(extra), rows)
        assertFalse(extra.isExpanded)
    }
}
