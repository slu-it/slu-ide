package dev.sluide.fileexplorer.tree

import javafx.beans.Observable
import javafx.beans.value.ObservableValueBase
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import java.nio.file.Path

/**
 * The explorer search's state on its tree: narrows the tree to the matches with [apply] and restores it afterwards.
 * Never opens files or moves the focus; FX thread.
 */
internal class TreeSearch(
    private val tree: TreeView<Path>,
    /** The top-level folders, the project first. */
    private val topLevel: () -> List<FileTreeItem>,
    /** Ends the watches of folders no longer in the tree. */
    private val closeVanishedWatches: () -> Unit,
) {
    /** The filter the tree's items list with; `null` while no search is applied. */
    var filter: TreeSearchFilter? = null
        private set

    private var matches: Set<Path> = emptySet()

    /** The folders expanded before the search started, every folder of their rows' chains. */
    private var expandedBefore: Set<Path> = emptySet()

    /** Fires (FX thread) whenever the search's results may have changed; see the view's `searchInvalidated`. */
    val invalidated: Observable get() = invalidation

    private val invalidation = Invalidation()

    /** Tells [invalidated]'s listeners. */
    fun invalidate() = invalidation.fire()

    /**
     * Narrows the tree to [matches] (absolute paths, as the tree holds them) and their folders, or restores it for
     * `null`, see [restore]. The first one remembers the expanded folders and expands the folders holding a match (also
     * a matched one), the others collapse, an open hit too ([TreeSearchFilter.isFolderHit]). A later one keeps the
     * extras, the expansion the user did and the remembered one; it expands the folders of new matches and sets the
     * expansion of the rows that changed between holding a match and being an open hit. The selection stays where it
     * is still shown; a row no longer shown is unselected.
     */
    fun apply(matches: List<Path>?) {
        if (matches == null) return restore()
        val previous = filter
        val next = TreeSearchFilter(matches, topLevel().map { it.value })
        previous?.kept?.forEach(next::keep)
        val fresh = if (previous == null) matches.toSet() else matches.toSet() - this.matches
        if (previous == null) expandedBefore = expandedFolders()
        this.matches = matches.toSet()
        filter = next
        keepShownSelection {
            topLevel().forEach(FileTreeItem::refreshTree)
            val ways = fresh.flatMap(::foldersHolding).toSet()
            val loaded = topLevel().flatMap(FileTreeItem::loadedFolders)
            // A later run touches only the folders of new matches and the rows that changed between the two kinds.
            val rows = if (previous == null) loaded else ways + loaded.filter { changedKind(previous, next, it) }
            rows.forEach { it.isExpanded = next.holdsMatch(it) }
            closeVanishedWatches()
        }
    }

    /** Whether [row] became a folder holding a match or an open hit with [next]: a matched folder whose kind changed. */
    private fun changedKind(
        previous: TreeSearchFilter,
        next: TreeSearchFilter,
        row: FileTreeItem,
    ): Boolean =
        (next.holdsMatch(row) && !previous.holdsMatch(row)) || (next.isFolderHit(row) && !previous.isFolderHit(row))

    /** Adds [path] to the results while a search is applied, so the explorer can show and select it. */
    fun keep(path: Path) {
        filter?.keep(path.toAbsolutePath().normalize())
    }

    /** Runs [find] with [path] kept in the results ([keep]); takes it out again if [find] finds nothing. */
    fun <T : Any> keepFor(
        path: Path,
        find: () -> T?,
    ): T? {
        val before = filter?.kept.orEmpty().toList()
        keep(path)
        return find() ?: null.also {
            filter?.resetExtras()
            before.forEach { filter?.keep(it) }
        }
    }

    fun resetExtras() {
        filter?.resetExtras()
    }

    /** Forgets the search without restoring the tree, e.g. when another folder is opened. */
    fun forget() {
        filter = null
        matches = emptySet()
        expandedBefore = emptySet()
    }

    /**
     * Ends the search: lists every loaded folder in full again, expands exactly the folders that were expanded before
     * it started, and keeps the selected entries selected, expanding the folders down to them and scrolling to them.
     */
    private fun restore() {
        filter ?: return
        val selected =
            tree.selectionModel.selectedItems.filterNotNull().mapNotNull { item ->
                topOf(item)?.let {
                    it to
                        item.value
                }
            }
        val expanded = expandedBefore
        forget()
        topLevel().forEach(FileTreeItem::refreshTree)
        topLevel().forEach { expandAgain(it, expanded) }
        closeVanishedWatches()
        val rows =
            selected.mapNotNull { (top, path) ->
                findForReveal(listOf(top), path)?.let { (folders, item) ->
                    folders.forEach { it.isExpanded = true }
                    item
                }
            }
        // Scrolls to them even if they were selected already.
        if (rows.isEmpty()) tree.selectionModel.clearSelection() else tree.selectRows(rows.distinct())
    }

    /** Expands [row] if every folder of its chain is in [expanded], else collapses it; the same for the rows below. */
    private fun expandAgain(
        row: FileTreeItem,
        expanded: Set<Path>,
    ) {
        if (!row.isDirectory) return
        row.isExpanded = expanded.containsAll(row.chain)
        // A collapsed folder that was never loaded is not loaded for it.
        if (row.isExpanded ||
            row.isLoaded
        ) {
            row.children.filterIsInstance<FileTreeItem>().forEach { expandAgain(it, expanded) }
        }
    }

    private fun expandedFolders(): Set<Path> =
        topLevel().flatMap(FileTreeItem::loadedFolders).filter { it.isExpanded }.flatMapTo(HashSet()) { it.chain }

    /**
     * The rows from each top-level folder holding [match] down to the row holding it, loading them; none if it is gone.
     * A match in a middle folder of a compacted row is that row, a folder hit, so not among them.
     */
    private fun foldersHolding(match: Path): List<FileTreeItem> =
        topLevel().filter { match.startsWith(it.value) }.flatMap { top ->
            findForReveal(listOf(top), match)?.first.orEmpty()
        }

    private fun topOf(item: TreeItem<Path>): FileTreeItem? =
        generateSequence(item) { it.parent }.firstOrNull { it.parent === tree.root } as? FileTreeItem

    /** Runs [change], then selects the rows that were selected and are still shown, or the rows standing for them now. */
    private fun keepShownSelection(change: () -> Unit) {
        val before =
            tree.selectionModel.selectedItems
                .filterNotNull()
                .toList()
        change()
        val rows = (0 until tree.expandedItemCount).mapNotNull { tree.getTreeItem(it) }
        select(
            before
                .mapNotNull { item ->
                    item.takeIf { tree.getRow(it) >= 0 }
                        ?: rows.firstOrNull { (it as? FileTreeItem)?.represents(item.value) == true }
                }.distinct(),
        )
    }

    /** Selects exactly [rows] unless they are selected already; clears the selection for none. */
    private fun select(rows: List<TreeItem<Path>>) {
        if (tree.selectionModel.selectedItems
                .filterNotNull()
                .toSet() == rows.toSet()
        ) {
            return
        }
        if (rows.isEmpty()) tree.selectionModel.clearSelection() else tree.selectRows(rows)
    }

    /** Tells its invalidation listeners every time, unlike a property, which tells them once until it is read. */
    private class Invalidation : ObservableValueBase<Unit>() {
        override fun getValue() = Unit

        fun fire() = fireValueChangedEvent()
    }
}
