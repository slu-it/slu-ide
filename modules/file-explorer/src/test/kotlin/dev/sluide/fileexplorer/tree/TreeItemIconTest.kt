package dev.sluide.fileexplorer.tree

import dev.sluide.fileicons.FileIcon
import dev.sluide.librarycode.LibraryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TreeItemIconTest {
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
}
