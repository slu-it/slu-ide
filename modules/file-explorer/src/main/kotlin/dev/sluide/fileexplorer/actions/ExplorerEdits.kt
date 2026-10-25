package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.FolderAction
import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileexplorer.tree.sourceRows
import javafx.scene.control.TreeItem
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

private val log = System.getLogger(ExplorerEdits::class.java.name)

/**
 * What [ExplorerEdits] needs from the explorer it works for. Every function reads the explorer's current hook or
 * test seam when it is called, so hooks set later and seams replaced by tests take effect.
 */
internal class ExplorerEditHost(
    val projectItem: () -> FileTreeItem?,
    val askName: (title: String, initial: String, validate: (String) -> String?) -> String?,
    val showError: (String) -> Unit,
    val showInfo: (String) -> Unit,
    val confirmDelete: (DeleteQuestion) -> Boolean,
    val confirmMove: (MoveQuestion) -> Boolean,
    val confirmCopy: (CopyQuestion) -> Boolean,
    val runInBackground: (Runnable) -> Unit,
    val dispatch: (Runnable) -> Unit,
    val onMoved: (from: Path, to: Path) -> Unit,
    val unsavedUnder: (Path) -> List<Path>,
    val onDeleted: (path: Path, unsaved: List<Path>) -> Unit,
    val requestOpen: (Path) -> Boolean,
    /** Shows a path below a folder at once (expanding the folders down to it), selects and returns it. */
    val select: (parent: FileTreeItem, path: Path) -> TreeItem<Path>?,
    val selectRow: (TreeItem<Path>) -> Unit,
    /** Selects exactly these rows, scrolling to the first; nothing for none. */
    val selectRows: (List<TreeItem<Path>>) -> Unit,
    val selectedItems: () -> List<TreeItem<Path>>,
    /** Every shown row, top to bottom. */
    val rows: () -> List<TreeItem<Path>>,
    val isShown: (TreeItem<Path>) -> Boolean,
    /** Ends the watches of the folders at or below a path, which moved. */
    val closeWatchesUnder: (Path) -> Unit,
    val closeWatchesOfVanishedFolders: () -> Unit,
)

/** The file operations of the explorer: create, rename and delete entries, and run actions on folders. */
internal class ExplorerEdits(
    private val host: ExplorerEditHost,
) {
    /**
     * The entries being deleted; they, everything in them and the folders holding them cannot be renamed, moved or
     * deleted meanwhile, and nothing can be moved into them.
     */
    private val deleting = mutableSetOf<Path>()

    /**
     * Asks for a name or `/`-separated path (ADR 0094) and creates a file or [folder] of that name in [target], which
     * must be a folder; in the project folder for `null`. Missing folders on the way are created. The new entry (the
     * deepest folder for a trailing `/`) is selected, shown with the folders down to it, and a new file opened.
     */
    fun create(
        target: FileTreeItem?,
        folder: Boolean,
    ) {
        val parent = targetFolder(target) ?: return
        val name =
            host.askName(if (folder) "New Folder" else "New File", "") { FileOperations.pathProblem(it, parent.value) }
                ?: return
        val created =
            try {
                FileOperations.createPath(parent.value, name, folder)
            } catch (e: FileOperationException) {
                host.showError(e.message.orEmpty())
                return
            }
        host.select(parent, created)
        if (!folder && Files.isRegularFile(created)) host.requestOpen(created)
    }

    /**
     * The folder an action on [target] works in: [target] if a folder, `null` for a file; the project folder for `null`.
     */
    fun targetFolder(target: FileTreeItem?): FileTreeItem? {
        val item = target ?: host.projectItem() ?: return null
        return item.takeIf { it.isDirectory }
    }

    /**
     * Runs [action] on the folder for [target] (as for [create]). A returned file is selected, if it is in that folder,
     * and opened; the folder is expanded and read again so that new entries show at once. A note is shown after it.
     */
    fun runFolderAction(
        action: FolderAction,
        target: FileTreeItem?,
    ) {
        val folder = targetFolder(target) ?: return
        val result =
            try {
                action.perform(folder.value)
            } catch (e: IOException) {
                log.log(System.Logger.Level.WARNING, "A folder action failed in ${folder.value}", e)
                host.showError(e.message.orEmpty())
                return
            } catch (e: UncheckedIOException) {
                log.log(System.Logger.Level.WARNING, "A folder action failed in ${folder.value}", e)
                host.showError((e.cause?.message ?: e.message).orEmpty())
                return
            }
        val open = result.open
        if (open != null && open.parent == folder.value) {
            host.select(folder, open)
        } else {
            folder.isExpanded = true
            folder.refresh()
        }
        if (open != null) host.requestOpen(open)
        result.note?.let(host.showInfo)
    }

    /**
     * Whether [item] can be renamed, moved or deleted: an entry of a top-level folder, not a top-level folder itself
     * (their parent is the hidden root), not being deleted, and not a folder holding an entry being deleted (which
     * would take a half-deleted tree along); for a compacted row, all of its chain.
     */
    fun canChange(item: TreeItem<Path>?): Boolean =
        item is FileTreeItem &&
            item.parent is FileTreeItem &&
            deleting.none { item.sourcePath.startsWith(it) || it.startsWith(item.sourcePath) }

    /** Whether [path] is being deleted or lies in an entry being deleted; nothing may be moved into it. */
    fun isBeingDeleted(path: Path): Boolean = deleting.any { path.startsWith(it) }

    /**
     * Whether the entry at [path], shown or not, can be moved: as [canChange], not a top-level folder, not being
     * deleted and not holding an entry being deleted.
     */
    fun canChangePath(path: Path): Boolean =
        host.rows().none { it.parent !is FileTreeItem && it.value == path } &&
            deleting.none { path.startsWith(it) || it.startsWith(path) }

    /**
     * Asks for a new name of [item] and renames it in its folder, reporting it to the `onMoved` hook. The renamed entry
     * is selected, a folder stays expanded if it was. A compacted row renames its folders ([renameChain]).
     */
    fun rename(item: FileTreeItem) {
        val parent = item.parent as? FileTreeItem ?: return
        if (item.isCompacted) return renameChain(item, parent)
        val path = item.value
        val name =
            host.askName("Rename", path.fileName.toString()) {
                FileOperations.nameProblem(it, parent.value, renaming = path)
            }
                ?: return
        val renamed =
            try {
                FileOperations.rename(path, name)
            } catch (e: FileOperationException) {
                host.showError(e.message.orEmpty())
                return
            }
        if (renamed == path) return
        // The folder and its loaded subfolders have new paths, watched once they are loaded again.
        host.closeWatchesUnder(path)
        host.onMoved(path, renamed)
        host.select(parent, renamed)?.isExpanded = item.isExpanded
    }

    /**
     * Asks for new names of the folders of the compacted row [item] (`src/main/kotlin`, ADR 0096), as many as before,
     * each checked in its folder ([chainNameProblem]), and renames the changed ones in place, the deepest first so that
     * the paths of the outer ones stay valid. Each rename is reported to the `onMoved` hook; the first failure stops
     * the rest and is shown. The row is selected afterwards and stays expanded if it was.
     */
    private fun renameChain(
        item: FileTreeItem,
        parent: FileTreeItem,
    ) {
        val chain = item.chain
        val names = chain.map { it.fileName.toString() }
        val answer = host.askName("Rename", item.displayName) { chainNameProblem(it, chain) } ?: return
        val wanted = answer.split('/')
        val renamed = names.toMutableList()
        for (index in chain.indices.reversed()) {
            if (wanted[index] == names[index]) continue
            val from = chain[index]
            val to =
                try {
                    FileOperations.rename(from, wanted[index])
                } catch (e: FileOperationException) {
                    host.showError(e.message.orEmpty())
                    break
                }
            if (to == from) continue
            host.closeWatchesUnder(from)
            host.onMoved(from, to)
            renamed[index] = to.fileName.toString()
        }
        if (renamed == names) return
        host.select(parent, renamed.fold(parent.value, Path::resolve))?.isExpanded = item.isExpanded
    }

    /**
     * Asks once whether to delete [items], warning about the files with `unsavedUnder` that would be lost, and deletes
     * them permanently in the background, in row order, stopping at the first failure; every entry deleted completely
     * is reported to the `onDeleted` hook. Entries at or below another one are left out, being deleted with it. If the
     * selection lies in the deleted entries, the next row that stays (or the one before, or the folder) is selected
     * right away, before the folders' watches may remove them.
     */
    fun delete(items: List<FileTreeItem>) {
        // A compacted row stands for its first folder (ADR 0096).
        val targets = sourceRows(items.filter { it.parent is FileTreeItem })
        val asked =
            targets.mapNotNull { item ->
                entryKind(item.sourcePath)?.let { kind -> DeleteEntry(item, kind, host.unsavedUnder(item.sourcePath)) }
            }
        if (asked.isEmpty()) return
        val question =
            asked.singleOrNull()?.let { deleteQuestion(it.item.displayName, it.kind, it.unsaved.size) }
                ?: deleteQuestion(asked.map { it.item.displayName to it.kind }, asked.sumOf { it.unsaved.size })
        if (!host.confirmDelete(question)) return
        // The question was about this kind of entry, e.g. not about a folder with everything in it.
        val changed = asked.filter { entryKind(it.path) != it.kind }
        if (changed.isNotEmpty()) {
            val names = changed.joinToString(" and ") { "“${it.item.displayName}”" }
            host.showError(
                if (changed.size == 1) {
                    "$names changed while asking and was not deleted."
                } else {
                    "$names changed while asking and were not deleted."
                },
            )
            changed.mapNotNull { it.item.parent as? FileTreeItem }.distinct().forEach(FileTreeItem::refresh)
        }
        val entries = asked.filter { it !in changed && canChange(it.item) }
        if (entries.isEmpty()) return
        val paths = entries.map { it.path }
        val replacement = replacementSelection(entries)?.also(host.selectRow)
        deleting.addAll(paths)
        host.runInBackground {
            val done = mutableListOf<DeleteEntry>()
            var failure: FileOperationException? = null
            try {
                for (entry in entries) {
                    failure = deleteOne(entry.path)
                    if (failure != null) break
                    done += entry
                }
            } finally {
                val outcome = failure
                host.dispatch {
                    try {
                        outcome?.let { host.showError(it.message.orEmpty()) }
                        // Still the replacement, or lost, not chosen otherwise meanwhile.
                        val selected = host.selectedItems()
                        val keepReplacement =
                            replacement != null &&
                                (selected.none(host.isShown) || selected.singleOrNull() === replacement)
                        // Also after a failure, which may have deleted a part.
                        entries
                            .mapNotNull { it.item.parent as? FileTreeItem }
                            .distinct()
                            .filter(host.isShown)
                            .forEach(FileTreeItem::refresh)
                        host.closeWatchesOfVanishedFolders()
                        // Removing an expanded folder above it loses the selection or moves it to another row.
                        if (keepReplacement && replacement != null && host.isShown(replacement) &&
                            host.selectedItems().singleOrNull() !== replacement
                        ) {
                            host.selectRow(replacement)
                        }
                        done.forEach { host.onDeleted(it.path, it.unsaved) }
                    } finally {
                        deleting.removeAll(paths.toSet())
                    }
                }
            }
        }
    }

    /** Deletes [path] and logs it; returns why it failed, `null` on success. */
    private fun deleteOne(path: Path): FileOperationException? {
        log.log(System.Logger.Level.INFO, "Deleting $path")
        val started = System.nanoTime()
        var failure: FileOperationException? = null
        // Only set once the delete returned: an Error leaves neither a failure nor a success.
        var completed = false
        try {
            FileOperations.delete(path)
            completed = true
        } catch (e: FileOperationException) {
            log.log(System.Logger.Level.WARNING, "Cannot delete $path", e)
            failure = e
        } catch (e: Exception) {
            // Unexpected: the end of the delete must still run, or the entries stay locked.
            log.log(System.Logger.Level.ERROR, "Deleting $path failed unexpectedly", e)
            failure = FileOperationException("Cannot delete “${path.fileName}”: ${e.message ?: e::class.simpleName}", e)
        } finally {
            val millis = (System.nanoTime() - started) / 1_000_000
            if (completed) {
                log.log(System.Logger.Level.INFO, "Deleted $path in $millis ms")
            } else {
                log.log(System.Logger.Level.WARNING, "Delete of $path failed after $millis ms")
            }
        }
        return failure
    }

    /**
     * The row to select when the selection lies at or below [entries]: the first row after the last entry that is not
     * at or below one, else the nearest such row before the first entry, else the folder of the first entry; `null` if
     * the selection stays.
     */
    private fun replacementSelection(entries: List<DeleteEntry>): TreeItem<Path>? {
        fun deleted(item: TreeItem<Path>) = entries.any { item.value.startsWith(it.path) }
        if (host.selectedItems().none(::deleted)) return null
        val rows = host.rows()
        val indices = entries.map { rows.indexOf(it.item) }.filter { it >= 0 }
        val first = indices.minOrNull() ?: return null
        val last = indices.max()
        return rows.drop(last + 1).firstOrNull { !deleted(it) }
            ?: rows.take(first).lastOrNull { !deleted(it) }
            ?: entries.first().item.parent
    }

    private class DeleteEntry(
        val item: FileTreeItem,
        val kind: EntryKind,
        val unsaved: List<Path>,
    ) {
        val path: Path get() = item.sourcePath
    }

    /** The kind of entry at [path], not following a link; `null` if there is none. */
    private fun entryKind(path: Path): EntryKind? =
        when {
            Files.isSymbolicLink(path) -> EntryKind.LINK
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> EntryKind.FOLDER
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) -> EntryKind.FILE
            else -> null
        }
}
