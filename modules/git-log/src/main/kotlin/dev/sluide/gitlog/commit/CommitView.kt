package dev.sluide.gitlog.commit

import dev.sluide.git.GitChangedFile
import dev.sluide.gitlog.data.ChangeFile
import dev.sluide.gitlog.data.changeTree
import dev.sluide.gitlog.operations.CheckedFiles
import dev.sluide.gitlog.operations.CommitChanges
import dev.sluide.gitlog.ui.SHOW_DIFF
import dev.sluide.gitlog.ui.SharedSplit
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TextArea
import javafx.scene.control.Tooltip
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Font

/**
 * The content of the Commit tab of the git log area (ADR 0048), read by [reader]: left a tree of the changes with
 * checkboxes, "Changes" (tracked files against `HEAD`, [changeTree]) and "Unversioned Files" (untracked ones), each with
 * its count; folders and groups check or uncheck all files below them and show some checked as indeterminate. Which
 * files are checked is kept by path across reloads ([CommitChecks]). Space toggles the selected nodes; Show Diff
 * (Enter, double-click, Shortcut+D), Open Local File (F4) and Copy Path act on the selected file through [showDiff]
 * (with `HEAD`'s commit, `null` without one), [openFile] (only if it [exists]) and the clipboard. Right, the message,
 * Amend (ticked with an empty message it fills `HEAD`'s message, unticked it takes it out again if unchanged; a note
 * if `HEAD` is pushed already), what keeps git from committing (a merge in progress, conflicts), Commit (also
 * Shortcut+Enter) and Commit and Push (Shortcut+Alt+K, ADR 0049), which hand a [CommitChanges] to [commit] and are
 * enabled only as [canCommit] says (not while an operation is [running]), and how many files are checked. A failed
 * read keeps the tree and tells [failedWithChanges]; without a tree the placeholder says it. `git-log.css` styles it
 * within `.git-log`.
 */
internal class CommitView(
    val reader: CommitReader,
    private val running: ObservableBooleanValue,
) : BorderPane() {
    /** Commits (or amends) as the tab says; the operation runs elsewhere, [committed] tells its success. */
    var commit: (CommitChanges) -> Unit = {}

    /** Asks how to stash the checked files (ADR 0107). */
    var stash: (CheckedFiles) -> Unit = {}

    /** Shows how a file differs from `HEAD` (the commit's hash, `null` without one). */
    var showDiff: (GitChangedFile, String?) -> Unit = { _, _ -> }

    /** Opens a file of the working tree. */
    var openFile: (GitChangedFile) -> Unit = {}

    /** Whether a file is in the working tree now. */
    var exists: (GitChangedFile) -> Boolean = { false }

    /** Told when a read failed while a tree read before is shown. */
    var failedWithChanges: () -> Unit = {}

    /** Shows a message in the header, e.g. why a diff cannot be shown. */
    var tell: (String) -> Unit = {}

    private val checks = CommitChecks()

    /** The tracked and untracked paths the tree was built with last; `null` before the first build. */
    private var built: Pair<List<GitChangedFile>, List<String>>? = null

    private val checkedCount = SimpleIntegerProperty(0)
    private val blocked = SimpleBooleanProperty(false)
    private val hasHead = SimpleBooleanProperty(false)
    private val headPushed = SimpleBooleanProperty(false)

    /** The message of `HEAD` that ticking Amend filled in; taken out again on unticking if unchanged. */
    private var amendMessage: String? = null

    internal val showDiffItem = MenuItem("Show Diff").apply { setOnAction { showSelectedDiff() } }
    internal val openFileItem = MenuItem("Open Local File").apply { setOnAction { openSelectedFile() } }
    internal val copyPathItem = MenuItem("Copy Path").apply { setOnAction { copySelectedPath() } }

    /** The changes with their checkboxes; several nodes can be selected. */
    internal val tree =
        TreeView<CommitNode>(TreeItem()).apply {
            styleClass += "git-commit-tree"
            isShowRoot = false
            selectionModel.selectionMode = SelectionMode.MULTIPLE
            setCellFactory { CommitCell(::checkState, { node -> toggle(listOf(node)) }, { showSelectedDiff() }) }
            contextMenu =
                ContextMenu(showDiffItem, openFileItem, SeparatorMenuItem(), copyPathItem).apply {
                    setOnShowing { updateActions() }
                }
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                val plain = !event.isShortcutDown && !event.isAltDown && !event.isShiftDown
                val handled =
                    when {
                        event.code == KeyCode.SPACE && plain -> toggleSelected()
                        event.code == KeyCode.ENTER && plain -> enter()
                        SHOW_DIFF.match(event) -> showSelectedDiff()
                        event.code == KeyCode.F4 && plain -> openSelectedFile()
                        else -> false
                    }
                if (handled) event.consume()
            }
        }

    /** Says why there is no tree: still loading, a failed read, or no changes. */
    internal val placeholder = Label().apply { styleClass += "git-commit-placeholder" }

    internal val messageArea =
        TextArea().apply {
            styleClass += "git-commit-message"
            promptText = "Commit message"
            font = Font.font(MONOSPACED, Font.getDefault().size)
            isWrapText = true
            prefRowCount = MESSAGE_ROWS
        }

    internal val amendBox =
        CheckBox("Amend").apply {
            styleClass += "git-commit-amend"
            disableProperty().bind(hasHead.not())
        }

    internal val pushedNote =
        Label("This commit is already pushed: amending it needs a force push.").apply {
            styleClass += "git-commit-note"
            isWrapText = true
            visibleProperty().bind(amendBox.selectedProperty().and(headPushed))
            managedProperty().bind(visibleProperty())
        }

    /** What git is in the middle of, keeping it from committing; hidden while nothing. */
    internal val blockedNote =
        Label().apply {
            styleClass += "git-commit-note"
            isWrapText = true
            visibleProperty().bind(blocked)
            managedProperty().bind(visibleProperty())
        }

    /** Whether Commit (and Commit and Push) cannot commit now. */
    private val commitDisabled =
        Bindings.createBooleanBinding(
            {
                !canCommit(
                    messageArea.text.orEmpty(),
                    checkedCount.get(),
                    amendBox.isSelected,
                    blocked.get(),
                    running.get(),
                )
            },
            messageArea.textProperty(),
            checkedCount,
            amendBox.selectedProperty(),
            blocked,
            running,
        )

    internal val commitButton =
        Button("Commit").apply {
            styleClass += "git-commit-button"
            tooltip = Tooltip("Commit (${COMMIT.displayText})")
            setOnAction { commitNow() }
            disableProperty().bind(commitDisabled)
        }

    /** Commits, then opens the Push dialog once the commit is made (ADR 0049). */
    internal val commitAndPushButton =
        Button("Commit and Push…").apply {
            styleClass += "git-commit-push-button"
            tooltip = Tooltip("Commit and Push… (${COMMIT_AND_PUSH.displayText})")
            setOnAction { commitNow(push = true) }
            disableProperty().bind(commitDisabled)
        }

    /** Stashes the checked files (ADR 0107), once asked with the Stash Changes dialog. */
    internal val stashButton =
        Button("Stash Checked Files…").apply {
            styleClass += "git-commit-stash-button"
            setOnAction { stashChecked() }
            disableProperty().bind(
                Bindings.createBooleanBinding(
                    { !canStashChecked(checkedCount.get(), blocked.get(), running.get()) },
                    checkedCount,
                    blocked,
                    running,
                ),
            )
        }

    /** The buttons below the message. */
    internal val buttons =
        HBox(commitButton, commitAndPushButton, stashButton).apply { styleClass += "git-commit-buttons" }

    /** "2 of 5 files selected", or "Nothing to commit." */
    internal val summary = Label().apply { styleClass += "git-commit-summary" }

    /** The tree left, the message and its controls right, about 60/40 at first. */
    internal val split =
        SharedSplit(
            BorderPane(StackPane(tree, placeholder)).apply { styleClass += "git-commit-changes" },
            VBox(messageArea, amendBox, pushedNote, blockedNote, buttons, summary).apply {
                styleClass += "git-commit-side"
                VBox.setVgrow(messageArea, Priority.ALWAYS)
            },
            CHANGES_SHARE,
        ).apply { styleClass += "git-commit-body" }

    init {
        styleClass += "git-commit"
        center = split
        placeholder.visibleProperty().bind(Bindings.isEmpty(tree.root.children))
        placeholder.textProperty().bind(
            reader.state.map { state ->
                when {
                    state == CommitLoadState.FAILED && reader.snapshot == null -> "The changes could not be read."
                    reader.snapshot == null -> "Loading…"
                    else -> "No changes."
                }
            },
        )
        tree.selectionModel.selectedItemProperty().subscribe { _ -> updateActions() }
        reader.loaded = ::show
        reader.failed = { if (reader.snapshot != null) failedWithChanges() }
        amendBox.selectedProperty().subscribe { _, ticked -> amendChanged(ticked) }
        addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (COMMIT.match(event)) {
                commitNow()
                event.consume()
            } else if (COMMIT_AND_PUSH.match(event)) {
                commitNow(push = true)
                event.consume()
            }
        }
        summary.text = ""
    }

    /** How the checkbox of [node] shows the files below it. */
    internal fun checkState(node: CommitNode) = checks.stateOf(node.paths)

    /** Gives the message the focus. */
    fun focusMessage() {
        messageArea.requestFocus()
    }

    /**
     * The commit [operation] made succeeded: the message is cleared (unless it was changed while the commit ran), Amend
     * unticked and the committed files' checks forgotten, so that they count as new when they change again.
     */
    fun committed(operation: CommitChanges) {
        amendMessage = null
        if (messageArea.text == operation.message) messageArea.clear()
        amendBox.isSelected = false
        checks.forget(operation.paths)
        changed()
    }

    /** Drops the reads still going. */
    fun close() {
        reader.close()
    }

    /** Shows what was read: the tree, rebuilt only if the files changed, and what keeps git from committing. */
    private fun show(snapshot: CommitSnapshot) {
        val changes = snapshot.changes
        checks.update(changes.files.map { it.path }, changes.untracked)
        hasHead.set(snapshot.head != null)
        // No commit to amend anymore (e.g. `git checkout --orphan`): Amend would enable Commit with nothing checked.
        if (snapshot.head == null) amendBox.isSelected = false
        headPushed.set(changes.headPushed)
        val reason = commitBlocked(changes.inProgress)
        blockedNote.text = reason.orEmpty()
        blocked.set(reason != null)
        val files = changes.files to changes.untracked
        if (files != built) {
            built = files
            rebuild(changes.files, changes.untracked)
        }
        changed()
    }

    /** Builds the tree of [files] and [untracked] files again, keeping the selection and collapsed folders by path. */
    private fun rebuild(
        files: List<GitChangedFile>,
        untracked: List<String>,
    ) {
        val selected =
            tree.selectionModel.selectedItems
                .mapNotNull { it?.value?.let(::commitNodeId) }
                .toSet()
        val collapsed = collapsedCommitIds(tree.root)
        tree.selectionModel.clearSelection()
        tree.root.children.setAll(commitTreeItems(files, untracked, collapsed))
        val rows =
            commitRows(tree.root)
                .filter { item ->
                    item.value?.let(::commitNodeId) in selected
                }.map(tree::getRow)
        rows.filter { it >= 0 }.forEach(tree.selectionModel::select)
    }

    /** The checks changed: the checkboxes, the count and the summary follow. */
    private fun changed() {
        tree.refresh()
        val changes = reader.snapshot?.changes
        val paths = changes?.files.orEmpty().map { it.path } + changes?.untracked.orEmpty()
        checkedCount.set(paths.count(checks::isChecked))
        summary.text = if (changes == null) "" else commitSummary(checkedCount.get(), paths.size)
    }

    /** Checks all files below [nodes], or unchecks them if all are checked. */
    private fun toggle(nodes: List<CommitNode>) {
        val paths = nodes.flatMap { it.paths }.distinct()
        if (paths.isEmpty()) return
        checks.set(paths, checks.stateOf(paths) != CheckState.ALL)
        changed()
    }

    private fun toggleSelected(): Boolean {
        val nodes = tree.selectionModel.selectedItems.mapNotNull { it?.value }
        if (nodes.isEmpty()) return false
        toggle(nodes)
        return true
    }

    private fun amendChanged(ticked: Boolean) {
        if (ticked) {
            if (messageArea.text.isNullOrBlank()) {
                reader.headMessage { message ->
                    if (message != null && amendBox.isSelected && messageArea.text.isNullOrBlank()) {
                        messageArea.text = message
                        amendMessage = message
                    }
                }
            }
        } else {
            if (amendMessage != null && messageArea.text == amendMessage) messageArea.clear()
            amendMessage = null
        }
    }

    /** Commits the checked files with the message, if Commit is enabled; then pushes if [push]. */
    private fun commitNow(push: Boolean = false) {
        if (commitButton.isDisabled) return
        val changes = reader.snapshot?.changes ?: return
        val files = changes.files.filter { checks.isChecked(it.path) }
        val untracked = changes.untracked.filter(checks::isChecked)
        commit(CommitChanges(messageArea.text, files, untracked, amendBox.isSelected, push))
    }

    /** Hands the checked files to [stash], a renamed file with both of its paths, as [commitNow] commits them. */
    private fun stashChecked() {
        if (stashButton.isDisabled) return
        val changes = reader.snapshot?.changes ?: return
        val files = changes.files.filter { checks.isChecked(it.path) }
        val untracked = changes.untracked.filter(checks::isChecked)
        stash(CheckedFiles(stashPaths(files, untracked), untracked.isNotEmpty(), files.size + untracked.size))
    }

    /** The selected file, as git names it (an untracked one as added); `null` for none, a folder or a group. */
    internal val selectedFile: GitChangedFile?
        get() {
            val entry = tree.selectionModel.selectedItem?.value as? CommitEntryNode ?: return null
            return (entry.node as? ChangeFile)?.file
        }

    /** Enter: the diff of a file; a folder or group opens or closes. */
    private fun enter(): Boolean {
        val item = tree.selectionModel.selectedItem ?: return false
        if (item.children.isNotEmpty()) {
            item.isExpanded = !item.isExpanded
            return true
        }
        return showSelectedDiff()
    }

    /**
     * Whether the selected file is an untracked nested repository, which git lists as its folder with a trailing `/`:
     * it has no diff and is no file to open.
     */
    private val selectedRepository: Boolean
        get() {
            val entry = tree.selectionModel.selectedItem?.value as? CommitEntryNode ?: return false
            return entry.node is ChangeFile && entry.paths.single().endsWith("/")
        }

    private fun showSelectedDiff(): Boolean {
        val file = selectedFile ?: return false
        if (selectedRepository) {
            tell("${file.path.substringAfterLast('/')} is a repository.")
        } else {
            showDiff(file, reader.snapshot?.head)
        }
        return true
    }

    private fun openSelectedFile(): Boolean {
        val file = selectedFile ?: return false
        if (!selectedRepository && exists(file)) openFile(file)
        return true
    }

    private fun copySelectedPath() {
        val file = selectedFile ?: return
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(file.path) })
    }

    /**
     * None of the actions applies to folders; Open Local File only to files in the working tree, not to a nested
     * repository.
     */
    private fun updateActions() {
        val file = selectedFile
        showDiffItem.isDisable = file == null
        copyPathItem.isDisable = file == null
        openFileItem.isDisable = file == null || selectedRepository || !exists(file)
    }

    private companion object {
        const val MONOSPACED = "Monospaced"
        const val MESSAGE_ROWS = 6
        const val CHANGES_SHARE = 0.6

        /** Commits from anywhere in the tab, as IntelliJ does; Enter alone is a new line in the message. */
        val COMMIT = KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN)

        /** Commit and Push from anywhere in the tab: IntelliJ's shortcut (ADR 0049). */
        val COMMIT_AND_PUSH = KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN)
    }
}
