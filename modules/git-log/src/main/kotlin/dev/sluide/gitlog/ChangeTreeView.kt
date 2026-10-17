package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import javafx.geometry.Pos
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.Tooltip
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignF

/**
 * Changed files as a tree by their paths ([changeTree], ADR 0038), everything expanded, the names coloured by their
 * change, a renamed one with its old path; for the commit's changed files and for the files that differ from the working
 * tree (ADR 0044). Show Diff (double-click, Enter, Shortcut+D), Open Local File (F4) and Copy Path act on the selected
 * file through [showDiff], [openFile] and the clipboard; Open Local File only if the file [exists] in the working tree.
 * Enter on a folder opens or closes it. Show History (only while [showHistory] is set) shows the file's history up to
 * the commit (ADR 0046). `git-log.css` styles it within `.git-log`.
 */
internal class ChangeTreeView : TreeView<ChangeNode>(TreeItem()) {
    /** Shows the diff of a file. */
    var showDiff: (GitChangedFile) -> Unit = {}

    /** Opens the file in the working tree. */
    var openFile: (GitChangedFile) -> Unit = {}

    /** Whether the file is in the working tree now. */
    var exists: (GitChangedFile) -> Boolean = { false }

    /** Shows the history of a file (ADR 0046); `null` (the working-tree window) leaves Show History out of the menu. */
    var showHistory: ((GitChangedFile) -> Unit)? = null

    internal val showDiffItem = MenuItem("Show Diff").apply { setOnAction { showSelectedDiff() } }
    internal val openFileItem = MenuItem("Open Local File").apply { setOnAction { openSelectedFile() } }
    internal val showHistoryItem =
        MenuItem("Show History").apply { setOnAction { selectedFile?.let { showHistory?.invoke(it) } } }
    internal val copyPathItem = MenuItem("Copy Path").apply { setOnAction { copySelectedPath() } }

    init {
        styleClass += "git-changes-tree"
        isShowRoot = false
        setCellFactory { ChangeCell() }
        contextMenu =
            ContextMenu(showDiffItem, openFileItem, showHistoryItem, SeparatorMenuItem(), copyPathItem).apply {
                // The file may have appeared or gone in the working tree since it was selected.
                setOnShowing { updateActions() }
            }
        addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            val handled =
                when {
                    event.code == KeyCode.ENTER && !event.isShortcutDown -> enter()
                    SHOW_DIFF.match(event) -> showSelectedDiff()
                    event.code == KeyCode.F4 && !event.isShortcutDown -> openSelectedFile()
                    else -> false
                }
            if (handled) event.consume()
        }
        selectionModel.selectedItemProperty().subscribe { _ -> updateActions() }
    }

    /** Whether [show] or [clear] is changing the items, whose selection changes then are not the user's. */
    var rebuilding = false
        private set

    /** The selected file, `null` for none or a folder. */
    val selectedFile: GitChangedFile?
        get() = (selectionModel.selectedItem?.value as? ChangeFile)?.file

    /**
     * Shows [files]; if [keep], the selected file stays selected and collapsed folders collapsed (by path), e.g. for the
     * same commit after a refresh. Scrolls to the selected file, or to the top.
     */
    fun show(
        files: List<GitChangedFile>,
        keep: Boolean,
    ) {
        val selected = selectedFile?.takeIf { keep }?.path
        val collapsed = if (keep) collapsedFolders(root) else emptySet()
        val fileItems = HashMap<String, TreeItem<ChangeNode>>()
        rebuilding = true
        try {
            root.children.setAll(changeTree(files).map { item(it, collapsed, fileItems) })
            selectionModel.clearSelection()
            // Not selected if it is in a collapsed folder.
            selected
                ?.let(fileItems::get)
                ?.let(::getRow)
                ?.takeIf { it >= 0 }
                ?.let(selectionModel::select)
        } finally {
            rebuilding = false
        }
        scrollTo(selectionModel.selectedIndex.coerceAtLeast(0))
    }

    /** Shows no files. */
    fun clear() {
        rebuilding = true
        try {
            root.children.clear()
        } finally {
            rebuilding = false
        }
    }

    /** The paths of the collapsed folders below [item]. */
    private fun collapsedFolders(item: TreeItem<ChangeNode>): Set<String> =
        item.children
            .flatMap { child ->
                val folder = child.value as? ChangeFolder ?: return@flatMap emptyList()
                listOfNotNull(folder.path.takeIf { !child.isExpanded }) + collapsedFolders(child)
            }.toSet()

    /**
     * The tree item of [node] with everything below it, expanded unless its path is one of the [collapsed] folders;
     * the items of files go into [fileItems] by path.
     */
    private fun item(
        node: ChangeNode,
        collapsed: Set<String>,
        fileItems: MutableMap<String, TreeItem<ChangeNode>>,
    ): TreeItem<ChangeNode> =
        TreeItem(node).apply {
            when (node) {
                is ChangeFolder -> {
                    children.setAll(node.children.map { item(it, collapsed, fileItems) })
                    isExpanded = node.path !in collapsed
                }

                is ChangeFile -> {
                    fileItems[node.file.path] = this
                }
            }
        }

    /** Enter: the diff of a file; a folder opens or closes. */
    private fun enter(): Boolean {
        val item = selectionModel.selectedItem ?: return false
        if (item.value is ChangeFolder) {
            item.isExpanded = !item.isExpanded
            return true
        }
        return showSelectedDiff()
    }

    private fun showSelectedDiff(): Boolean {
        val file = selectedFile ?: return false
        showDiff(file)
        return true
    }

    private fun openSelectedFile(): Boolean {
        val file = selectedFile ?: return false
        if (exists(file)) openFile(file)
        return true
    }

    private fun copySelectedPath() {
        val file = selectedFile ?: return
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(file.path) })
    }

    /** None of the actions applies to folders; Open Local File only to files in the working tree. */
    private fun updateActions() {
        val file = selectedFile
        showDiffItem.isDisable = file == null
        copyPathItem.isDisable = file == null
        openFileItem.isDisable = file == null || !exists(file)
        showHistoryItem.isVisible = showHistory != null
        showHistoryItem.isDisable = file == null
    }

    /** A folder or a file: an icon and the name, a file's coloured by its change, a renamed one with its old path. */
    private inner class ChangeCell : TreeCell<ChangeNode>() {
        private val icon = FontIcon().apply { styleClass += "git-change-icon" }
        private val name = Label()
        private val oldPath = Label().apply { styleClass += "git-change-old" }
        private val box = HBox(icon, name, oldPath).apply { alignment = Pos.CENTER_LEFT }

        init {
            setOnMouseClicked { event ->
                if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && item is ChangeFile) {
                    showSelectedDiff()
                    event.consume()
                }
            }
        }

        override fun updateItem(
            item: ChangeNode?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = null
            if (empty || item == null) {
                graphic = null
                tooltip = null
                return
            }
            name.text = item.name
            when (item) {
                is ChangeFolder -> {
                    icon.iconCode = MaterialDesignF.FOLDER_OUTLINE
                    name.styleClass.setAll("label", "git-change-folder")
                    showOldPath(null)
                }

                is ChangeFile -> {
                    icon.iconCode = MaterialDesignF.FILE_OUTLINE
                    name.styleClass.setAll("label", "git-change-name", styleClassOf(item.file.change))
                    showOldPath(item.file)
                }
            }
            graphic = box
        }

        private fun showOldPath(file: GitChangedFile?) {
            val old = file?.oldPath
            oldPath.text = old?.let { " ← $it" }.orEmpty()
            oldPath.isVisible = old != null
            oldPath.isManaged = old != null
            tooltip =
                old?.let {
                    Tooltip(if (file.change == GitChange.COPIED) "Copied from $it" else "Renamed from $it")
                }
        }
    }

    internal companion object {
        /** The style class colouring the name of a file with [change]. */
        fun styleClassOf(change: GitChange) =
            when (change) {
                GitChange.ADDED -> "git-change-added"
                GitChange.DELETED -> "git-change-deleted"
                else -> "git-change-modified"
            }
    }
}

internal val SHOW_DIFF = KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN)
