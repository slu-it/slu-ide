package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileexplorer.tree.canDrop
import dev.sluide.fileexplorer.tree.canDropRows
import dev.sluide.fileexplorer.tree.outermost
import dev.sluide.fileexplorer.tree.sourceRows
import javafx.scene.control.TreeItem
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Moving entries of the explorer into another folder: by drag and drop (ADR 0073), or by pasting entries that were cut
 * (ADR 0092).
 */
internal class ExplorerMoves(
    private val host: ExplorerEditHost,
    /**
     * Whether an entry can be renamed, moved or deleted (not a top-level folder, not being deleted, not holding an
     * entry being deleted).
     */
    private val canChange: (TreeItem<Path>?) -> Boolean,
    /** Whether a folder is being deleted or lies in an entry being deleted; nothing is moved into it. */
    private val isBeingDeleted: (Path) -> Boolean,
    /** As [canChange] for the entry at a path, also one not shown. */
    private val canChangePath: (Path) -> Boolean,
) {
    /**
     * Asks once whether to move [items] into [folder] and moves them, in order, on the calling (JavaFX) thread, keeping
     * their names. Entries at or below another one go along with it; entries directly in [folder] already are left
     * out. Nothing happens if [items] cannot be moved there ([canDrop]), one of them cannot be changed or is no longer
     * shown, or [folder] is being deleted or no longer shown; if that changes while asking, an error says so and
     * nothing moves. Entries whose names exist in
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
        val sources = sourceRows(items)
        if (!canMove(sources, folder) || !canDropRows(sources, folder)) return
        moveAll(
            sources.map { Source(it.sourcePath, it) },
            folder,
            confirm = true,
            stillMovable = { canMove(sources, folder) },
            reselect = { reselect(items) },
        )
    }

    /**
     * Moves the entries at [paths] into [folder] like [move], without asking first, e.g. when pasting cut entries; the
     * entries need not be shown (in a collapsed folder, or another window). Only names that exist in [folder] are
     * asked about, as for [move]. Nothing happens if all entries are in [folder] already; an error says why if [folder]
     * lies in one of them (also through a link), one of them cannot be changed (a top-level folder, or being deleted),
     * or [folder] is being deleted or no longer shown. Entries gone from the disk are reported, the others moved.
     * Returns whether entries were moved.
     */
    fun move(
        paths: List<Path>,
        folder: FileTreeItem,
    ): Boolean {
        val all = outermost(paths) { it }
        if (all.isEmpty() || !canMoveInto(folder)) return false
        all.firstOrNull { FileOperations.isAtOrBelow(folder.value, it) }?.let {
            host.showError("“${it.fileName}” cannot be moved into itself.")
            return false
        }
        val (sources, gone) = all.partition { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
        val goneText = goneMessage(gone, "moved")
        if (sources.isEmpty() || !canDrop(sources, folder.value)) {
            goneText?.let(host.showError)
            return false
        }
        val stillMovable = { canMoveInto(folder) && sources.all(canChangePath) }
        if (!stillMovable()) {
            host.showError(NOTHING_MOVED.format(folderName(folder)))
            return false
        }
        val shown = host.rows().filterIsInstance<FileTreeItem>()
        return moveAll(
            sources.map { path -> Source(path, shown.firstOrNull { it.sourcePath == path }) },
            folder,
            confirm = false,
            stillMovable = stillMovable,
            reselect = {},
            note = goneText,
        )
    }

    /** An entry to move: its path, and its row if it is shown (a compacted row for its first folder). */
    private class Source(
        val path: Path,
        val item: FileTreeItem?,
    ) {
        val name: String get() = path.fileName.toString()

        /** The name in questions: the row's (`src/main/kotlin` for a compacted one), else the entry's. */
        val shownName: String get() = item?.displayName ?: name
    }

    /**
     * Moves [sources] into [folder] after asking, if [confirm] or some names are taken there; returns whether entries
     * were moved. [stillMovable] is checked again after the question, [reselect] runs if nothing moves. A [note] is
     * shown with the errors, or alone when the moves are done.
     */
    private fun moveAll(
        sources: List<Source>,
        folder: FileTreeItem,
        confirm: Boolean,
        stillMovable: () -> Boolean,
        reselect: () -> Unit,
        note: String? = null,
    ): Boolean {
        fun showError(message: String?) =
            listOfNotNull(note, message).takeIf { it.isNotEmpty() }?.let { host.showError(it.joinToString("\n\n")) }

        val moving = sources.filter { it.path.parent != folder.value }
        val folderName = folderName(folder)
        val clashing = moving.filter { Files.exists(folder.value.resolve(it.name), LinkOption.NOFOLLOW_LINKS) }
        if (clashing.size == moving.size) {
            reselect()
            showError(
                clashing.singleOrNull()?.let { "“${it.name}” already exists in “$folderName”." }
                    ?: "None of the ${clashing.size} entries can be moved: their names already exist in “$folderName”.",
            )
            return false
        }
        val others = moving.filter { source -> clashing.none { it === source } }
        if (confirm || clashing.isNotEmpty()) {
            val from = others.singleOrNull()?.item?.parent as? FileTreeItem
            val question =
                moveQuestion(
                    others.map { it.shownName },
                    clashing.map { it.name },
                    folderName,
                    from?.let(::location),
                    location(folder),
                )
            if (!host.confirmMove(question)) {
                reselect()
                return false
            }
        }
        // A delete may have started while asking, or an entry vanished from the tree.
        if (!stillMovable()) {
            showError(NOTHING_MOVED.format(folderName))
            return false
        }
        val moved = mutableListOf<Pair<Source, Path>>()
        var failure: String? = null
        for (source in others) {
            val from = source.path
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
            moved += source to to
        }
        // Also after a failure: the entries moved before it are gone from their folders.
        val sourceFolders = others.map { it.path.parent }.toSet()
        (
            others.mapNotNull { it.item?.parent as? FileTreeItem } +
                host.rows().filterIsInstance<FileTreeItem>().filter { it.value in sourceFolders && it !== folder }
        ).distinct()
            .filter(host.isShown)
            .forEach(FileTreeItem::refresh)
        folder.isExpanded = true
        folder.refresh()
        host.closeWatchesOfVanishedFolders()
        val shown =
            moved.mapNotNull { (source, to) ->
                folder.children
                    .firstOrNull { (it as? FileTreeItem)?.sourcePath == to }
                    ?.also { it.isExpanded = source.item?.isExpanded == true }
            }
        host.selectRows(shown)
        showError(failure)
        return moved.isNotEmpty()
    }

    private fun canMove(
        sources: List<FileTreeItem>,
        folder: FileTreeItem,
    ): Boolean =
        folder.isDirectory &&
            host.isShown(folder) &&
            !isBeingDeleted(folder.value) &&
            sources.isNotEmpty() &&
            sources.all(canChange) &&
            sources.all(host.isShown)

    private fun canMoveInto(folder: FileTreeItem): Boolean =
        folder.isDirectory && host.isShown(folder) && !isBeingDeleted(folder.value)

    private fun reselect(items: List<FileTreeItem>) = host.selectRows(items.filter(host.isShown))

    private fun folderName(folder: FileTreeItem): String = folder.displayName

    /** Where [folder] is, for the question: its top-level folder's label or name, then the path below it. */
    private fun location(folder: FileTreeItem): String {
        val top = generateSequence(folder) { it.parent as? FileTreeItem }.last()
        val name = top.label ?: (top.value.fileName ?: top.value).toString()
        return (
            listOf(name) +
                top.value
                    .relativize(folder.value)
                    .map { it.toString() }
                    .filter { it.isNotEmpty() }
        ).joinToString("/")
    }
}

private const val NOTHING_MOVED = "Nothing was moved: “%s” or one of the entries is being deleted or gone."
