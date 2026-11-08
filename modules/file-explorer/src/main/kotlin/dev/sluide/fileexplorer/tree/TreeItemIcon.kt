package dev.sluide.fileexplorer.tree

import dev.sluide.fileicons.FileIcon
import dev.sluide.fileicons.fileIconFor
import dev.sluide.librarycode.LibraryKind
import java.nio.file.Path

/**
 * The icon of this item: for a folder with [FileTreeItem.folderIcons] the top icon of a top-level folder (its parent is
 * not a [FileTreeItem]) or else the open or closed one; otherwise the icon of its type. [canOpen] says whether a file
 * of no known type is text, and is asked once per file: the answer is kept in [FileTreeItem.readable]. A library root
 * ([FileTreeItem.libraryKind]) shows the locked folder first, while [libraryReadOnly] is on ([libraryFolderIcon]).
 */
internal fun FileTreeItem.fileIcon(
    libraryReadOnly: Boolean,
    canOpen: (Path) -> Boolean,
): FileIcon {
    libraryFolderIcon(libraryKind, isExpanded, libraryReadOnly, parent !is FileTreeItem)?.let { return it }
    val folderIcons = folderIcons.takeIf { isDirectory }
    return if (folderIcons != null) {
        when {
            parent !is FileTreeItem -> folderIcons.top
            isExpanded -> folderIcons.open
            else -> folderIcons.closed
        }
    } else {
        fileIconFor(value, isDirectory, isExpanded) {
            readable.value ?: canOpen(value).also { readable.value = it }
        }
    }
}

/**
 * The icon of a library root folder (ADR 0150): the locked folder, open or closed; `null` for no [kind], with the
 * setting [readOnly] off and for a [topLevel] node (the project or an extra root: its files open editable).
 */
internal fun libraryFolderIcon(
    kind: LibraryKind?,
    expanded: Boolean,
    readOnly: Boolean,
    topLevel: Boolean,
): FileIcon? =
    when {
        kind == null || !readOnly || topLevel -> null
        expanded -> FileIcon.LIBRARY_FOLDER_OPEN
        else -> FileIcon.LIBRARY_FOLDER
    }

/** The tooltip of a library root folder, `null` when [libraryFolderIcon] shows none. */
internal fun libraryTooltip(
    kind: LibraryKind?,
    readOnly: Boolean,
    topLevel: Boolean,
): String? =
    if (libraryFolderIcon(kind, expanded = false, readOnly, topLevel) == null) {
        null
    } else {
        "Library code (${if (kind == LibraryKind.PYPACKAGES) "__pypackages__" else "virtual environment"}): opens read-only"
    }
