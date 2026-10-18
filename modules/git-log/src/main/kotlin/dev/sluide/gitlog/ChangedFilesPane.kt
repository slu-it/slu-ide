package dev.sluide.gitlog

import dev.sluide.git.GitChangedFile
import dev.sluide.gitlog.data.ChangedFilesState
import dev.sluide.gitlog.data.GitLogRow
import dev.sluide.gitlog.ui.ChangeTreeView
import dev.sluide.gitlog.ui.SHORT_HASH
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.value.ObservableValue
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.StackPane
import javafx.util.Duration

/** How long the files of another commit stay while the next ones load, before "Loading…" replaces them. */
private val LOADING_DELAY = Duration.millis(300.0)

/**
 * The files the selected commit changed, above its details (ADR 0038): a line with their number, then a tree by their
 * paths ([ChangeTreeView]), everything expanded, the names coloured by their change. Show Diff (double-click, Enter,
 * Shortcut+D), Open Local File (F4) and Copy Path act on the selected file through [showDiff], [openFile] and the
 * clipboard; Open Local File only if the file [exists] in the working tree. Show History goes to [showHistory]. Without exactly one selected commit, or
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

    /** Shows the history of a file up to the commit of a row (ADR 0046). */
    var showHistory: (GitLogRow, GitChangedFile) -> Unit = { _, _ -> }

    internal val header = Label().apply { styleClass += "git-changes-header" }
    internal val placeholder = Label().apply { styleClass += "git-details-placeholder" }

    internal val tree =
        ChangeTreeView().apply {
            showDiff = { file -> shown?.let { row -> this@ChangedFilesPane.showDiff(row, file) } }
            openFile = { file -> this@ChangedFilesPane.openFile(file) }
            exists = { file -> this@ChangedFilesPane.exists(file) }
            showHistory = { file -> shown?.let { row -> this@ChangedFilesPane.showHistory(row, file) } }
        }

    internal val showDiffItem get() = tree.showDiffItem
    internal val openFileItem get() = tree.openFileItem
    internal val showHistoryItem get() = tree.showHistoryItem
    internal val copyPathItem get() = tree.copyPathItem

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
        state.subscribe(::show)
    }

    /** The selected file, `null` for none or a folder. */
    internal val selectedFile: GitChangedFile? get() = tree.selectedFile

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
        tree.clear()
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
        if (files.isEmpty()) showPlaceholder("No changed files")
        shown = row
        if (files.isEmpty()) return
        val count = if (files.size == 1) "1 file" else "${files.size} files"
        val parents = row.commit.parents
        val firstParent = parents.firstOrNull()?.take(SHORT_HASH)
        header.text = if (parents.size > 1) "$count (compared with the first parent $firstParent)" else count
        header.isVisible = true
        header.isManaged = true
        tree.show(files, keep = sameCommit)
        placeholder.isVisible = false
        tree.isVisible = true
    }

    /** Stops showing "Loading…" later; the window closes. */
    fun close() {
        if (Platform.isFxApplicationThread()) loading.stop() else Platform.runLater(loading::stop)
    }
}
