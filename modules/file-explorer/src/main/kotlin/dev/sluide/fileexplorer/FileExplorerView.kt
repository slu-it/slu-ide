package dev.sluide.fileexplorer

import dev.sluide.filewatcher.FileWatcher
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tree view showing the complete content of a folder, including hidden files. Every folder whose content has been
 * loaded is watched, so changes on disk show up by themselves; "Refresh" (context menu, F5) re-reads all of them.
 */
@Component
class FileExplorerView(
    private val fileWatcher: FileWatcher,
) : TreeView<Path>() {
    /** Called with a file the user wants to open (double-click or Enter). Never called for folders. */
    var onOpenFile: ((Path) -> Unit)? = null

    private val watches = mutableMapOf<Path, AutoCloseable>()

    init {
        setCellFactory { PathCell() }
        addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            val selected = selectionModel.selectedItem?.value
            when {
                event.code == KeyCode.ENTER && selected != null && requestOpen(selected) -> {
                    event.consume()
                }

                event.code == KeyCode.F5 -> {
                    refreshAll()
                    event.consume()
                }
            }
        }
        contextMenu = ContextMenu(MenuItem("Refresh").apply { setOnAction { refreshAll() } })
    }

    /** Shows [root] and its content; `null` clears the view. */
    fun open(root: Path?) {
        watches.values.forEach(AutoCloseable::close)
        watches.clear()
        this.root = root?.let { FileTreeItem(it, ::watch).apply { isExpanded = true } }
    }

    /** Reads every loaded folder again, e.g. if a change on disk was missed. */
    fun refreshAll() {
        (root as? FileTreeItem)?.loadedFolders()?.forEach(FileTreeItem::refresh)
        closeWatchesOfVanishedFolders()
    }

    private fun watch(folder: FileTreeItem) {
        val path = folder.value
        watches.remove(path)?.close()
        watches[path] = fileWatcher.watch(path) { onFolderChanged(folder) }
    }

    private fun onFolderChanged(folder: FileTreeItem) {
        folder.refresh()
        closeWatchesOfVanishedFolders()
    }

    /** Deleted folders are removed from the tree by their parent's refresh; their watches end here. */
    private fun closeWatchesOfVanishedFolders() {
        watches.keys.filterNot(Files::isDirectory).forEach { watches.remove(it)?.close() }
    }

    /** Passes [path] to [onOpenFile] if it is a regular file; returns whether it did. */
    internal fun requestOpen(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        onOpenFile?.invoke(path)
        return true
    }

    private inner class PathCell : TreeCell<Path>() {
        init {
            setOnMouseClicked { event ->
                val path = item
                if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && !isEmpty && path != null) {
                    requestOpen(path)
                }
            }
        }

        override fun updateItem(
            item: Path?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else (item.fileName ?: item).toString()
        }
    }
}
