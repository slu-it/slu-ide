package dev.sluide.fileexplorer

import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
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
    fontSettings: FontSettings,
) : TreeView<Path>() {
    /** Called with a file the user wants to open (double-click or Enter). Never called for folders. */
    var onOpenFile: ((Path) -> Unit)? = null

    private val watches = mutableMapOf<Path, AutoCloseable>()

    init {
        setCellFactory { PathCell() }
        // The cells inherit the font.
        styleProperty().bind(fontSettings.font(FontArea.EXPLORER).map(FontSpec::toCss))
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

    /** Reads every loaded folder again and renews its watch, e.g. if a change on disk was missed. */
    fun refreshAll() {
        (root as? FileTreeItem)?.loadedFolders()?.forEach { folder ->
            folder.refresh()
            watch(folder)
        }
        closeWatchesOfVanishedFolders()
    }

    private fun watch(folder: FileTreeItem) {
        val path = folder.value
        watches.remove(path)?.close()
        watches[path] = fileWatcher.watch(path) { changed -> onFolderChanged(folder, changed) }
    }

    private fun onFolderChanged(
        folder: FileTreeItem,
        changed: List<Path>,
    ) {
        folder.refresh()
        // The folder itself is reported when events were lost or it was deleted; watch it anew if it is there.
        if (folder.value in changed && Files.isDirectory(folder.value)) watch(folder)
        // A loaded subfolder that was deleted and recreated (e.g. build/) has new content and needs a new watch.
        folder.children
            .filterIsInstance<FileTreeItem>()
            .filter { it.isLoaded && it.value in changed }
            .forEach { subfolder ->
                subfolder.refresh()
                watch(subfolder)
            }
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
