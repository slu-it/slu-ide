package dev.sluide.fileexplorer

import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import javafx.beans.InvalidationListener
import javafx.beans.value.ObservableValue
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import org.kordamp.ikonli.javafx.FontIcon
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * Tree view showing the complete content of a folder, including hidden files. Every folder whose content has been
 * loaded is watched, so changes on disk show up by themselves; "Refresh" (context menu, F5) re-reads all of them.
 * Every entry has a [FileIcon], coloured by type in `file-explorer.css` and sized to the explorer font.
 */
@Component
class FileExplorerView(
    private val fileWatcher: FileWatcher,
    fontSettings: FontSettings,
) : TreeView<Path>() {
    /** Called with a file the user wants to open (double-click or Enter). Never called for folders. */
    var onOpenFile: ((Path) -> Unit)? = null

    /**
     * Whether a file can be opened (e.g. by a file viewer), which decides between the text and binary icon for files
     * without a known extension. It may read the file; it is asked once per file and again after the file changed.
     */
    var canOpenFile: (Path) -> Boolean = { true }

    private val watches = mutableMapOf<Path, AutoCloseable>()

    // Material Design icons leave a margin around the glyph, so they are a bit larger than the text.
    private val fileIconSize: ObservableValue<Int> =
        fontSettings.font(FontArea.EXPLORER).map { (it.size * ICON_SCALE).roundToInt() }

    init {
        setCellFactory { PathCell() }
        // The cells inherit the font.
        styleProperty().bind(fontSettings.font(FontArea.EXPLORER).map(FontSpec::toCss))
        stylesheets += checkNotNull(FileExplorerView::class.java.getResource("file-explorer.css")).toExternalForm()
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
        // A changed file may have become readable or binary.
        folder.children
            .filterIsInstance<FileTreeItem>()
            .filter { !it.isDirectory && it.value in changed }
            .forEach { it.readable.value = null }
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
        private val icon = FontIcon().apply { iconSizeProperty().bind(fileIconSize) }
        private val iconUpdater = InvalidationListener { updateIcon() }

        init {
            // Folders open and close, and readability is checked later or reset, without a call to updateItem.
            treeItemProperty().addListener { _, old, new ->
                (old as? FileTreeItem)?.let {
                    it.expandedProperty().removeListener(iconUpdater)
                    it.readable.removeListener(iconUpdater)
                }
                (new as? FileTreeItem)?.let {
                    it.expandedProperty().addListener(iconUpdater)
                    it.readable.addListener(iconUpdater)
                }
            }
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
            updateIcon()
        }

        private fun updateIcon() {
            val treeItem: TreeItem<Path>? = treeItem
            if (isEmpty || treeItem !is FileTreeItem) {
                graphic = null
                return
            }
            val fileIcon =
                fileIconFor(treeItem.value, treeItem.isDirectory, treeItem.isExpanded) {
                    treeItem.readable.value ?: canOpenFile(treeItem.value).also { treeItem.readable.value = it }
                }
            icon.iconCode = fileIcon.ikon
            icon.styleClass.removeIf { it.startsWith(FILE_ICON_CLASS_PREFIX) }
            icon.styleClass += fileIcon.styleClass
            graphic = icon
        }
    }
}

private const val ICON_SCALE = 1.25
private const val FILE_ICON_CLASS_PREFIX = "file-icon-"
