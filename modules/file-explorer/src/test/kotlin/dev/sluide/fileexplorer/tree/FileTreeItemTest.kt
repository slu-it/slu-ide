package dev.sluide.fileexplorer.tree

import dev.sluide.fileexplorer.FolderIcons
import dev.sluide.fileicons.FileIcon
import javafx.scene.control.TreeItem
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
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

    // Compact Middle Folders (ADR 0096)

    private var compacting = true

    /** A top-level item of [root] compacting while [compacting] is set, below a hidden root. */
    private fun compactTop(onChildrenAdded: (FileTreeItem) -> Unit = {}) =
        topLevel(FileTreeItem(root, compact = { compacting }, onChildrenAdded = onChildrenAdded))

    private fun FileTreeItem.rows() = children.map { (it as FileTreeItem).displayName }

    private fun FileTreeItem.row(name: String) =
        children.first { (it as FileTreeItem).displayName == name } as FileTreeItem

    @Test
    fun `a chain of folders holding only a folder is one row, the top-level folder is never part of it`() {
        root.resolve("src/main/kotlin/App.kt").createParentDirectories().createFile()
        val top = compactTop()

        val row = top.children.single() as FileTreeItem

        assertEquals("src/main/kotlin", row.displayName)
        assertEquals(root.resolve("src/main/kotlin"), row.value)
        assertEquals(listOf("src", "src/main", "src/main/kotlin").map(root::resolve), row.chain)
        assertEquals(root.resolve("src"), row.sourcePath)
        assertTrue(row.isCompacted && row.represents(root.resolve("src/main")))
        assertEquals(listOf("App.kt"), row.rows())
        assertEquals(listOf(root), top.chain)
    }

    @Test
    fun `a chain ends at a link, a nested repository, an unreadable folder, a second entry and the depth cap`() {
        val elsewhere = root.resolve("elsewhere/inner").createDirectories()
        // A link as the only entry, and a link that would start a chain.
        root.resolve("a").createDirectories()
        Files.createSymbolicLink(root.resolve("a/link"), elsewhere)
        Files.createSymbolicLink(root.resolve("linked"), root.resolve("elsewhere"))
        // A nested repository: a .git beside the only folder, or alone.
        root.resolve("b/.git").createDirectories()
        root.resolve("b/inner").createDirectories()
        root.resolve("c/.git").createDirectories()
        // Two entries.
        root.resolve("d/inner").createDirectories()
        root.resolve("d/note.txt").createFile()
        val deep = (1..MAX_CHAIN + 5).fold(root.resolve("e")) { folder, n -> folder.resolve("$n") }.createDirectories()

        assertEquals(listOf(root.resolve("a")), compactChain(root.resolve("a")))
        assertEquals(listOf(root.resolve("linked")), compactChain(root.resolve("linked")))
        assertEquals(listOf(root.resolve("b")), compactChain(root.resolve("b")))
        assertEquals(listOf(root.resolve("c")), compactChain(root.resolve("c")))
        assertEquals(listOf(root.resolve("d")), compactChain(root.resolve("d")))
        assertEquals(listOf(root.resolve("x.txt")), compactChain(root.resolve("x.txt").createFile()))
        assertEquals(MAX_CHAIN, compactChain(root.resolve("e")).size)
        assertTrue(deep.startsWith(compactChain(root.resolve("e")).last()))
    }

    @Test
    fun `an unreadable folder ends the chain and is its last folder`() {
        val locked = root.resolve("f/locked").createDirectories()
        locked.resolve("inner").createDirectories()
        assumeTrue(
            locked.toFile().setReadable(false) && !Files.isReadable(locked),
            "needs a folder that cannot be read",
        )
        try {
            assertEquals(listOf(root.resolve("f"), locked), compactChain(root.resolve("f")))
        } finally {
            locked.toFile().setReadable(true)
        }
    }

    @Test
    fun `without compacting every folder is a row of its own`() {
        root.resolve("src/main/kotlin").createDirectories()
        compacting = false

        val src = compactTop().children.single() as FileTreeItem

        assertEquals("src", src.displayName)
        assertEquals(listOf(root.resolve("src")), src.chain)
        assertEquals(listOf("main"), src.rows())
    }

    @Test
    fun `refresh splits a row when a middle folder gets an entry and joins it again when it is gone`() {
        root.resolve("src/main/kotlin/App.kt").createParentDirectories().createFile()
        val added = mutableListOf<FileTreeItem>()
        val top = compactTop { added += it }
        val compacted = top.row("src/main/kotlin")
        compacted.isExpanded = true
        compacted.children

        val readme = root.resolve("src/main/README.md").createFile()
        assertTrue(compacted.isChainStale())
        top.refresh()

        val split = top.row("src/main")
        assertNotSame(compacted, split)
        assertTrue(split.isExpanded, "the deepest folder was expanded, so the folders down to it are")
        assertEquals(listOf("kotlin", "README.md"), split.rows())
        assertTrue(split.row("kotlin").isExpanded)
        assertEquals(listOf("App.kt"), split.row("kotlin").rows())
        assertEquals(listOf(top), added)

        readme.deleteExisting()
        top.refresh()

        val joined = top.row("src/main/kotlin")
        assertTrue(joined.isExpanded)
        assertEquals(listOf("App.kt"), joined.rows())
    }

    @Test
    fun `refresh keeps a row whose chain is unchanged, and a collapsed split stays collapsed`() {
        root.resolve("src/main/kotlin").createDirectories()
        val top = compactTop()
        val row = top.row("src/main/kotlin")

        root.resolve("other.txt").createFile()
        top.refresh()
        assertSame(row, top.row("src/main/kotlin"))
        assertFalse(row.isChainStale())

        root.resolve("src/note.txt").createFile()
        top.refresh()
        assertFalse(top.row("src").isExpanded)
    }

    @Test
    fun `refreshTree rebuilds the whole tree when compacting is turned on or off, keeping what was expanded`() {
        root.resolve("one/src/main/kotlin/App.kt").createParentDirectories().createFile()
        root.resolve("one/two.txt").createFile()
        compacting = false
        val top = compactTop()
        val one = top.row("one").apply { isExpanded = true }
        one.row("src").isExpanded = true
        one.row("src").row("main").isExpanded = true
        one
            .row("src")
            .row("main")
            .row("kotlin")
            .isExpanded = true

        compacting = true
        top.refreshTree()

        assertSame(one, top.row("one"), "its chain stays the same")
        val compacted = one.row("src/main/kotlin")
        assertTrue(compacted.isExpanded)
        assertEquals(listOf("App.kt"), compacted.rows())

        compacting = false
        top.refreshTree()

        assertEquals(listOf("src", "two.txt"), one.rows())
        val kotlin = one.row("src").row("main").row("kotlin")
        assertTrue(one.row("src").isExpanded && one.row("src").row("main").isExpanded && kotlin.isExpanded)
    }
}
