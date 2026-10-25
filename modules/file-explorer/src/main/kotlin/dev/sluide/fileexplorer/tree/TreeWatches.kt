package dev.sluide.fileexplorer.tree

import dev.sluide.filewatcher.FileWatcher
import javafx.scene.control.TreeItem
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap

/**
 * The watches of the explorer's tree: every loaded folder is watched and read again when it changes, and so is every
 * middle folder of a compacted row (ADR 0096): a change there reads the row's parent folder again, which splits the row
 * or joins it with its folders. A folder that changed so that its row stands for other folders now (it holds only a
 * folder now, or not any more) has its parent read again, too. Watches belong to tree items, so a folder shown twice
 * (an extra root inside the project) is watched by each; they end with the item ([closeVanished]).
 */
internal class TreeWatches(
    private val fileWatcher: FileWatcher,
    /** Whether an item is still in the tree. */
    private val isShown: (TreeItem<Path>) -> Boolean,
    /** Runs a change of the tree, selecting the entries again that were selected if their rows were replaced. */
    private val keepSelection: (() -> Unit) -> Unit,
    /** Called with the changed paths after a watched folder changed. */
    private val onFilesChanged: (Set<Path>) -> Unit,
) {
    // By item, not path: a folder can be shown twice (e.g. an extra root inside the project), each copy watched.
    private val folders = IdentityHashMap<FileTreeItem, AutoCloseable>()

    /** The watches of the middle folders of compacted rows, one handle for all folders of a row. */
    private val middles = IdentityHashMap<FileTreeItem, AutoCloseable>()

    /**
     * Watches [folder] anew, if it is shown, and the middle folders of the compacted rows in it; a compacted [folder]
     * watches its own middle folders anew, too.
     */
    fun watch(folder: FileTreeItem) {
        if (!isShown(folder)) return
        folders.remove(folder)?.close()
        folders[folder] = fileWatcher.watch(folder.value) { changed -> onFolderChanged(folder, changed) }
        middles.remove(folder)?.close()
        watchMiddles(folder)
        watchChildMiddles(folder)
    }

    /** Watches the middle folders of the compacted rows among the loaded children of [folder] not watched yet. */
    fun watchChildMiddles(folder: FileTreeItem) {
        if (!folder.isLoaded || !isShown(folder)) return
        folder.children.filterIsInstance<FileTreeItem>().forEach(::watchMiddles)
    }

    /** Watches the middle folders of [row] anew, e.g. after they were deleted and recreated (a watch ends then). */
    private fun renewMiddles(row: FileTreeItem) {
        middles.remove(row)?.close()
        watchMiddles(row)
    }

    private fun watchMiddles(row: FileTreeItem) {
        if (!row.isCompacted || row in middles) return
        val handles = row.chain.dropLast(1).map { fileWatcher.watch(it) { changed -> onMiddleChanged(row, changed) } }
        middles[row] = AutoCloseable { handles.forEach(AutoCloseable::close) }
    }

    private fun onFolderChanged(
        folder: FileTreeItem,
        changed: List<Path>,
    ) {
        // A callback queued before the folder left the tree, e.g. by open.
        if (!isShown(folder)) return
        keepSelection {
            folder.refresh()
            folder.forgetReadability(changed)
            // The folder itself is reported when events were lost or it was deleted; watch it anew if it is there.
            if (folder.value in changed && Files.isDirectory(folder.value)) watch(folder)
            // A loaded subfolder that was deleted and recreated (e.g. build/) has new content and needs a new watch.
            folder.children
                .filterIsInstance<FileTreeItem>()
                .filter { it.isLoaded && it.sourcePath in changed }
                .forEach { subfolder ->
                    subfolder.refresh()
                    watch(subfolder)
                }
            // The same for the middle folders of a compacted row that was never expanded (watch renews the others).
            folder.children
                .filterIsInstance<FileTreeItem>()
                .filter { it.isCompacted && !it.isLoaded && it.sourcePath in changed }
                .forEach(::renewMiddles)
            watchChildMiddles(folder)
            // E.g. the last file beside its only folder is gone: the row joins that folder. Only a folder now listing a
            // single folder can join it, so the others skip reading the chain again.
            if (folder.children.singleOrNull()?.let { (it as? FileTreeItem)?.isDirectory } == true) {
                (folder.parent as? FileTreeItem)?.takeIf { folder.isChainStale() }?.let(::refreshParent)
            }
            closeVanished()
        }
        onFilesChanged(changed.toSet())
    }

    private fun onMiddleChanged(
        row: FileTreeItem,
        changed: List<Path>,
    ) {
        if (!isShown(row)) return
        val parent = row.parent as? FileTreeItem ?: return
        keepSelection {
            refreshParent(parent)
            // A middle folder deleted and recreated (the chain the same, the row kept) ended its watch.
            if (isShown(row)) renewMiddles(row)
            closeVanished()
        }
        onFilesChanged(changed.toSet())
    }

    private fun refreshParent(parent: FileTreeItem) {
        parent.refresh()
        watchChildMiddles(parent)
    }

    /** Ends every watch. */
    fun closeAll() {
        (folders.values + middles.values).forEach(AutoCloseable::close)
        folders.clear()
        middles.clear()
    }

    /** Ends the watches of the folders at or below [path], which moved, and of the rows with such a folder. */
    fun closeUnder(path: Path) {
        folders.keys.filter { it.value.startsWith(path) }.forEach { folders.remove(it)?.close() }
        middles.keys.filter { row -> row.chain.any { it.startsWith(path) } }.forEach { middles.remove(it)?.close() }
    }

    /** Ends the watches of folders deleted or no longer in the tree (the parent's refresh removes them). */
    fun closeVanished() {
        folders.keys.filter { !Files.isDirectory(it.value) || !isShown(it) }.forEach { folders.remove(it)?.close() }
        middles.keys.filter { !isShown(it) }.forEach { middles.remove(it)?.close() }
    }
}
