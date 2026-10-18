package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.fileicons.fileIconFor
import dev.sluide.fileicons.show
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.beans.WeakInvalidationListener
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.ContentDisplay
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.shape.Circle
import javafx.scene.text.Text
import org.kordamp.ikonli.javafx.FontIcon
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * Tree view showing the complete content of a folder, including hidden files. Every folder whose content has been
 * loaded is watched, so changes on disk show up by themselves; "Refresh" (context menu, F5) re-reads all of them.
 * Every entry has a [FileIcon], coloured by type in `file-icons.css` and sized to the explorer font, and is marked
 * as given by [markers]: a coloured name and bubbles after it.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class FileExplorerView(
    private val fileWatcher: FileWatcher,
    fontSettings: FontSettings,
) : TreeView<Path>() {
    /** Called with a file the user wants to open (double-click or Enter). Never called for folders. */
    var onOpenFile: (Path) -> Unit = {}

    /**
     * Whether a file can be opened (e.g. by a file viewer), which decides between the text and binary icon for files
     * without a known extension. It may read the file; it is asked once per file and again after the file changed.
     */
    var canOpenFile: (Path) -> Boolean = { true }

    /**
     * How a file or folder (`directory`) is marked: the colour of its name and the bubbles after it. Asked whenever
     * an entry is shown; call [refreshMarkers] when the answers change.
     */
    var markers: (path: Path, directory: Boolean) -> FileMarkers = { _, _ -> FileMarkers.NONE }

    /** Called after files changed on disk in a loaded folder, and after a manual refresh. */
    var onFilesChanged: () -> Unit = {}

    /**
     * Called after a file or folder was renamed in the explorer, with its old and new path. A folder's content moved
     * along with it.
     */
    var onMoved: (from: Path, to: Path) -> Unit = { _, _ -> }

    /**
     * The files at or below a path that have unsaved changes; asked before the path is deleted, so the confirmation
     * can warn that they will be lost.
     */
    var unsavedUnder: (Path) -> List<Path> = { emptyList() }

    /**
     * Called after a file or folder was deleted in the explorer, a folder with everything in it, with the files with
     * unsaved changes the confirmation said would be lost ([unsavedUnder] when it asked). Not called if deleting failed,
     * even if it deleted a part of a folder.
     */
    var onDeleted: (path: Path, unsaved: List<Path>) -> Unit = { _, _ -> }

    /**
     * Shows the history of a file, e.g. in the git log area (ADR 0046); `null` leaves Show History out of the context
     * menu. Set by the window.
     */
    var showHistory: ((Path) -> Unit)? = null

    /** Whether Show History is enabled for a file (never for folders); asked when the context menu opens. */
    var canShowHistory: (Path) -> Boolean = { false }

    /** Asks whether to delete an entry; shows a confirmation dialog, tests replace it, since it blocks. */
    internal var confirmDelete: (DeleteQuestion) -> Boolean = { askToDelete(scene?.window, it) }

    /** Runs a delete, which may take long (e.g. `node_modules`), away from the JavaFX thread; tests replace it. */
    internal var runInBackground: (Runnable) -> Unit = { Thread.ofVirtual().name("sluide-delete").start(it) }

    /** Runs the end of a delete on the JavaFX thread; tests replace it. */
    internal var dispatch: (Runnable) -> Unit = Platform::runLater

    /** The entries being deleted; they and everything in them cannot be renamed or deleted meanwhile. */
    private val deleting = mutableSetOf<Path>()

    /**
     * Asks for a name, starting with `initial`, while `validate` tells what is wrong with it; `null` if cancelled.
     * Shows a dialog; tests replace it, since it blocks.
     */
    internal var askName: (title: String, initial: String, validate: (String) -> String?) -> String? =
        { title, initial, validate -> askForName(scene?.window, title, initial, validate) }

    /** Shows an error message and waits until it is closed; tests replace it, since it blocks. */
    internal var showError: (String) -> Unit = { message ->
        Alert(Alert.AlertType.ERROR, message)
            .apply {
                scene?.window?.let(::initOwner)
                headerText = null
            }.showAndWait()
    }

    /** The entry the context menu was opened for; `null` for the empty area, which stands for the opened folder. */
    private var menuTarget: FileTreeItem? = null

    /** Counts calls of [refreshMarkers]; the cells listen to it. */
    private val markerChanges = SimpleIntegerProperty()

    private val watches = mutableMapOf<Path, AutoCloseable>()

    private val font = fontSettings.font(FontArea.EXPLORER)

    // Material Design icons leave a margin around the glyph, so they are a bit larger than the text.
    private val fileIconSize: ObservableValue<Int> = font.map { (it.size * ICON_SCALE).roundToInt() }

    init {
        setCellFactory { PathCell() }
        // The cells inherit the font.
        styleProperty().bind(font.map(FontSpec::toCss))
        stylesheets += checkNotNull(FileExplorerView::class.java.getResource("file-explorer.css")).toExternalForm()
        stylesheets += FILE_ICONS_CSS
        addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            val selected = selectionModel.selectedItem?.value
            val changeable = (selectionModel.selectedItem as? FileTreeItem)?.takeIf(::canChange)
            when {
                event.code == KeyCode.ENTER && selected != null && requestOpen(selected) -> {
                    event.consume()
                }

                event.code == KeyCode.F5 -> {
                    refreshAll()
                    event.consume()
                }

                event.code == KeyCode.F2 && changeable != null -> {
                    rename(changeable)
                    event.consume()
                }

                // On macOS, Cmd+Backspace, as in the Finder: Mac keyboards often have no Delete key.
                (event.code == KeyCode.DELETE || (event.code == KeyCode.BACK_SPACE && event.isShortcutDown)) &&
                    changeable != null -> {
                    delete(changeable)
                    event.consume()
                }
            }
        }
        contextMenu = fileMenu()
        // Cells for entries set the target afterwards; a menu opened by keyboard is for the selected entry.
        addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED) { event ->
            menuTarget = if (event.isKeyboardTrigger) selectionModel.selectedItem as? FileTreeItem else null
        }
    }

    private fun fileMenu(): ContextMenu {
        val newFile = MenuItem("New File…").apply { setOnAction { create(menuTarget, folder = false) } }
        val newFolder = MenuItem("New Folder…").apply { setOnAction { create(menuTarget, folder = true) } }
        val rename = MenuItem("Rename…").apply { setOnAction { menuTarget?.let(::rename) } }
        val delete = MenuItem("Delete…").apply { setOnAction { menuTarget?.let(::delete) } }
        val history =
            MenuItem(
                "Show History",
            ).apply { setOnAction { historyTarget()?.let { showHistory?.invoke(it) } } }
        val historySeparator = SeparatorMenuItem()
        return ContextMenu(
            newFile,
            newFolder,
            SeparatorMenuItem(),
            rename,
            delete,
            historySeparator,
            history,
            SeparatorMenuItem(),
            MenuItem("Refresh").apply { setOnAction { refreshAll() } },
        ).apply {
            setOnShowing {
                newFile.isDisable = root == null
                newFolder.isDisable = root == null
                rename.isDisable = !canChange(menuTarget)
                delete.isDisable = !canChange(menuTarget)
                history.isVisible = showHistory != null
                historySeparator.isVisible = showHistory != null
                history.isDisable = historyTarget()?.let(canShowHistory) != true
            }
        }
    }

    /** The file the context menu was opened for, for Show History; `null` for a folder or the empty area. */
    private fun historyTarget(): Path? = menuTarget?.takeIf { !it.isDirectory }?.value

    /** Shows [root] and its content; `null` clears the view. */
    fun open(root: Path?) {
        watches.values.forEach(AutoCloseable::close)
        watches.clear()
        this.root = root?.let { FileTreeItem(it, ::watch).apply { isExpanded = true } }
    }

    /**
     * Reads every loaded folder again, checks again whether its files can be opened and renews its watch, e.g. if a
     * change on disk was missed.
     */
    fun refreshAll() {
        (root as? FileTreeItem)?.loadedFolders()?.forEach { folder ->
            folder.refresh()
            folder.forgetReadability(listOf(folder.value))
            watch(folder)
        }
        closeWatchesOfVanishedFolders()
        onFilesChanged()
    }

    /**
     * The width the explorer would like to start with: enough for the names of the shown entries with their icons and
     * indentation, in the explorer font, but at least [MIN_START_WIDTH] and at most [MAX_START_WIDTH] times its size,
     * so that a few short names still leave room and a long one does not take the window.
     */
    fun startWidth(): Double {
        val spec = font.value
        val text = Text().apply { font = spec.toFont() }
        val max = MAX_START_WIDTH * spec.size
        var width = MIN_START_WIDTH * spec.size
        // Stops once a name needs the largest width, so a folder with thousands of entries is not measured in full.
        for ((item, level) in shownEntries(root, 0)) {
            text.text = (item.value.fileName ?: item.value).toString()
            width = maxOf(width, (level + ROW_EXTRA) * spec.size + text.layoutBounds.width)
            if (width >= max) return max
        }
        return width
    }

    /** [item] at [level] and, if it is expanded, its entries below it, in the order shown. */
    private fun shownEntries(
        item: TreeItem<Path>?,
        level: Int,
    ): Sequence<Pair<TreeItem<Path>, Int>> =
        sequence {
            if (item == null) return@sequence
            yield(item to level)
            if (item.isExpanded) item.children.forEach { yieldAll(shownEntries(it, level + 1)) }
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
        folder.forgetReadability(changed)
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

    /**
     * Asks for a name and creates a file or [folder] of that name in [target] if it is a folder, else beside it; in the
     * opened folder for `null`. The new entry is selected, and a new file opened.
     */
    internal fun create(
        target: FileTreeItem?,
        folder: Boolean,
    ) {
        val item = target ?: root as? FileTreeItem ?: return
        val parent = if (item.isDirectory) item else item.parent as? FileTreeItem ?: return
        val name =
            askName(if (folder) "New Folder" else "New File", "") { FileOperations.nameProblem(it, parent.value) }
                ?: return
        val operation = if (folder) FileOperations::createFolder else FileOperations::createFile
        val created =
            try {
                operation(parent.value, name)
            } catch (e: FileOperationException) {
                showError(e.message.orEmpty())
                return
            }
        select(parent, created)
        if (!folder) requestOpen(created)
    }

    /**
     * Whether [item] can be renamed or deleted: an entry of the opened folder, not the folder itself, and not being
     * deleted.
     */
    private fun canChange(item: TreeItem<Path>?): Boolean =
        item is FileTreeItem && item.parent is FileTreeItem && deleting.none { item.value.startsWith(it) }

    /**
     * Asks for a new name of [item] and renames it in its folder, reporting it to [onMoved]. The renamed entry is
     * selected, a folder stays expanded if it was.
     */
    internal fun rename(item: FileTreeItem) {
        val parent = item.parent as? FileTreeItem ?: return
        val path = item.value
        val name =
            askName("Rename", path.fileName.toString()) {
                FileOperations.nameProblem(it, parent.value, renaming = path)
            }
                ?: return
        val renamed =
            try {
                FileOperations.rename(path, name)
            } catch (e: FileOperationException) {
                showError(e.message.orEmpty())
                return
            }
        if (renamed == path) return
        // The folder and its loaded subfolders have new paths, watched once they are loaded again.
        watches.keys.filter { it.startsWith(path) }.forEach { watches.remove(it)?.close() }
        onMoved(path, renamed)
        select(parent, renamed)?.isExpanded = item.isExpanded
    }

    /**
     * Asks whether to delete [item], warning about the files with [unsavedUnder] that would be lost, and deletes it
     * permanently in the background, reporting it to [onDeleted]. If it is selected, the entry after it (or before it,
     * or its folder) is selected right away, before the folder's watch may remove it.
     */
    internal fun delete(item: FileTreeItem) {
        val parent = item.parent as? FileTreeItem ?: return
        val path = item.value
        val kind = entryKind(path) ?: return
        val unsaved = unsavedUnder(path)
        if (!confirmDelete(deleteQuestion(path, kind, unsaved.size))) return
        // The question was about this kind of entry, e.g. not about a folder with everything in it.
        if (entryKind(path) != kind) {
            showError("“${path.fileName}” changed while asking and was not deleted.")
            parent.refresh()
            return
        }
        if (!canChange(item)) return
        val replacement =
            if (selectionModel.selectedItem?.value?.startsWith(path) == true) {
                val siblings = parent.children
                val index = siblings.indexOf(item)
                (siblings.getOrNull(index + 1) ?: siblings.getOrNull(index - 1) ?: parent).also(::selectRow)
            } else {
                null
            }
        deleting.add(path)
        runInBackground {
            val failure =
                try {
                    FileOperations.delete(path)
                    null
                } catch (e: FileOperationException) {
                    e
                }
            dispatch {
                deleting.remove(path)
                failure?.let { showError(it.message.orEmpty()) }
                // Also after a failure, which may have deleted a part.
                if (isShown(parent)) {
                    parent.refresh()
                    closeWatchesOfVanishedFolders()
                    // Removing an expanded folder above it can lose the selection.
                    val selected = selectionModel.selectedItem
                    if (replacement != null && isShown(replacement) && (selected == null || !isShown(selected))) {
                        selectRow(replacement)
                    }
                }
                if (failure == null) onDeleted(path, unsaved)
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

    /** Whether [item] is still in the tree, e.g. not gone with a folder opened since. */
    private fun isShown(item: TreeItem<Path>): Boolean = generateSequence(item) { it.parent }.last() === root

    /** Shows [path], an entry of [parent], at once, without waiting for the watch, selects and returns it. */
    private fun select(
        parent: FileTreeItem,
        path: Path,
    ): TreeItem<Path>? {
        parent.isExpanded = true
        // Reads the folder for the first time, or again if it was loaded.
        parent.refresh()
        val item = parent.children.firstOrNull { it.value == path } ?: return null
        selectRow(item)
        return item
    }

    /** Selects and focuses [item], scrolling only as far as needed to show it. */
    private fun selectRow(item: TreeItem<Path>) {
        val row = getRow(item)
        selectionModel.clearAndSelect(row)
        // Rows inserted or removed before the focused one move the focus later, away from the selection.
        Platform.runLater { focusModel.focus(getRow(item)) }
        (lookup(".virtual-flow") as? VirtualFlow<*>)?.scrollTo(row) ?: scrollTo(row)
    }

    /** Passes [path] to [onOpenFile] if it is a regular file; returns whether it did. */
    internal fun requestOpen(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        onOpenFile(path)
        return true
    }

    private inner class PathCell : TreeCell<Path>() {
        private val icon = FontIcon().apply { iconSizeProperty().bind(fileIconSize) }
        private val iconUpdater = InvalidationListener { updateIcon() }

        // The name is a label in the graphic, so the markers can follow it; it looks like the cell's own text unless
        // a marker colours it.
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
                    styleClass += listOf(FILE_MARKER_CLASS, marker.bubbleStyleClass)
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

        /** The marker colouring the name, if any; not shown on the selection bar, where it would be hard to read. */
        private var nameMarker: FileMarker? = null

        init {
            // The text stays set (accessibility, lookups by name) but only the graphic is shown.
            contentDisplay = ContentDisplay.GRAPHIC_ONLY
            markerChanges.addListener(WeakInvalidationListener(markerUpdater))
            selectedProperty().addListener { _ -> updateNameColour() }
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
            addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED) {
                if (!isEmpty) menuTarget = treeItem as? FileTreeItem
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
            icon.show(fileIcon)
            graphic = content
        }

        private fun updateMarkers() {
            val treeItem: TreeItem<Path>? = treeItem
            val shown =
                if (isEmpty || treeItem !is FileTreeItem) {
                    FileMarkers.NONE
                } else {
                    markers(treeItem.value, treeItem.isDirectory)
                }
            bubbles.children.setAll(shown.bubbles.distinct().map(bubbleOf::getValue))
            bubbles.isVisible = shown.bubbles.isNotEmpty()
            nameMarker = shown.name
            updateNameColour()
        }

        // CSS cannot set a bound property, so a coloured name is unbound from the cell's text fill.
        private fun updateNameColour() {
            val marker = nameMarker
            name.styleClass.removeAll(nameStyleClasses)
            if (marker == null || isSelected) {
                name.textFillProperty().bind(textFillProperty())
            } else {
                name.textFillProperty().unbind()
                name.styleClass += marker.nameStyleClass
            }
        }
    }
}

private const val ICON_SCALE = 1.25

// The start width in multiples of the font size: the bounds, and what a row needs besides its indentation (Modena's
// 1 em per level) and name: the disclosure arrow, the icon, the gaps and padding, a marker bubble or two and the
// vertical scroll bar.
private const val MIN_START_WIDTH = 12.0
private const val MAX_START_WIDTH = 24.0
private const val ROW_EXTRA = 6.0

private const val FILE_MARKER_CLASS = "file-marker"
private val nameStyleClasses = FileMarker.entries.map(FileMarker::nameStyleClass)

// Bubble radius relative to the icon size, and the gap between bubbles in pixels.
private const val BUBBLE_SCALE = 0.2
private const val BUBBLE_GAP = 3.0
