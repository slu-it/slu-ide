package dev.sluide.fileexplorer

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileexplorer.actions.DeleteQuestion
import dev.sluide.fileexplorer.actions.ExplorerEditHost
import dev.sluide.fileexplorer.actions.ExplorerEdits
import dev.sluide.fileexplorer.actions.ExplorerMenuHost
import dev.sluide.fileexplorer.actions.ExplorerMoves
import dev.sluide.fileexplorer.actions.MoveQuestion
import dev.sluide.fileexplorer.actions.askForName
import dev.sluide.fileexplorer.actions.askToDelete
import dev.sluide.fileexplorer.actions.askToMove
import dev.sluide.fileexplorer.actions.buildExplorerMenu
import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileexplorer.tree.PathCell
import dev.sluide.fileexplorer.tree.TreeDragAndDrop
import dev.sluide.fileexplorer.tree.startWidth
import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import javafx.application.Platform
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.value.ObservableValue
import javafx.scene.control.Alert
import javafx.scene.control.SelectionMode
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.IdentityHashMap
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
    var onFilesChanged: (Set<Path>) -> Unit = {}

    /**
     * Called after a file or folder was renamed or moved to another folder in the explorer, with its old and new path.
     * A folder's content moved along with it.
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

    /** Actions on a folder, listed in the context menu after New Folder…. Set by the window. */
    var folderActions: List<FolderAction> = emptyList()

    /** Whether Show History is enabled for a file (never for folders); asked when the context menu opens. */
    var canShowHistory: (Path) -> Boolean = { false }

    /** Asks whether to delete an entry; shows a confirmation dialog, tests replace it, since it blocks. */
    internal var confirmDelete: (DeleteQuestion) -> Boolean = { askToDelete(scene?.window, it) }

    /** Asks whether to move entries; shows a confirmation dialog, tests replace it, since it blocks. */
    internal var confirmMove: (MoveQuestion) -> Boolean = { askToMove(scene?.window, it) }

    /** Runs a delete, which may take long (e.g. `node_modules`), away from the JavaFX thread; tests replace it. */
    internal var runInBackground: (Runnable) -> Unit = { NamedThreads.virtual("sluide-delete", it) }

    /** Runs the end of a delete on the JavaFX thread; tests replace it. */
    internal var dispatch: (Runnable) -> Unit = Platform::runLater

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

    /** Shows a note and waits until it is closed; tests replace it, since it blocks. */
    internal var showInfo: (String) -> Unit = { message ->
        Alert(Alert.AlertType.INFORMATION, message)
            .apply {
                scene?.window?.let(::initOwner)
                headerText = null
            }.showAndWait()
    }

    // The hooks and test seams are read when an edit runs, not now, so later changes take effect.
    private val editHost =
        ExplorerEditHost(
            projectItem = { projectItem },
            askName = { title, initial, validate -> askName(title, initial, validate) },
            showError = { showError(it) },
            showInfo = { showInfo(it) },
            confirmDelete = { confirmDelete(it) },
            confirmMove = { confirmMove(it) },
            runInBackground = { runInBackground(it) },
            dispatch = { dispatch(it) },
            onMoved = { from, to -> onMoved(from, to) },
            unsavedUnder = { unsavedUnder(it) },
            onDeleted = { path, unsaved -> onDeleted(path, unsaved) },
            requestOpen = { requestOpen(it) },
            select = { parent, path -> select(parent, path) },
            selectRow = { selectRow(it) },
            selectRows = { selectRows(it) },
            selectedItems = { selectionModel.selectedItems.filterNotNull() },
            rows = { (0 until expandedItemCount).mapNotNull { getTreeItem(it) } },
            isShown = { isShown(it) },
            closeWatchesUnder = { path ->
                watches.keys.filter { it.value.startsWith(path) }.forEach { watches.remove(it)?.close() }
            },
            closeWatchesOfVanishedFolders = { closeWatchesOfVanishedFolders() },
        )
    private val edits = ExplorerEdits(editHost)
    private val moves = ExplorerMoves(editHost, edits::canChange, edits::isBeingDeleted)

    /**
     * The entries the context menu was opened for, in row order; empty for the empty area, which stands for the
     * project folder.
     */
    private var menuTargets: List<FileTreeItem> = emptyList()

    /** Counts calls of [refreshMarkers]; the cells listen to it. */
    private val markerChanges = SimpleIntegerProperty()

    /** The top-level item of the opened (project) folder; `null` without one. */
    internal var projectItem: FileTreeItem? = null
        private set

    // By item, not path: a folder can be shown twice (e.g. an extra root inside the project), each copy watched.
    private val watches = IdentityHashMap<FileTreeItem, AutoCloseable>()

    private val font = fontSettings.font(FontArea.EXPLORER)

    // Material Design icons leave a margin around the glyph, so they are a bit larger than the text.
    private val fileIconSize: ObservableValue<Int> = font.map { (it.size * ICON_SCALE).roundToInt() }

    // Moving entries by drag and drop; the cells mark its drop target.
    private val dragAndDrop =
        TreeDragAndDrop(
            tree = this,
            projectItem = { projectItem },
            canChange = ::canChange,
            canDropInto = { !edits.isBeingDeleted(it.value) },
            selectedEntries = ::selectedEntries,
            reselect = { items -> selectRows(items.filter(::isShown)) },
            move = { items, folder -> move(items, folder) },
        )

    init {
        // The top-level folders are the children of a hidden root, indented like a root shown by itself.
        isShowRoot = false
        setCellFactory {
            PathCell(
                fileIconSize,
                markerChanges,
                { path, directory -> markers(path, directory) },
                { path -> canOpenFile(path) },
                { target -> menuTargets = targetsFor(target) },
                { path -> requestOpen(path) },
                dragAndDrop.dropTarget,
            )
        }
        // The cells inherit the font.
        styleProperty().bind(font.map(FontSpec::toCss))
        stylesheets += checkNotNull(FileExplorerView::class.java.getResource("file-explorer.css")).toExternalForm()
        stylesheets += FILE_ICONS_CSS
        selectionModel.selectionMode = SelectionMode.MULTIPLE
        addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            val selected = selectedEntries()
            val single = selected.singleOrNull()?.takeIf(::canChange)
            when {
                event.code == KeyCode.ENTER && openFiles(selected) > 0 -> {
                    event.consume()
                }

                event.code == KeyCode.F5 -> {
                    refreshAll()
                    event.consume()
                }

                event.code == KeyCode.F2 && single != null -> {
                    rename(single)
                    event.consume()
                }

                // On macOS, Cmd+Backspace, as in the Finder: Mac keyboards often have no Delete key.
                (event.code == KeyCode.DELETE || (event.code == KeyCode.BACK_SPACE && event.isShortcutDown)) &&
                    selected.isNotEmpty() && selected.all(::canChange) -> {
                    delete(selected)
                    event.consume()
                }
            }
        }
        contextMenu =
            buildExplorerMenu(
                ExplorerMenuHost(
                    targets = { menuTargets },
                    projectOpen = { projectItem != null },
                    canChange = ::canChange,
                    folderActions = { folderActions },
                    historyOffered = { showHistory != null },
                    canShowHistory = { canShowHistory(it) },
                    showHistory = { showHistory?.invoke(it) },
                    openFiles = { openFiles(it) },
                    create = { target, folder -> create(target, folder) },
                    runFolderAction = { action, target -> runFolderAction(action, target) },
                    rename = { rename(it) },
                    delete = { delete(it) },
                    refreshAll = { refreshAll() },
                ),
            )
        // Cells for entries set the targets afterwards; a menu opened by keyboard is for the selection.
        addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED) { event ->
            menuTargets = if (event.isKeyboardTrigger) selectedEntries() else emptyList()
        }
    }

    /** The selected entries, in row order. */
    private fun selectedEntries(): List<FileTreeItem> =
        selectionModel.selectedItems.filterIsInstance<FileTreeItem>().sortedBy { getRow(it) }

    /** The targets of a menu opened on the row of [target]: the selection if it holds the row, else the row alone. */
    private fun targetsFor(target: FileTreeItem?): List<FileTreeItem> {
        val selected = selectedEntries()
        return if (target != null && selected.any { it === target }) selected else listOfNotNull(target)
    }

    /** Opens every regular file among [items], in the order given; returns how many it opened. */
    internal fun openFiles(items: List<FileTreeItem>): Int = items.count { requestOpen(it.value) }

    /**
     * Shows [root] (expanded) and after it each of [extraRoots] (collapsed) as top-level nodes; `open(null)` clears
     * the view.
     */
    fun open(
        root: Path?,
        extraRoots: List<ExplorerRoot> = emptyList(),
    ) {
        watches.values.forEach(AutoCloseable::close)
        watches.clear()
        val project = root?.let { FileTreeItem(it.toAbsolutePath().normalize(), onChildrenLoaded = ::watch) }
        project?.isExpanded = true
        projectItem = project
        val extras =
            extraRoots.map {
                FileTreeItem(
                    it.folder.toAbsolutePath().normalize(),
                    it.label,
                    it.folderIcons,
                    ::watch,
                )
            }
        this.root =
            if (project == null && extras.isEmpty()) {
                null
            } else {
                TreeItem<Path>().apply {
                    isExpanded = true
                    children.setAll(listOfNotNull(project) + extras)
                }
            }
    }

    /** The top-level folders, the project first. */
    private fun topLevel(): List<FileTreeItem> = root?.children.orEmpty().filterIsInstance<FileTreeItem>()

    /**
     * Expands the top-level node holding [path] and the folders down to it, loading them, then selects and scrolls to
     * [path]; takes the focus only if [focus]. Returns false, expanding nothing, if no top-level node holds it or it
     * does not exist.
     */
    fun reveal(
        path: Path,
        focus: Boolean = false,
    ): Boolean {
        val target = path.toAbsolutePath().normalize()
        val top = topLevel().filter { target.startsWith(it.value) }.maxByOrNull { it.value.nameCount } ?: return false
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false
        var item: TreeItem<Path> = top
        val folders = mutableListOf<FileTreeItem>()
        for (segment in top.value.relativize(target).filter { it.toString().isNotEmpty() }) {
            val folder = item as? FileTreeItem ?: return false
            folders += folder
            val next = folder.value.resolve(segment)
            // The entry may be new, before the watch reported it.
            var found = folder.children.firstOrNull { it.value == next }
            if (found == null) {
                folder.refresh()
                found = folder.children.firstOrNull { it.value == next }
            }
            item = found ?: return false
        }
        // Only now, so that a path not found leaves the tree as it was.
        folders.forEach { it.isExpanded = true }
        selectRow(item)
        if (focus) requestFocus()
        return true
    }

    /**
     * Reads every loaded folder again, checks again whether its files can be opened and renews its watch, e.g. if a
     * change on disk was missed.
     */
    fun refreshAll() {
        topLevel().flatMap(FileTreeItem::loadedFolders).forEach { folder ->
            folder.refresh()
            folder.forgetReadability(listOf(folder.value))
            watch(folder)
        }
        closeWatchesOfVanishedFolders()
        onFilesChanged(emptySet())
    }

    /**
     * The width the explorer would like to start with: enough for the names of the shown entries with their icons and
     * indentation, in the explorer font, but at least 12 and at most 24 times its size (`tree.startWidth`),
     * so that a few short names still leave room and a long one does not take the window.
     */
    fun startWidth(): Double = startWidth(topLevel(), font.value)

    /** Asks [markers] again for every shown entry. */
    fun refreshMarkers() {
        markerChanges.set(markerChanges.get() + 1)
    }

    private fun watch(folder: FileTreeItem) {
        if (!isShown(folder)) return
        watches.remove(folder)?.close()
        watches[folder] = fileWatcher.watch(folder.value) { changed -> onFolderChanged(folder, changed) }
    }

    private fun onFolderChanged(
        folder: FileTreeItem,
        changed: List<Path>,
    ) {
        // A callback queued before the folder left the tree, e.g. by open.
        if (!isShown(folder)) return
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
        onFilesChanged(changed.toSet())
    }

    /** Folders deleted or no longer in the tree (the parent's refresh removes them); their watches end here. */
    private fun closeWatchesOfVanishedFolders() {
        watches.keys.filter { !Files.isDirectory(it.value) || !isShown(it) }.forEach { watches.remove(it)?.close() }
    }

    /**
     * Asks for a name and creates a file or [folder] of that name in [target], which must be a folder; in the project
     * folder for `null`. The new entry is selected, and a new file opened.
     */
    internal fun create(
        target: FileTreeItem?,
        folder: Boolean,
    ) = edits.create(target, folder)

    /** Runs [action] on the folder for [target] (as for [create]); see [ExplorerEdits.runFolderAction]. */
    internal fun runFolderAction(
        action: FolderAction,
        target: FileTreeItem?,
    ) = edits.runFolderAction(action, target)

    private fun canChange(item: TreeItem<Path>?): Boolean = edits.canChange(item)

    /** Asks for a new name of [item] and renames it in its folder, reporting it to [onMoved]. */
    internal fun rename(item: FileTreeItem) = edits.rename(item)

    /** Asks once whether to delete [items] and deletes them permanently in the background, see [ExplorerEdits.delete]. */
    internal fun delete(items: List<FileTreeItem>) = edits.delete(items)

    /**
     * Asks once whether to move [items] into [folder] and moves them, reporting each to [onMoved]; see
     * [ExplorerMoves.move]. Dropping dragged entries on a folder runs it.
     */
    internal fun move(
        items: List<FileTreeItem>,
        folder: FileTreeItem,
    ) = moves.move(items, folder)

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

    /** Selects [item], scrolling only as far as needed to show it, and focuses its row in the focus model. */
    private fun selectRow(item: TreeItem<Path>) {
        val row = getRow(item)
        selectionModel.clearAndSelect(row)
        // Rows inserted or removed before the focused one move the focus later, away from the selection.
        Platform.runLater { focusModel.focus(getRow(item)) }
        (lookup(".virtual-flow") as? VirtualFlow<*>)?.scrollTo(row) ?: scrollTo(row)
    }

    /** Selects exactly [items], scrolling to the first of them like [selectRow]; nothing for none. */
    private fun selectRows(items: List<TreeItem<Path>>) {
        val rows = items.sortedBy { getRow(it) }
        selectRow(rows.firstOrNull() ?: return)
        rows.drop(1).forEach { selectionModel.select(it) }
    }

    /** Passes [path] to [onOpenFile] if it is a regular file; returns whether it did. */
    internal fun requestOpen(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        onOpenFile(path)
        return true
    }
}

private const val ICON_SCALE = 1.25
