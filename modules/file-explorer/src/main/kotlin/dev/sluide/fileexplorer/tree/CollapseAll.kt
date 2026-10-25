package dev.sluide.fileexplorer.tree

import javafx.scene.control.TreeItem
import java.nio.file.Path

/**
 * Collapses every loaded folder below the [topLevel] folders, and the top-level folders themselves except [keepOpen]
 * (the project); loads nothing. Returns the rows the [selected] ones become: a row hidden by it is replaced by its
 * nearest ancestor that is still shown, the others stay; no duplicates, in the order of [selected].
 */
internal fun collapseAll(
    topLevel: List<FileTreeItem>,
    keepOpen: TreeItem<Path>?,
    selected: List<TreeItem<Path>>,
): List<TreeItem<Path>> {
    topLevel.flatMap(FileTreeItem::loadedFolders).forEach { it.isExpanded = it === keepOpen }
    return selected.map(::nearestShown).distinct()
}

/** [item], or its topmost ancestor that is collapsed, the nearest row still shown if [item] is hidden below it. */
private fun nearestShown(item: TreeItem<Path>): TreeItem<Path> {
    val ancestors = generateSequence(item.parent) { it.parent }.toList().asReversed()
    return ancestors.firstOrNull { !it.isExpanded } ?: item
}
