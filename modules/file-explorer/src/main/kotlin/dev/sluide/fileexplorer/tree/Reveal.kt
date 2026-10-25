package dev.sluide.fileexplorer.tree

import javafx.scene.control.TreeItem
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Finds the item of [path] below the [topLevel] folder that holds it (the deepest one, if several do), loading the
 * folders on the way and reading a folder again if the entry is new, before its watch reported it, or a compacted row
 * on the way no longer stands for its folders (ADR 0096). Returns the folders from the top-level one down to the parent
 * of the item, and the item (the top-level folder itself, with no folders, for its own path; the compacted row for one
 * of its middle folders); `null` if no top-level folder holds [path] or it does not exist. Expands nothing.
 */
internal fun findForReveal(
    topLevel: List<FileTreeItem>,
    path: Path,
): Pair<List<FileTreeItem>, TreeItem<Path>>? {
    val target = path.toAbsolutePath().normalize()
    val top = topLevel.filter { target.startsWith(it.value) }.maxByOrNull { it.value.nameCount } ?: return null
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
    var item: TreeItem<Path> = top
    val folders = mutableListOf<FileTreeItem>()
    while (item.value != target) {
        val folder = item as? FileTreeItem ?: return null
        // A middle folder of a compacted row is shown by that row.
        if (folder.represents(target)) break
        folders += folder
        val next = folder.value.resolve(folder.value.relativize(target).getName(0))
        item = folder.step(next, target) ?: folder.also(FileTreeItem::refresh).step(next, target) ?: return null
    }
    return folders to item
}

/**
 * The child of this folder on the way to [target] that stands for [next], the entry in this folder; `null` if there
 * is none, or it is a compacted row that no longer stands for its folders.
 */
private fun FileTreeItem.step(
    next: Path,
    target: Path,
): TreeItem<Path>? =
    children
        .firstOrNull { (it as? FileTreeItem)?.sourcePath == next }
        ?.takeIf { it is FileTreeItem && (target.startsWith(it.value) || it.represents(target)) && !it.isChainStale() }

/**
 * Expands [parent] and the folders below it down to [path], reading a folder again if the entry is new, and returns
 * the item of [path]; `null`, expanding what was found on the way, if [path] is not below [parent] or does not exist.
 * A direct child is found after reading [parent] again. If [parent] is a row that no longer stands for its folders
 * (e.g. a folder that now holds only the new folder), its parent is read again first and the row for [parent]'s folder
 * taken instead.
 */
internal fun expandTo(
    parent: FileTreeItem,
    path: Path,
): TreeItem<Path>? {
    val folder = current(parent)
    folder.isExpanded = true
    // Reads the folder for the first time, or again if it was loaded.
    folder.refresh()
    val (folders, item) = findForReveal(listOf(folder), path) ?: return null
    folders.forEach { it.isExpanded = true }
    return item
}

/** [item], or the row standing for its folder after reading its parent again if [item] no longer stands for its folders. */
private fun current(item: FileTreeItem): FileTreeItem {
    if (!item.isChainStale()) return item
    val parent = item.parent as? FileTreeItem ?: return item
    parent.refresh()
    return parent.children.filterIsInstance<FileTreeItem>().firstOrNull { it.represents(item.value) } ?: item
}
