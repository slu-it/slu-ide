package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.FolderAction
import dev.sluide.fileexplorer.tree.FileTreeItem
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
    val runInBackground: (Runnable) -> Unit,
    val dispatch: (Runnable) -> Unit,
    val onMoved: (from: Path, to: Path) -> Unit,
    val unsavedUnder: (Path) -> List<Path>,
    val onDeleted: (path: Path, unsaved: List<Path>) -> Unit,
    val requestOpen: (Path) -> Boolean,
    /** Shows a path of a folder at once, selects and returns it. */
    val select: (parent: FileTreeItem, path: Path) -> TreeItem<Path>?,
    val selectRow: (TreeItem<Path>) -> Unit,
    val selectedItem: () -> TreeItem<Path>?,
    val isShown: (TreeItem<Path>) -> Boolean,
    /** Ends the watches of the folders at or below a path, which moved. */
    val closeWatchesUnder: (Path) -> Unit,
    val closeWatchesOfVanishedFolders: () -> Unit,
)

/** The file operations of the explorer: create, rename and delete entries, and run actions on folders. */
internal class ExplorerEdits(
    private val host: ExplorerEditHost,
) {
    /** The entries being deleted; they and everything in them cannot be renamed or deleted meanwhile. */
    private val deleting = mutableSetOf<Path>()

    /**
     * Asks for a name and creates a file or [folder] of that name in [target] if it is a folder, else beside it; in the
     * project folder for `null`. The new entry is selected, and a new file opened.
     */
    fun create(
        target: FileTreeItem?,
        folder: Boolean,
    ) {
        val parent = targetFolder(target) ?: return
        val name =
            host.askName(if (folder) "New Folder" else "New File", "") { FileOperations.nameProblem(it, parent.value) }
                ?: return
        val operation = if (folder) FileOperations::createFolder else FileOperations::createFile
        val created =
            try {
                operation(parent.value, name)
            } catch (e: FileOperationException) {
                host.showError(e.message.orEmpty())
                return
            }
        host.select(parent, created)
        if (!folder) host.requestOpen(created)
    }

    /**
     * The folder an action on [target] works in: [target] if a folder, else its folder; the project folder for `null`.
     */
    fun targetFolder(target: FileTreeItem?): FileTreeItem? {
        val item = target ?: host.projectItem() ?: return null
        return if (item.isDirectory) item else item.parent as? FileTreeItem
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
     * Whether [item] can be renamed or deleted: an entry of a top-level folder, not a top-level folder itself (their
     * parent is the hidden root), and not being deleted.
     */
    fun canChange(item: TreeItem<Path>?): Boolean =
        item is FileTreeItem && item.parent is FileTreeItem && deleting.none { item.value.startsWith(it) }

    /**
     * Asks for a new name of [item] and renames it in its folder, reporting it to the `onMoved` hook. The renamed entry
     * is selected, a folder stays expanded if it was.
     */
    fun rename(item: FileTreeItem) {
        val parent = item.parent as? FileTreeItem ?: return
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
     * Asks whether to delete [item], warning about the files with `unsavedUnder` that would be lost, and deletes it
     * permanently in the background, reporting it to the `onDeleted` hook. If it is selected, the entry after it (or
     * before it, or its folder) is selected right away, before the folder's watch may remove it.
     */
    fun delete(item: FileTreeItem) {
        val parent = item.parent as? FileTreeItem ?: return
        val path = item.value
        val kind = entryKind(path) ?: return
        val unsaved = host.unsavedUnder(path)
        if (!host.confirmDelete(deleteQuestion(path, kind, unsaved.size))) return
        // The question was about this kind of entry, e.g. not about a folder with everything in it.
        if (entryKind(path) != kind) {
            host.showError("“${path.fileName}” changed while asking and was not deleted.")
            parent.refresh()
            return
        }
        if (!canChange(item)) return
        val replacement =
            if (host.selectedItem()?.value?.startsWith(path) == true) {
                val siblings = parent.children
                val index = siblings.indexOf(item)
                (siblings.getOrNull(index + 1) ?: siblings.getOrNull(index - 1) ?: parent).also(host.selectRow)
            } else {
                null
            }
        deleting.add(path)
        host.runInBackground {
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
                // Unexpected: the end of the delete must still run, or the entry stays locked.
                log.log(System.Logger.Level.ERROR, "Deleting $path failed unexpectedly", e)
                failure =
                    FileOperationException("Cannot delete “${path.fileName}”: ${e.message ?: e::class.simpleName}", e)
            } finally {
                // Also after an Error, which the thread logs: the entry must not stay locked.
                val millis = (System.nanoTime() - started) / 1_000_000
                if (completed) {
                    log.log(System.Logger.Level.INFO, "Deleted $path in $millis ms")
                } else {
                    log.log(System.Logger.Level.WARNING, "Delete of $path failed after $millis ms")
                }
                val outcome = failure
                val succeeded = completed
                host.dispatch {
                    try {
                        outcome?.let { host.showError(it.message.orEmpty()) }
                        // Also after a failure, which may have deleted a part.
                        if (host.isShown(parent)) {
                            parent.refresh()
                            host.closeWatchesOfVanishedFolders()
                            // Removing an expanded folder above it can lose the selection.
                            val selected = host.selectedItem()
                            if (replacement != null && host.isShown(replacement) &&
                                (selected == null || !host.isShown(selected))
                            ) {
                                host.selectRow(replacement)
                            }
                        }
                        if (succeeded) host.onDeleted(path, unsaved)
                    } finally {
                        deleting.remove(path)
                    }
                }
            }
        }
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
