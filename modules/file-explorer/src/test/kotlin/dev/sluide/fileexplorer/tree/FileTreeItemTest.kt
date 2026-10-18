package dev.sluide.fileexplorer.tree

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
}
