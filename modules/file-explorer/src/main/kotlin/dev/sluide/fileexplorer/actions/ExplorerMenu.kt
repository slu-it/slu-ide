package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.FolderAction
import dev.sluide.fileexplorer.tree.FileTreeItem
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import java.nio.file.Files
import java.nio.file.Path

/** What the menu needs to know about one target: a folder, a regular file, and whether it can be renamed or deleted. */
internal data class MenuTarget(
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val canChange: Boolean,
)

/** Which items of the context menu are enabled; see [menuState]. */
internal data class MenuState(
    val open: Boolean,
    val create: Boolean,
    val rename: Boolean,
    val delete: Boolean,
    val history: Boolean,
)

/**
 * Enablement of the context menu for [targets] (none: the empty area, standing for the project folder if
 * [projectOpen]). [canShowHistory] is only asked for a single file.
 */
internal fun menuState(
    targets: List<MenuTarget>,
    projectOpen: Boolean,
    canShowHistory: () -> Boolean,
): MenuState {
    val single = targets.singleOrNull()
    return MenuState(
        open = targets.any { it.isRegularFile },
        create = if (targets.isEmpty()) projectOpen else single?.isDirectory == true,
        rename = single?.canChange == true,
        delete = targets.isNotEmpty() && targets.all { it.canChange },
        history = single != null && !single.isDirectory && canShowHistory(),
    )
}

/** What [buildExplorerMenu] needs from the explorer; read when the menu opens or an item runs. */
internal class ExplorerMenuHost(
    /** The entries the menu was opened for, in row order; empty for the empty area. */
    val targets: () -> List<FileTreeItem>,
    val projectOpen: () -> Boolean,
    val canChange: (FileTreeItem) -> Boolean,
    val folderActions: () -> List<FolderAction>,
    /** Whether Show History is offered at all. */
    val historyOffered: () -> Boolean,
    val canShowHistory: (Path) -> Boolean,
    val showHistory: (Path) -> Unit,
    val openFiles: (List<FileTreeItem>) -> Unit,
    val create: (target: FileTreeItem?, folder: Boolean) -> Unit,
    val runFolderAction: (FolderAction, FileTreeItem?) -> Unit,
    val rename: (FileTreeItem) -> Unit,
    val delete: (List<FileTreeItem>) -> Unit,
    val refreshAll: () -> Unit,
)

/** The context menu of the explorer; its items are enabled when it opens, as [menuState] says. */
internal fun buildExplorerMenu(host: ExplorerMenuHost): ContextMenu {
    val open = MenuItem("Open").apply { setOnAction { host.openFiles(host.targets()) } }
    val newFile = MenuItem("New File…").apply { setOnAction { host.create(host.targets().singleOrNull(), false) } }
    val newFolder = MenuItem("New Folder…").apply { setOnAction { host.create(host.targets().singleOrNull(), true) } }
    val rename = MenuItem("Rename…").apply { setOnAction { host.targets().singleOrNull()?.let(host.rename) } }
    val delete = MenuItem("Delete…").apply { setOnAction { host.delete(host.targets()) } }
    val history =
        MenuItem("Show History").apply {
            setOnAction {
                host
                    .targets()
                    .singleOrNull()
                    ?.value
                    ?.let(host.showHistory)
            }
        }
    val historySeparator = SeparatorMenuItem()
    val actionItems = mutableListOf<MenuItem>()
    return ContextMenu(
        open,
        SeparatorMenuItem(),
        newFile,
        newFolder,
        SeparatorMenuItem(),
        rename,
        delete,
        historySeparator,
        history,
        SeparatorMenuItem(),
        MenuItem("Refresh").apply { setOnAction { host.refreshAll() } },
    ).apply {
        setOnShowing {
            val targets = host.targets()
            val state =
                menuState(
                    targets.map { MenuTarget(it.isDirectory, Files.isRegularFile(it.value), host.canChange(it)) },
                    host.projectOpen(),
                ) { host.canShowHistory(targets.single().value) }
            items.removeAll(actionItems)
            actionItems.clear()
            host.folderActions().mapTo(actionItems) { action ->
                MenuItem(action.label).apply {
                    isDisable = !state.create
                    setOnAction { host.runFolderAction(action, host.targets().singleOrNull()) }
                }
            }
            items.addAll(items.indexOf(newFolder) + 1, actionItems)
            open.isDisable = !state.open
            newFile.isDisable = !state.create
            newFolder.isDisable = !state.create
            rename.isDisable = !state.rename
            delete.isDisable = !state.delete
            history.isVisible = host.historyOffered()
            historySeparator.isVisible = host.historyOffered()
            history.isDisable = !state.history
        }
    }
}
