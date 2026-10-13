package dev.sluide.fileexplorer

import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import javafx.beans.InvalidationListener
import javafx.beans.WeakInvalidationListener
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.control.ContentDisplay
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.shape.Circle
import org.kordamp.ikonli.javafx.FontIcon
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * Tree view showing the complete content of a folder, including hidden files. Every folder whose content has been
 * loaded is watched, so changes on disk show up by themselves; "Refresh" (context menu, F5) re-reads all of them.
 * Every entry has a [FileIcon], coloured by type in `file-explorer.css` and sized to the explorer font, and the
 * [FileMarker]s given by [markers] after its name.
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

    /**
     * The markers shown after the name of a file or folder (`directory`), in the order given. Asked whenever an
     * entry is shown; call [refreshMarkers] when the answers change.
     */
    var markers: (path: Path, directory: Boolean) -> List<FileMarker> = { _, _ -> emptyList() }

    /** Called after files changed on disk in a loaded folder, and after a manual refresh. */
    var onFilesChanged: () -> Unit = {}

    /** Counts calls of [refreshMarkers]; the cells listen to it. */
    private val markerChanges = SimpleIntegerProperty()

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
        onFilesChanged()
    }

    /** Asks [markers] again for every shown entry. */
    fun refreshMarkers() {
        markerChanges.set(markerChanges.get() + 1)
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
        onFilesChanged()
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

        // The name is a label in the graphic, so the markers can follow it; it looks like the cell's own text.
        private val name =
            Label().apply {
                fontProperty().bind(this@PathCell.fontProperty())
                textFillProperty().bind(this@PathCell.textFillProperty())
            }
        private val bubbleRadius = fileIconSize.map { it * BUBBLE_SCALE }

        // One bubble per marker, reused; without markers the box takes no space.
        private val bubbleOf =
            FileMarker.entries.associateWith { marker ->
                Circle().apply {
                    radiusProperty().bind(bubbleRadius)
                    styleClass += listOf(FILE_MARKER_CLASS, marker.styleClass)
                }
            }
        private val bubbles =
            HBox(BUBBLE_GAP).apply {
                alignment = Pos.CENTER_LEFT
                managedProperty().bind(visibleProperty())
            }
        private val content =
            HBox(icon, name, bubbles).apply {
                alignment = Pos.CENTER_LEFT
                spacingProperty().bind(this@PathCell.graphicTextGapProperty())
            }
        private val markerUpdater = InvalidationListener { updateMarkers() }

        init {
            // The text stays set (accessibility, lookups by name) but only the graphic is shown.
            contentDisplay = ContentDisplay.GRAPHIC_ONLY
            markerChanges.addListener(WeakInvalidationListener(markerUpdater))
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
            name.text = text
            updateIcon()
            updateMarkers()
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
            graphic = content
        }

        private fun updateMarkers() {
            val treeItem: TreeItem<Path>? = treeItem
            val shown =
                if (isEmpty ||
                    treeItem !is FileTreeItem
                ) {
                    emptyList()
                } else {
                    markers(treeItem.value, treeItem.isDirectory)
                }
            bubbles.children.setAll(shown.distinct().map(bubbleOf::getValue))
            bubbles.isVisible = shown.isNotEmpty()
        }
    }
}

private const val ICON_SCALE = 1.25
private const val FILE_ICON_CLASS_PREFIX = "file-icon-"
private const val FILE_MARKER_CLASS = "file-marker"

// Bubble radius relative to the icon size, and the gap between bubbles in pixels.
private const val BUBBLE_SCALE = 0.2
private const val BUBBLE_GAP = 3.0
