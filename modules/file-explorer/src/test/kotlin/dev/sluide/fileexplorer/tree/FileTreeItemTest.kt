package dev.sluide.fileexplorer.tree

import dev.sluide.fileexplorer.FolderIcons
import dev.sluide.fileicons.FileIcon
import javafx.scene.control.TreeItem
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FileTreeItemTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `files are leaves and folders are not`() {
        val dir = root.resolve("dir").createDirectories()
        val file = root.resolve("file.txt").createFile()

        assertFalse(FileTreeItem(dir).isLeaf)
        assertTrue(FileTreeItem(file).isLeaf)
    }

    @Test
    fun `children of nested folders are loaded lazily`() {
        root.resolve(".idea/libraries").createDirectories()
        val item = FileTreeItem(root)

        val idea = item.children.single()
        assertEquals(".idea", idea.value.name)
        assertEquals(listOf("libraries"), idea.children.map { it.value.name })
    }

    private fun names(item: FileTreeItem) = item.children.map { it.value.name }

    @Test
    fun `refresh adds and removes children in sort order`() {
        root.resolve("b.txt").createFile()
        root.resolve("d.txt").createFile()
        val item = FileTreeItem(root)
        assertEquals(listOf("b.txt", "d.txt"), names(item))

        root.resolve("a.txt").createFile()
        root.resolve("c").createDirectories()
        root.resolve("d.txt").deleteExisting()
        item.refresh()

        assertEquals(listOf("c", "a.txt", "b.txt"), names(item))
    }

    @Test
    fun `refresh keeps unchanged children with their expansion and loaded content`() {
        root.resolve("sub/inner.txt").createParentDirectories().createFile()
        val item = FileTreeItem(root)
        val sub = item.children.single()
        sub.isExpanded = true
        assertEquals(listOf("inner.txt"), sub.children.map { it.value.name })

        root.resolve("new.txt").createFile()
        item.refresh()

        assertSame(sub, item.children.first())
        assertTrue(sub.isExpanded)
        assertEquals(listOf("inner.txt"), sub.children.map { it.value.name })
    }

    @Test
    fun `refresh replaces an item whose file became a folder`() {
        val entry = root.resolve("entry").createFile()
        val item = FileTreeItem(root)
        val before = item.children.single()

        entry.deleteExisting()
        entry.createDirectories()
        item.refresh()

        val after = item.children.single()
        assertNotSame(before, after)
        assertFalse(after.isLeaf)
    }

    @Test
    fun `refresh of a folder that was never loaded does nothing`() {
        val loaded = mutableListOf<Path>()
        val item = FileTreeItem(root) { loaded.add(it.value) }

        item.refresh()

        assertEquals(emptyList(), loaded, "refresh must not load children")
        assertEquals(emptyList(), item.loadedFolders())
    }

    @Test
    fun `reports loaded folders, also nested ones, and lists them parents first`() {
        root.resolve("sub/deeper").createDirectories()
        val loaded = mutableListOf<String>()
        val item = FileTreeItem(root) { loaded.add(it.value.name) }

        val sub = item.children.single()
        sub.children.single()

        assertEquals(listOf(root.name, "sub"), loaded)
        assertEquals(listOf(item, sub), item.loadedFolders())
    }

    @Test
    fun `forgets whether changed files are readable, all of them when the folder itself changed`() {
        val a = root.resolve("a.txt").createFile()
        root.resolve("b.txt").createFile()
        root.resolve("sub").createDirectories()
        val item = FileTreeItem(root)
        val children = item.children.filterIsInstance<FileTreeItem>()

        fun readability() = children.associate { it.value.name to it.readable.value }

        fun checkAll() = children.forEach { it.readable.value = true }

        checkAll()
        item.forgetReadability(listOf(a))
        assertEquals(mapOf("sub" to true, "a.txt" to null, "b.txt" to true), readability())

        checkAll()
        item.forgetReadability(listOf(root))
        assertEquals(mapOf("sub" to true, "a.txt" to null, "b.txt" to null), readability())
    }

    private val icons = FolderIcons(FileIcon.SCRATCHES, FileIcon.SCRATCH_FOLDER, FileIcon.SCRATCH_FOLDER_OPEN)

    /** Puts [top] below a hidden root, like the explorer's top-level folders. */
    private fun topLevel(
        top: FileTreeItem,
        hidden: TreeItem<Path> = TreeItem(),
    ): FileTreeItem = top.also { hidden.children.add(it) }

    @Test
    fun `children inherit the folder icons but not the label`() {
        root.resolve("sub/deeper").createDirectories()
        val top = FileTreeItem(root, label = "Extra", folderIcons = icons)

        val sub = top.children.single() as FileTreeItem
        val deeper = sub.children.single() as FileTreeItem

        assertEquals(icons, sub.folderIcons)
        assertEquals(icons, deeper.folderIcons)
        assertNull(sub.label)
        assertEquals("Extra", top.label)
    }

    @Test
    fun `a root with folder icons shows the top icon, collapsed or expanded, and its folders the closed or open one`() {
        root.resolve("sub").createDirectories()
        root.resolve("Main.kt").createFile()
        val top = topLevel(FileTreeItem(root, label = "Extra", folderIcons = icons))
        val sub = top.children.first() as FileTreeItem
        val file = top.children.last() as FileTreeItem

        assertEquals(FileIcon.SCRATCHES, top.fileIcon { true })
        top.isExpanded = true
        assertEquals(FileIcon.SCRATCHES, top.fileIcon { true })
        assertEquals(FileIcon.SCRATCH_FOLDER, sub.fileIcon { true })
        sub.isExpanded = true
        assertEquals(FileIcon.SCRATCH_FOLDER_OPEN, sub.fileIcon { true })
        assertEquals(FileIcon.KOTLIN, file.fileIcon { true }, "files keep the icon of their type")
    }

    @Test
    fun `folders without folder icons, also beside a root with them, keep the normal ones`() {
        root.resolve("pdir").createDirectories()
        val other = root.resolve("other").createDirectories()
        val hidden = TreeItem<Path>()
        val project = topLevel(FileTreeItem(root), hidden)
        val extra = topLevel(FileTreeItem(other, label = "Extra", folderIcons = icons), hidden)
        val pdir = project.children.first() as FileTreeItem

        assertEquals(FileIcon.FOLDER, project.fileIcon { true })
        project.isExpanded = true
        assertEquals(FileIcon.FOLDER_OPEN, project.fileIcon { true })
        assertEquals(FileIcon.FOLDER, pdir.fileIcon { true })
        pdir.isExpanded = true
        assertEquals(FileIcon.FOLDER_OPEN, pdir.fileIcon { true })
        assertEquals(FileIcon.SCRATCHES, extra.fileIcon { true })
    }

    @Test
    fun `the readability of a file of unknown type is asked once and kept`() {
        val item = FileTreeItem(root.resolve("data").createFile())
        var asked = 0

        assertEquals(FileIcon.BINARY, item.fileIcon { asked++ < 0 })
        assertEquals(FileIcon.BINARY, item.fileIcon { asked++ < 0 })

        assertEquals(1, asked)
    }
}
