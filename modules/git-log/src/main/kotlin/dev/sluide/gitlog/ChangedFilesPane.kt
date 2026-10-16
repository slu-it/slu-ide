package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.value.ObservableValue
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
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.util.Duration
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignF

/** How long the files of another commit stay while the next ones load, before "Loading…" replaces them. */
private val LOADING_DELAY = Duration.millis(300.0)

/**
 * The files the selected commit changed, above its details (ADR 0038): a line with their number, then a tree by their
 * paths ([changeTree]), everything expanded, the names coloured by their change. Show Diff (double-click, Enter,
 * Shortcut+D), Open Local File (F4) and Copy Path act on the selected file through [showDiff], [openFile] and the
 * clipboard; Open Local File only if the file [exists] in the working tree. Without exactly one selected commit, or
 * while one loads for the first time, a placeholder says so.
 */
internal class ChangedFilesPane(
    state: ObservableValue<ChangedFilesState>,
) : BorderPane() {
    /** Shows the diff of a file of the commit of a row. */
    var showDiff: (GitLogRow, GitChangedFile) -> Unit = { _, _ -> }

    /** Opens the file in the working tree. */
    var openFile: (GitChangedFile) -> Unit = {}

    /** Whether the file is in the working tree now. */
    var exists: (GitChangedFile) -> Boolean = { false }

    internal val header = Label().apply { styleClass += "git-changes-header" }
    internal val placeholder = Label().apply { styleClass += "git-details-placeholder" }

    internal val showDiffItem = MenuItem("Show Diff").apply { setOnAction { showSelectedDiff() } }
    internal val openFileItem = MenuItem("Open Local File").apply { setOnAction { openSelectedFile() } }
    internal val copyPathItem = MenuItem("Copy Path").apply { setOnAction { copySelectedPath() } }

    internal val tree: TreeView<ChangeNode> =
        TreeView<ChangeNode>(TreeItem()).apply {
            styleClass += "git-changes-tree"
            isShowRoot = false
            setCellFactory { ChangeCell() }
            contextMenu =
                ContextMenu(showDiffItem, openFileItem, SeparatorMenuItem(), copyPathItem).apply {
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
        }

    /** The row whose files are shown (also when there are none), `null` while a placeholder is. */
    private var shown: GitLogRow? = null

    /** Shows "Loading…" if the files of another commit take a while. */
    internal val loading = PauseTransition(LOADING_DELAY).apply { setOnFinished { showPlaceholder("Loading…") } }

    init {
        styleClass += "git-changes"
        minWidth = 0.0
        minHeight = 0.0
        top = header
        center = StackPane(tree, placeholder)
        tree.selectionModel.selectedItemProperty().subscribe { _ -> updateActions() }
        state.subscribe(::show)
    }

    /** The selected file, `null` for none or a folder. */
    internal val selectedFile: GitChangedFile?
        get() = (tree.selectionModel.selectedItem?.value as? ChangeFile)?.file

    private fun show(state: ChangedFilesState) {
        loading.stop()
        when (state) {
            ChangedFilesState.NoneSelected -> {
                showPlaceholder("No commit selected")
            }

            is ChangedFilesState.SeveralSelected -> {
                showPlaceholder("${state.count} commits selected")
            }

            is ChangedFilesState.Loading -> {
                val shown = shown
                when {
                    shown == null -> {
                        showPlaceholder("Loading…")
                    }

                    // The same commit again (after a refresh): its files stay until they are read again.
                    shown.commit.hash == state.row.commit.hash -> {}

                    // Another commit: no flicker on fast moves through the rows, unless it takes a while.
                    else -> {
                        loading.playFromStart()
                    }
                }
            }

            is ChangedFilesState.Loaded -> {
                showFiles(state.row, state.files)
            }

            is ChangedFilesState.Failed -> {
                showPlaceholder("The changed files could not be read.")
            }
        }
    }

    private fun showPlaceholder(text: String) {
        shown = null
        tree.root.children.clear()
        placeholder.text = text
        placeholder.isVisible = true
        tree.isVisible = false
        header.isVisible = false
        header.isManaged = false
    }

    private fun showFiles(
        row: GitLogRow,
        files: List<GitChangedFile>,
    ) {
        // A refresh brings the same commit again: the selected file stays selected, collapsed folders collapsed.
        val sameCommit = shown?.commit?.hash == row.commit.hash
        val keep = selectedFile?.takeIf { sameCommit }?.path
        val collapsed = if (sameCommit) collapsedFolders(tree.root) else emptySet()
        if (files.isEmpty()) showPlaceholder("No changed files")
        shown = row
        if (files.isEmpty()) return
        val count = if (files.size == 1) "1 file" else "${files.size} files"
        val parents = row.commit.parents
        val firstParent = parents.firstOrNull()?.take(SHORT_HASH)
        header.text = if (parents.size > 1) "$count (compared with the first parent $firstParent)" else count
        header.isVisible = true
        header.isManaged = true
        val fileItems = HashMap<String, TreeItem<ChangeNode>>()
        tree.root.children.setAll(changeTree(files).map { item(it, collapsed, fileItems) })
        tree.selectionModel.clearSelection()
        // Not selected if it is in a collapsed folder.
        keep
            ?.let(fileItems::get)
            ?.let(tree::getRow)
            ?.takeIf { it >= 0 }
            ?.let(tree.selectionModel::select)
        tree.scrollTo(tree.selectionModel.selectedIndex.coerceAtLeast(0))
        placeholder.isVisible = false
        tree.isVisible = true
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
        val item = tree.selectionModel.selectedItem ?: return false
        if (item.value is ChangeFolder) {
            item.isExpanded = !item.isExpanded
            return true
        }
        return showSelectedDiff()
    }

    private fun showSelectedDiff(): Boolean {
        val row = shown ?: return false
        val file = selectedFile ?: return false
        showDiff(row, file)
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
    }

    /** Stops showing "Loading…" later; the window closes. */
    fun close() {
        if (Platform.isFxApplicationThread()) loading.stop() else Platform.runLater(loading::stop)
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
        /** As many characters of the hash as the details show. */
        const val SHORT_HASH = 8

        /** The style class colouring the name of a file with [change]. */
        fun styleClassOf(change: GitChange) =
            when (change) {
                GitChange.ADDED -> "git-change-added"
                GitChange.DELETED -> "git-change-deleted"
                else -> "git-change-modified"
            }
    }
}

private val SHOW_DIFF = KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN)
