package dev.sluide.fileexplorer

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        root.resolve(".git/objects").createDirectories()
        val item = FileTreeItem(root)

        val git = item.children.single()
        assertEquals(".git", git.value.name)
        assertEquals(listOf("objects"), git.children.map { it.value.name })
    }
}
