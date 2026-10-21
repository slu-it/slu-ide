package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileexplorer.tree.canDrop
import dev.sluide.fileexplorer.tree.outermost
import javafx.scene.control.TreeItem
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Moving entries of the explorer into another folder, e.g. by drag and drop (ADR 0073). */
internal class ExplorerMoves(
    private val host: ExplorerEditHost,
    /**
     * Whether an entry can be renamed, moved or deleted (not a top-level folder, not being deleted, not holding an
     * entry being deleted).
     */
    private val canChange: (TreeItem<Path>?) -> Boolean,
    /** Whether a folder is being deleted or lies in an entry being deleted; nothing is moved into it. */
    private val isBeingDeleted: (Path) -> Boolean,
) {
    /**
     * Asks once whether to move [items] into [folder] and moves them, in order, on the calling (JavaFX) thread, keeping
     * their names. Entries at or below another one go along with it; entries directly in [folder] already are left
     * out. Nothing happens if [items] cannot be moved there ([canDrop]), one of them cannot be changed or [folder] is
     * being deleted; if that changes while asking, an error says so and nothing moves. Entries whose names exist in
     * [folder] are skipped, never replaced: the question says so, and if all clash an error is shown instead. Each
     * move is reported to the `onMoved` hook; the first failure, also a name taken while asking, stops the rest and is
     * shown. Afterwards the folders are read again, [folder] is expanded and the moved entries are selected, a moved
     * folder expanded if it was. If nothing moves because of clashes or a cancelled question, [items] are selected again
     * (those still shown): expanding a folder while dragging may have lost the selection.
     */
    fun move(
        items: List<FileTreeItem>,
        folder: FileTreeItem,
    ) {
        val sources = outermost(items) { it.value }
        if (!canMove(sources, folder) || !canDrop(sources.map { it.value }, folder.value)) return
        val moving = sources.filter { it.value.parent != folder.value }
        val folderName = folder.label ?: (folder.value.fileName ?: folder.value).toString()
        val clashing = moving.filter { Files.exists(folder.value.resolve(name(it)), LinkOption.NOFOLLOW_LINKS) }
        if (clashing.size == moving.size) {
            reselect(items)
            host.showError(
                clashing.singleOrNull()?.let { "“${name(it)}” already exists in “$folderName”." }
                    ?: "None of the ${clashing.size} entries can be moved: their names already exist in “$folderName”.",
            )
            return
        }
        val others = moving.filter { item -> clashing.none { it === item } }
        if (!host.confirmMove(moveQuestion(others.map(::name), clashing.map(::name), folderName))) {
            reselect(items)
            return
        }
        // A delete may have started while asking.
        if (!canMove(sources, folder)) {
            host.showError("Nothing was moved: “$folderName” or one of the entries is being deleted.")
            return
        }
        val moved = mutableListOf<Pair<FileTreeItem, Path>>()
        var failure: String? = null
        for (item in others) {
            val from = item.value
            val to =
                try {
                    // Checks again that the name is free: the folder may have changed while asking.
                    FileOperations.move(from, folder.value)
                } catch (e: FileOperationException) {
                    failure = e.message.orEmpty()
                    break
                }
            // The folder and its loaded subfolders have new paths, watched once they are loaded again.
            host.closeWatchesUnder(from)
            host.onMoved(from, to)
            moved += item to to
        }
        // Also after a failure: the entries moved before it are gone from their folders.
        others
            .mapNotNull { it.parent as? FileTreeItem }
            .distinct()
            .filter(host.isShown)
            .forEach(FileTreeItem::refresh)
        folder.isExpanded = true
        folder.refresh()
        host.closeWatchesOfVanishedFolders()
        val shown =
            moved.mapNotNull { (item, to) ->
                folder.children.firstOrNull { it.value == to }?.also { it.isExpanded = item.isExpanded }
            }
        host.selectRows(shown)
        failure?.let(host.showError)
    }

    private fun canMove(
        sources: List<FileTreeItem>,
        folder: FileTreeItem,
    ): Boolean =
        folder.isDirectory &&
            host.isShown(folder) &&
            !isBeingDeleted(folder.value) &&
            sources.isNotEmpty() &&
            sources.all(canChange)

    private fun reselect(items: List<FileTreeItem>) = host.selectRows(items.filter(host.isShown))

    private fun name(item: FileTreeItem): String = item.value.fileName.toString()
}
