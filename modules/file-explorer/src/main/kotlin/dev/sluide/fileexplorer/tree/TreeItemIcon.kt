package dev.sluide.fileexplorer.tree

import dev.sluide.fileicons.FileIcon
import dev.sluide.fileicons.fileIconFor
import java.nio.file.Path

/**
 * The icon of this item: for a folder with [FileTreeItem.folderIcons] the top icon of a top-level folder (its parent is
 * not a [FileTreeItem]) or else the open or closed one; otherwise the icon of its type. [canOpen] says whether a file
 * of no known type is text, and is asked once per file: the answer is kept in [FileTreeItem.readable].
 */
internal fun FileTreeItem.fileIcon(canOpen: (Path) -> Boolean): FileIcon {
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
