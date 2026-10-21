package dev.sluide.fileexplorer

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileexplorer.actions.DeleteQuestion
import dev.sluide.fileexplorer.actions.EntryKind
import dev.sluide.fileexplorer.actions.FileOperationException
import dev.sluide.fileexplorer.actions.FileOperations
import dev.sluide.fileexplorer.actions.askForName
import dev.sluide.fileexplorer.actions.askToDelete
import dev.sluide.fileexplorer.actions.deleteQuestion
import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileexplorer.tree.PathCell
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
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.math.roundToInt

private val log = System.getLogger(FileExplorerView::class.java.name)

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

    /** Actions on a folder, listed in the context menu after New Folder…. Set by the window. */
    var folderActions: List<FolderAction> = emptyList()

    /** Whether Show History is enabled for a file (never for folders); asked when the context menu opens. */
    var canShowHistory: (Path) -> Boolean = { false }

    /** Asks whether to delete an entry; shows a confirmation dialog, tests replace it, since it blocks. */
    internal var confirmDelete: (DeleteQuestion) -> Boolean = { askToDelete(scene?.window, it) }

    /** Runs a delete, which may take long (e.g. `node_modules`), away from the JavaFX thread; tests replace it. */
    internal var runInBackground: (Runnable) -> Unit = { NamedThreads.virtual("sluide-delete", it) }

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

    /** Shows a note and waits until it is closed; tests replace it, since it blocks. */
    internal var showInfo: (String) -> Unit = { message ->
        Alert(Alert.AlertType.INFORMATION, message)
            .apply {
                scene?.window?.let(::initOwner)
                headerText = null
            }.showAndWait()
    }

    /** The entry the context menu was opened for; `null` for the empty area, which stands for the project folder. */
    private var menuTarget: FileTreeItem? = null

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

    init {
        // The top-level folders are the children of a hidden root, indented like a root shown by itself.
        isShowRoot = false
        setCellFactory {
            PathCell(
                fileIconSize,
                markerChanges,
                { path, directory -> markers(path, directory) },
                { path -> canOpenFile(path) },
                { target -> menuTarget = target },
                { path -> requestOpen(path) },
            )
        }
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
        val actionItems = mutableListOf<MenuItem>()
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
                items.removeAll(actionItems)
                actionItems.clear()
                folderActions.mapTo(actionItems) { action ->
                    MenuItem(action.label).apply {
                        isDisable = targetFolder(menuTarget) == null
                        setOnAction { runFolderAction(action, menuTarget) }
                    }
                }
                items.addAll(items.indexOf(newFolder) + 1, actionItems)
                newFile.isDisable = targetFolder(menuTarget) == null
                newFolder.isDisable = targetFolder(menuTarget) == null
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
        val extras = extraRoots.map { FileTreeItem(it.folder.toAbsolutePath().normalize(), it.label, ::watch) }
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
     * Asks for a name and creates a file or [folder] of that name in [target] if it is a folder, else beside it; in the
     * project folder for `null`. The new entry is selected, and a new file opened.
     */
    internal fun create(
        target: FileTreeItem?,
        folder: Boolean,
    ) {
        val parent = targetFolder(target) ?: return
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
     * The folder an action on [target] works in: [target] if a folder, else its folder; the project folder for `null`.
     */
    private fun targetFolder(target: FileTreeItem?): FileTreeItem? {
        val item = target ?: projectItem ?: return null
        return if (item.isDirectory) item else item.parent as? FileTreeItem
    }

    /**
     * Runs [action] on the folder for [target] (as for [create]). A returned file is selected, if it is in that folder,
     * and opened; the folder is expanded and read again so that new entries show at once. A note is shown after it.
     */
    internal fun runFolderAction(
        action: FolderAction,
        target: FileTreeItem?,
    ) {
        val folder = targetFolder(target) ?: return
        val result =
            try {
                action.perform(folder.value)
            } catch (e: IOException) {
                log.log(System.Logger.Level.WARNING, "A folder action failed in ${folder.value}", e)
                showError(e.message.orEmpty())
                return
            } catch (e: UncheckedIOException) {
                log.log(System.Logger.Level.WARNING, "A folder action failed in ${folder.value}", e)
                showError((e.cause?.message ?: e.message).orEmpty())
                return
            }
        val open = result.open
        if (open != null && open.parent == folder.value) {
            select(folder, open)
        } else {
            folder.isExpanded = true
            folder.refresh()
        }
        if (open != null) requestOpen(open)
        result.note?.let(showInfo)
    }

    /**
     * Whether [item] can be renamed or deleted: an entry of a top-level folder, not a top-level folder itself (their
     * parent is the hidden root), and not being deleted.
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
        watches.keys.filter { it.value.startsWith(path) }.forEach { watches.remove(it)?.close() }
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
                dispatch {
                    try {
                        outcome?.let { showError(it.message.orEmpty()) }
                        // Also after a failure, which may have deleted a part.
                        if (isShown(parent)) {
                            parent.refresh()
                            closeWatchesOfVanishedFolders()
                            // Removing an expanded folder above it can lose the selection.
                            val selected = selectionModel.selectedItem
                            if (replacement != null && isShown(replacement) &&
                                (selected == null || !isShown(selected))
                            ) {
                                selectRow(replacement)
                            }
                        }
                        if (succeeded) onDeleted(path, unsaved)
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

    /** Passes [path] to [onOpenFile] if it is a regular file; returns whether it did. */
    internal fun requestOpen(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        onOpenFile(path)
        return true
    }
}

private const val ICON_SCALE = 1.25
