package dev.sluide.fileexplorer.tree

import javafx.scene.control.TreeItem
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Finds the item of [path] below the [topLevel] folder that holds it (the deepest one, if several do), loading the
 * folders on the way and reading a folder again if the entry is new, before its watch reported it. Returns the folders
 * from the top-level one down to the parent of the item, and the item (the top-level folder itself, with no folders,
 * for its own path); `null` if no top-level folder holds [path] or it does not exist. Expands nothing.
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
    for (segment in top.value.relativize(target).filter { it.toString().isNotEmpty() }) {
        val folder = item as? FileTreeItem ?: return null
        folders += folder
        val next = folder.value.resolve(segment)
        var found = folder.children.firstOrNull { it.value == next }
        if (found == null) {
            folder.refresh()
            found = folder.children.firstOrNull { it.value == next }
        }
        item = found ?: return null
    }
    return folders to item
}
