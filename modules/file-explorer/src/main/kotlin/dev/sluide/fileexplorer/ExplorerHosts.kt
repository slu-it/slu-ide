package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.actions.ExplorerEditHost
import dev.sluide.fileexplorer.actions.FileManagerActions
import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileexplorer.tree.TreeSearch
import dev.sluide.fileexplorer.tree.TreeWatches
import dev.sluide.fileexplorer.tree.isShown
import dev.sluide.fileexplorer.tree.selectRow
import dev.sluide.fileexplorer.tree.selectRows
import javafx.scene.control.TreeItem
import java.nio.file.Path

/*
 * How the explorer's action classes reach the view: their hosts, built of the view's hooks and test seams, each read
 * when an action runs, not when the host is built, so hooks set later and seams replaced by tests take effect.
 */

/** The host of the explorer's edits, moves and copies; [select] shows, selects and returns an entry below a folder. */
internal fun FileExplorerView.buildEditHost(
    watches: TreeWatches,
    search: TreeSearch,
    select: (parent: FileTreeItem, path: Path) -> TreeItem<Path>?,
) = ExplorerEditHost(
    projectItem = { projectItem },
    askName = { title, initial, validate -> askName(title, initial, validate) },
    askImageName = { initial, validate, note -> askImageName(initial, validate, note) },
    showError = { showError(it) },
    showInfo = { showInfo(it) },
    confirmDelete = { confirmDelete(it) },
    confirmMove = { confirmMove(it) },
    confirmCopy = { confirmCopy(it) },
    runInBackground = { runInBackground(it) },
    dispatch = { dispatch(it) },
    onMoved = { from, to -> onMoved(from, to) },
    unsavedUnder = { unsavedUnder(it) },
    onDeleted = { path, unsaved -> onDeleted(path, unsaved) },
    requestOpen = { requestOpen(it) },
    select = select,
    selectRow = { selectRow(it) },
    selectRows = { selectRows(it) },
    selectedItems = { selectionModel.selectedItems.filterNotNull() },
    rows = { (0 until expandedItemCount).mapNotNull { getTreeItem(it) } },
    isShown = { isShown(it) },
    closeWatchesUnder = { watches.closeUnder(it) },
    closeWatchesOfVanishedFolders = { watches.closeVanished() },
    keepFound = { search.keep(it) },
)

/** Open in File Manager, Reveal and Open in Terminal for the explorer. */
internal fun FileExplorerView.buildFileManagerActions() =
    FileManagerActions(
        projectFolder = { projectItem?.value },
        showError = { showError(it) },
        runInBackground = { runInBackground(it) },
        dispatch = { dispatch(it) },
        openInTerminal = { openInTerminal },
    )
