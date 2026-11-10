package dev.sluide.fileexplorer.tree

import javafx.application.Platform
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.control.skin.VirtualFlow
import java.nio.file.Path

/** Whether [item] is still in the tree, e.g. not gone with a folder opened since. */
internal fun TreeView<Path>.isShown(item: TreeItem<Path>): Boolean =
    generateSequence(item) { it.parent }.last() === root

/** Selects [item], scrolling only as far as needed to show it, and focuses its row in the focus model. */
internal fun TreeView<Path>.selectRow(item: TreeItem<Path>) {
    val row = getRow(item)
    selectionModel.clearAndSelect(row)
    // Rows inserted or removed before the focused one move the focus later, away from the selection.
    Platform.runLater { focusModel.focus(getRow(item)) }
    (lookup(".virtual-flow") as? VirtualFlow<*>)?.scrollTo(row) ?: scrollTo(row)
}

/** Selects exactly [items], scrolling to the first of them like [selectRow]; nothing for none. */
internal fun TreeView<Path>.selectRows(items: List<TreeItem<Path>>) {
    val rows = items.sortedBy { getRow(it) }
    selectRow(rows.firstOrNull() ?: return)
    rows.drop(1).forEach { selectionModel.select(it) }
}

private fun TreeView<Path>.topLevelOf(item: TreeItem<Path>): TreeItem<Path>? =
    generateSequence(item) { it.parent }.firstOrNull { it.parent === root }

/**
 * Runs [change] and selects the entries again that were selected if their rows were replaced (a compacted row split
 * or joined): the row that stands for the entry now, for a middle folder its compacted row. With [ancestorFallback],
 * a row that is gone is replaced by the nearest ancestor still shown in its top-level node (a folder that was hidden).
 */
internal fun TreeView<Path>.keepSelection(
    ancestorFallback: Boolean = false,
    change: () -> Unit,
) {
    val selected = selectionModel.selectedItems.filterNotNull().toList()
    val tops = selected.associateWith { topLevelOf(it) }
    change()
    if (selected.all { isShown(it) }) return
    val rows = (0 until expandedItemCount).mapNotNull { getTreeItem(it) as? FileTreeItem }
    selectRows(
        selected
            .mapNotNull { item ->
                item.takeIf { isShown(it) }
                    ?: rows.firstOrNull { it.represents(item.value) }
                    ?: if (ancestorFallback) {
                        rows
                            .filter { topLevelOf(it) === tops[item] && item.value.startsWith(it.value) }
                            .maxByOrNull { it.value.nameCount }
                    } else {
                        null
                    }
            }.distinct(),
    )
}
