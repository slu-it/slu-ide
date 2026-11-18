package dev.sluide.fileexplorer.tree

import dev.sluide.fileexplorer.FolderIcons
import dev.sluide.fileexplorer.generated.GeneratedFolderFilter
import dev.sluide.fileexplorer.generated.GeneratedReason
import dev.sluide.fileicons.FileIcon
import dev.sluide.fileicons.fileIconFor
import dev.sluide.librarycode.LibraryKind
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TreeItemIconTest {
    @TempDir
    lateinit var root: Path

    private fun child(
        top: FileTreeItem,
        name: String,
    ) = top.children.single { it.value.name == name } as FileTreeItem

    private val icons = FolderIcons(FileIcon.TEXT, FileIcon.BINARY, FileIcon.GENERATED_FILE)

    @Test
    fun `a library root shows the locked folder, open or closed, only while the setting is on`() {
        root.resolve("venv").createDirectories()
        val top =
            FileTreeItem(root, libraryRoots = { if (it.name == "venv") LibraryKind.VIRTUAL_ENV else null })
        val venv = child(top, "venv")

        assertEquals(FileIcon.LIBRARY_FOLDER, venv.fileIcon(libraryReadOnly = true) { true })
        venv.isExpanded = true
        assertEquals(FileIcon.LIBRARY_FOLDER_OPEN, venv.fileIcon(libraryReadOnly = true) { true })
        assertEquals(FileIcon.FOLDER_OPEN, venv.fileIcon(libraryReadOnly = false) { true })
    }

    @Test
    fun `a top-level folder is no library root and keeps the folder icon`() {
        // detectLibrary is false for top-level folders in the app; set here so only the top-level test can decide.
        val top = FileTreeItem(root, detectLibrary = true, libraryRoots = { LibraryKind.VIRTUAL_ENV })

        assertEquals(LibraryKind.VIRTUAL_ENV, top.libraryKind)
        assertEquals(FileIcon.FOLDER, top.fileIcon(libraryReadOnly = true) { true })
    }

    @Test
    fun `a shown generated folder or file shows the muted cog, a library icon comes first`() {
        root.resolve("package.json").createFile()
        root.resolve("node_modules").createDirectories()
        root.resolve("build").createDirectories()
        root.resolve("build.gradle").createFile()
        root.resolve(".DS_Store").createFile()
        val top =
            FileTreeItem(
                root,
                generated = GeneratedFolderFilter { false },
                libraryRoots = { if (it.name == "build") LibraryKind.PYPACKAGES else null },
            )
        val modules = child(top, "node_modules")

        assertEquals(FileIcon.GENERATED_FOLDER, modules.fileIcon(libraryReadOnly = true) { true })
        modules.isExpanded = true
        assertEquals(FileIcon.GENERATED_FOLDER_OPEN, modules.fileIcon(libraryReadOnly = true) { true })
        assertEquals(FileIcon.GENERATED_FILE, child(top, ".DS_Store").fileIcon(libraryReadOnly = true) { true })
        assertEquals(FileIcon.LIBRARY_FOLDER, child(top, "build").fileIcon(libraryReadOnly = true) { true })
    }

    @Test
    fun `a folder with folder icons shows the top one at the top and else the open or closed one`() {
        root.resolve("sub").createDirectories()
        val top = FileTreeItem(root, folderIcons = icons)
        val sub = child(top, "sub")

        assertEquals(icons.top, top.fileIcon(false) { true })
        assertEquals(icons.closed, sub.fileIcon(false) { true })
        sub.isExpanded = true
        assertEquals(icons.open, sub.fileIcon(false) { true })
        top.isExpanded = true
        assertEquals(icons.top, top.fileIcon(false) { true })
    }

    @Test
    fun `a folder without folder icons and a file with a known type show the icon of their type`() {
        root.resolve("sub").createDirectories()
        root.resolve("a.kt").createFile()
        val top = FileTreeItem(root)
        var asked = 0

        assertEquals(FileIcon.FOLDER, child(top, "sub").fileIcon(false) { true })
        assertEquals(fileIconFor("a.kt", false, false) { true }, child(top, "a.kt").fileIcon(false) { asked++ > 0 })
        assertEquals(0, asked, "a known type is not read")
    }

    @Test
    fun `a file of no known type is text or binary as asked once, and the answer is kept`() {
        root.resolve("x.zzzz").createFile()
        root.resolve("y.zzzz").createFile()
        val top = FileTreeItem(root)
        val text = child(top, "x.zzzz")
        val binary = child(top, "y.zzzz")
        var asked = 0

        assertEquals(FileIcon.TEXT, text.fileIcon(false) { asked++ >= 0 })
        assertEquals(FileIcon.TEXT, text.fileIcon(false) { asked++ < 0 })
        assertEquals(1, asked)
        assertEquals(true, text.readable.value)
        assertEquals(FileIcon.BINARY, binary.fileIcon(false) { asked++ < 0 })
        assertEquals(FileIcon.BINARY, binary.fileIcon(false) { asked++ >= 0 })
        assertEquals(2, asked)
        assertEquals(false, binary.readable.value)
    }

    @Test
    fun `a library root shows the locked folder, closed or open`() {
        assertEquals(
            FileIcon.LIBRARY_FOLDER,
            libraryFolderIcon(LibraryKind.VIRTUAL_ENV, expanded = false, readOnly = true, topLevel = false),
        )
        assertEquals(
            FileIcon.LIBRARY_FOLDER_OPEN,
            libraryFolderIcon(LibraryKind.PYPACKAGES, expanded = true, readOnly = true, topLevel = false),
        )
    }

    @Test
    fun `no icon without a kind, with the setting off or on a top-level node`() {
        assertNull(libraryFolderIcon(null, expanded = false, readOnly = true, topLevel = false))
        assertNull(libraryFolderIcon(LibraryKind.VIRTUAL_ENV, expanded = false, readOnly = false, topLevel = false))
        assertNull(libraryFolderIcon(LibraryKind.VIRTUAL_ENV, expanded = true, readOnly = true, topLevel = true))
    }

    @Test
    fun `the tooltip names the kind and follows the same conditions`() {
        assertEquals(
            "Library code (virtual environment): opens read-only",
            libraryTooltip(LibraryKind.VIRTUAL_ENV, readOnly = true, topLevel = false),
        )
        assertEquals(
            "Library code (__pypackages__): opens read-only",
            libraryTooltip(LibraryKind.PYPACKAGES, readOnly = true, topLevel = false),
        )
        assertNull(libraryTooltip(LibraryKind.VIRTUAL_ENV, readOnly = false, topLevel = false))
        assertNull(libraryTooltip(LibraryKind.VIRTUAL_ENV, readOnly = true, topLevel = true))
        assertNull(libraryTooltip(null, readOnly = true, topLevel = false))
    }

    @Test
    fun `a shown generated folder shows the muted cog, closed or open, and none without a reason`() {
        val reason = GeneratedReason("node_modules for package.json")

        assertEquals(FileIcon.GENERATED_FOLDER, generatedIcon(reason, expanded = false))
        assertEquals(FileIcon.GENERATED_FOLDER_OPEN, generatedIcon(reason, expanded = true))
        assertNull(generatedIcon(null, expanded = true))
        assertEquals(
            FileIcon.GENERATED_FILE,
            generatedIcon(GeneratedReason("macOS folder metadata", file = true), false),
        )
    }

    @Test
    fun `the generated tooltip names the reason and the setting`() {
        assertEquals(
            "Generated folder: node_modules for package.json. Hidden while Hide Generated Files is on",
            generatedTooltip(GeneratedReason("node_modules for package.json")),
        )
        assertEquals(
            "Generated file: macOS folder metadata. Hidden while Hide Generated Files is on",
            generatedTooltip(GeneratedReason("macOS folder metadata", file = true)),
        )
        assertNull(generatedTooltip(null))
    }
}
