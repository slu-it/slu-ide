package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.COPY_KEYS
import dev.sluide.fileexplorer.CUT_KEYS
import dev.sluide.fileexplorer.FileAction
import dev.sluide.fileexplorer.FolderAction
import dev.sluide.fileexplorer.PASTE_KEYS
import dev.sluide.fileexplorer.tree.FileTreeItem
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
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
    val fileManager: Boolean,
    val cut: Boolean,
    val copy: Boolean,
    val paste: Boolean,
)

/**
 * Enablement of the context menu for [targets] (none: the empty area, standing for the project folder if
 * [projectOpen]). [canShowHistory] is only asked for a single file. Paste is enabled if it would paste something
 * ([canPaste], decided as the paste itself decides: `ExplorerCopies.canPaste`).
 */
internal fun menuState(
    targets: List<MenuTarget>,
    projectOpen: Boolean,
    canPaste: Boolean = false,
    canShowHistory: () -> Boolean,
): MenuState {
    val single = targets.singleOrNull()
    val create = if (targets.isEmpty()) projectOpen else single?.isDirectory == true
    return MenuState(
        open = targets.any { it.isRegularFile },
        create = create,
        rename = single?.canChange == true,
        delete = targets.isNotEmpty() && targets.all { it.canChange },
        history = single != null && !single.isDirectory && canShowHistory(),
        fileManager = create,
        cut = targets.isNotEmpty() && targets.all { it.canChange },
        copy = targets.isNotEmpty(),
        paste = canPaste,
    )
}

/**
 * Shows [keys] in a menu item without being an accelerator: it matches no key. A control's context menu puts the
 * accelerators of its items into the whole scene (JavaFX's `ControlAcceleratorSupport`), where Shortcut+C would copy
 * explorer entries while the editor or terminal has the focus. The explorer's own key handler takes the keys instead.
 */
internal class ShownKeys(
    val keys: KeyCombination,
) : KeyCombination() {
    override fun match(event: KeyEvent): Boolean = false

    override fun getDisplayText(): String = keys.displayText

    override fun getName(): String = keys.name

    // By identity: KeyCombination compares only the modifiers, which would make it equal to unrelated combinations.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * The [actions] the menu lists for [targets]: those that apply to the target if it is exactly one regular file
 * ([isRegularFile]), else none.
 */
internal fun fileActionsFor(
    targets: List<Path>,
    isRegularFile: (Path) -> Boolean,
    actions: List<FileAction>,
): List<FileAction> {
    val file = targets.singleOrNull()?.takeIf(isRegularFile) ?: return emptyList()
    return actions.filter { it.appliesTo(file) }
}

/** What [buildExplorerMenu] needs from the explorer; read when the menu opens or an item runs. */
internal class ExplorerMenuHost(
    /** The entries the menu was opened for, in row order; empty for the empty area. */
    val targets: () -> List<FileTreeItem>,
    val projectOpen: () -> Boolean,
    val canChange: (FileTreeItem) -> Boolean,
    val folderActions: () -> List<FolderAction>,
    val fileActions: () -> List<FileAction>,
    /** Whether Show History is offered at all. */
    val historyOffered: () -> Boolean,
    val canShowHistory: (Path) -> Boolean,
    val showHistory: (Path) -> Unit,
    val openFiles: (List<FileTreeItem>) -> Unit,
    val create: (target: FileTreeItem?, folder: Boolean) -> Unit,
    val runFolderAction: (FolderAction, FileTreeItem?) -> Unit,
    val rename: (FileTreeItem) -> Unit,
    val delete: (List<FileTreeItem>) -> Unit,
    /** Whether a paste for the targets would paste something; asked when the menu opens. */
    val canPaste: (List<FileTreeItem>) -> Boolean,
    val cut: (List<FileTreeItem>) -> Unit,
    val copy: (List<FileTreeItem>) -> Unit,
    /** Pastes into the folder for the targets. */
    val paste: (List<FileTreeItem>) -> Unit,
    /** The label of the item opening a folder in the file manager of the system. */
    val fileManagerLabel: () -> String,
    val openInFileManager: (FileTreeItem?) -> Unit,
    val refreshAll: () -> Unit,
)

/**
 * The context menu of the explorer; its items are enabled when it opens, as [menuState] says, and the file actions
 * that apply ([fileActionsFor]) are listed after Open.
 */
internal fun buildExplorerMenu(host: ExplorerMenuHost): ContextMenu {
    val open = MenuItem("Open").apply { setOnAction { host.openFiles(host.targets()) } }
    val newFile = MenuItem("New File…").apply { setOnAction { host.create(host.targets().singleOrNull(), false) } }
    val newFolder = MenuItem("New Folder…").apply { setOnAction { host.create(host.targets().singleOrNull(), true) } }
    val rename = MenuItem("Rename…").apply { setOnAction { host.targets().singleOrNull()?.let(host.rename) } }
    val delete = MenuItem("Delete…").apply { setOnAction { host.delete(host.targets()) } }
    val cut = shortcutItem("Cut", CUT_KEYS) { host.cut(host.targets()) }
    val copy = shortcutItem("Copy", COPY_KEYS) { host.copy(host.targets()) }
    val paste = shortcutItem("Paste", PASTE_KEYS) { host.paste(host.targets()) }
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
    val fileManager =
        MenuItem().apply { setOnAction { host.openInFileManager(host.targets().singleOrNull()) } }
    val historySeparator = SeparatorMenuItem()
    val actionItems = mutableListOf<MenuItem>()
    val fileActionItems = mutableListOf<MenuItem>()
    return ContextMenu(
        open,
        SeparatorMenuItem(),
        cut,
        copy,
        paste,
        SeparatorMenuItem(),
        newFile,
        newFolder,
        SeparatorMenuItem(),
        rename,
        delete,
        historySeparator,
        history,
        SeparatorMenuItem(),
        fileManager,
        SeparatorMenuItem(),
        MenuItem("Refresh").apply { setOnAction { host.refreshAll() } },
    ).apply {
        setOnShowing {
            val targets = host.targets()
            val state =
                menuState(
                    targets.map { MenuTarget(it.isDirectory, Files.isRegularFile(it.value), host.canChange(it)) },
                    host.projectOpen(),
                    host.canPaste(targets),
                ) { host.canShowHistory(targets.single().value) }
            items.removeAll(fileActionItems)
            fileActionItems.clear()
            val paths = targets.map { it.value }
            fileActionsFor(paths, Files::isRegularFile, host.fileActions()).mapTo(fileActionItems) { action ->
                // The icon takes the colour of the item's text (file-explorer.css), in both themes.
                val icon = action.icon?.invoke()?.apply { styleClass += "file-action-icon" }
                MenuItem(action.label, icon).apply { setOnAction { action.perform(paths.single()) } }
            }
            items.addAll(items.indexOf(open) + 1, fileActionItems)
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
            cut.isDisable = !state.cut
            copy.isDisable = !state.copy
            paste.isDisable = !state.paste
            history.isVisible = host.historyOffered()
            historySeparator.isVisible = host.historyOffered()
            history.isDisable = !state.history
            fileManager.text = host.fileManagerLabel()
            fileManager.isDisable = !state.fileManager
        }
    }
}

/** A menu item showing [keys] (the explorer's key handler takes them, see [ShownKeys]) and running [action]. */
private fun shortcutItem(
    text: String,
    keys: KeyCombination,
    action: () -> Unit,
): MenuItem =
    MenuItem(text).apply {
        accelerator = ShownKeys(keys)
        setOnAction { action() }
    }
