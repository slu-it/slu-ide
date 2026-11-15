package dev.sluide.fileexplorer.tree

import dev.sluide.fileexplorer.generated.GeneratedFolderFilter
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import java.nio.file.Path

/**
 * The explorer's actions on its whole tree: reveal and select an entry, collapse all, read every loaded folder again,
 * and list again after a setting or git's ignores changed. Keeps the selection as [keepSelection] does. FX thread.
 */
internal class TreeOperations(
    private val tree: TreeView<Path>,
    /** The top-level folders, the project first. */
    private val topLevel: () -> List<FileTreeItem>,
    /** The project's top-level folder, which [collapseAll] keeps open; `null` without one. */
    private val projectItem: () -> FileTreeItem?,
    private val generated: GeneratedFolderFilter,
    private val watches: TreeWatches,
) {
    /**
     * Expands the top-level folder holding [path] and the folders down to it, loading them, then selects and scrolls
     * to [path]. Returns false, expanding nothing, if no top-level folder holds it or it does not exist.
     */
    fun reveal(path: Path): Boolean {
        val (folders, item) = findForReveal(topLevel(), path, generated::unhide) ?: return false
        // Only now, so that a path not found leaves the tree as it was.
        folders.forEach { it.isExpanded = true }
        tree.selectRow(item)
        return true
    }

    /** Whether a top-level folder holds [path], so that [reveal] may find it. */
    fun canReveal(path: Path): Boolean {
        val target = path.toAbsolutePath().normalize()
        return topLevel().any { target.startsWith(it.value) }
    }

    /** Shows [path], an entry below [parent], at once, without waiting for the watch, selects and returns it. */
    fun select(
        parent: FileTreeItem,
        path: Path,
    ): TreeItem<Path>? {
        val item = expandTo(parent, path, generated::unhide) ?: return null
        tree.selectRow(item)
        return item
    }

    /**
     * Collapses every folder of every top-level folder but the project; a selected row hidden by it is replaced by its
     * nearest ancestor still shown. Revealed generated folders hide again.
     */
    fun collapseAll() {
        val revealed = generated.clearRevealed()
        val selected =
            tree.selectionModel.selectedItems
                .filterNotNull()
                .toList()
        val rows = collapseAll(topLevel(), projectItem(), selected)
        if (rows != selected) tree.selectRows(rows)
        // The revealed generated folders hide again.
        tree.keepSelection(revealed.isNotEmpty()) {
            topLevel().forEach { it.refreshParentsOf(revealed) }
            watches.closeVanished()
        }
    }

    /** Reads every loaded folder again, forgets whether its files can be opened and renews its watch. */
    fun refreshAll() {
        val revealed = generated.clearRevealed()
        tree.keepSelection(revealed.isNotEmpty()) {
            topLevel().flatMap(FileTreeItem::loadedFolders).asSequence().filter { tree.isShown(it) }.forEach { folder ->
                folder.refresh()
                folder.forgetReadability(listOf(folder.value))
                watches.watch(folder)
            }
            watches.closeVanished()
        }
    }

    /** Builds the tree again, e.g. after compacting was turned on or off, keeping what was expanded and selected. */
    fun relist(ancestorFallback: Boolean = false) =
        tree.keepSelection(ancestorFallback) {
            topLevel().forEach(FileTreeItem::refreshTree)
            watches.closeVanished()
        }

    /** Checks the generated folders and files of every loaded folder against git again; see the view's. */
    fun gitIgnoresChanged() =
        tree.keepSelection(ancestorFallback = true) {
            topLevel().forEach(FileTreeItem::gitIgnoresChanged)
            watches.closeVanished()
        }
}
