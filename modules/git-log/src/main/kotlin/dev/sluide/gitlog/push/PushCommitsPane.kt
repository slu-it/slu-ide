package dev.sluide.gitlog.push

import dev.sluide.git.GitChangedFile
import dev.sluide.gitlog.operations.commitCount
import dev.sluide.gitlog.ui.ChangeTreeView
import dev.sluide.gitlog.ui.SharedSplit
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The middle of the Push dialog (ADR 0049), as [reader] reads it: left the outgoing commits, "All" first; right the
 * files of the selected row ([ChangeTreeView]), whose diffs go to [showDiff] with the commit and the parent and where
 * to tell what keeps one from being shown ([statusLabel], placed by the dialog), Open Local File (F4) to [openFile] if
 * the file [exists].
 */
internal class PushCommitsPane(
    private val reader: PushReader,
    note: (styleClass: String) -> Label,
) {
    /** Shows the diff of a file of a commit against a parent; what keeps it from being shown goes to the last one. */
    var showDiff: (commit: String, parent: String?, file: GitChangedFile, tell: (String) -> Unit) -> Unit =
        { _, _, _, _ -> }

    /** Opens a file of the working tree. */
    var openFile: (GitChangedFile) -> Unit = {}

    /** Whether a file is in the working tree now. */
    var exists: (GitChangedFile) -> Boolean = { false }

    /** While the rows are set from what was read, so that selecting one does not read its files. */
    private var updating = false

    internal val commitList =
        ListView<PushRow>().apply {
            styleClass += "git-push-commits"
            setCellFactory { PushCell() }
        }

    internal val commitPlaceholder =
        Label().apply {
            styleClass += "git-push-placeholder"
            isWrapText = true
        }

    internal val tree =
        ChangeTreeView().apply {
            showDiff = ::showFileDiff
            openFile = { file -> this@PushCommitsPane.openFile(file) }
            exists = { file -> this@PushCommitsPane.exists(file) }
        }

    internal val filesPlaceholder = Label().apply { styleClass += "git-push-placeholder" }

    /** Why a diff cannot be shown, until the next selection or read. */
    internal val statusLabel = note("git-push-status")

    /** The commits left, the files of the selected row right. */
    val node =
        SharedSplit(
            StackPane(commitList),
            StackPane(tree, filesPlaceholder).apply { styleClass += "git-push-files" },
            COMMITS_SHARE,
        ).apply { styleClass += "git-push-body" }

    init {
        commitList.placeholder = commitPlaceholder
        reader.files.subscribe { files -> showFiles(files) }
        commitList.selectionModel.selectedItemProperty().subscribe { _ -> if (!updating) requestFiles() }
        tree.selectionModel.selectedItemProperty().subscribe { _ -> statusLabel.text = "" }
        commitPlaceholder.text = pushPlaceholder(null)
    }

    /** Lists the commits of [state], keeping the selected commit if it is still listed, else selecting All. */
    fun showRows(state: PushState) {
        updating = true
        try {
            val selected = commitList.selectionModel.selectedItem
            val rows = pushRows(state.outgoing)
            commitPlaceholder.text = pushPlaceholder(state)
            if (commitList.items != rows) commitList.items.setAll(rows)
            val index = rows.indexOf(selected).takeIf { selected is PushRow.Commit && it >= 0 } ?: 0
            if (rows.isEmpty()) {
                commitList.selectionModel.clearSelection()
            } else if (commitList.selectionModel.selectedIndex != index) {
                commitList.selectionModel.clearAndSelect(index)
            }
        } finally {
            updating = false
        }
    }

    /** Reads the files of the selected row, unless they are shown or read already. */
    fun requestFiles() {
        val state = reader.state.get()
        val key = pushFilesOf(commitList.selectionModel.selectedItem, state?.outgoing)
        if (key == null) {
            reader.noFiles()
            return
        }
        val files = reader.files.get()
        val current =
            when (files) {
                is PushFiles.Loaded -> files.commit to files.parent
                is PushFiles.Loading -> files.commit to files.parent
                else -> null
            }
        if (current != key) reader.files(key.first, key.second)
    }

    private fun showFiles(files: PushFiles) {
        statusLabel.text = ""
        statusLabel.isVisible = false
        when (files) {
            PushFiles.None -> {
                tree.clear()
                showFilesPlaceholder("")
            }

            is PushFiles.Loading -> {
                tree.clear()
                showFilesPlaceholder("Loading…")
            }

            is PushFiles.Loaded -> {
                tree.show(files.files, keep = false)
                showFilesPlaceholder(if (files.files.isEmpty()) "No files changed." else "")
            }

            PushFiles.Failed -> {
                tree.clear()
                showFilesPlaceholder("The files could not be read.")
            }
        }
    }

    private fun showFilesPlaceholder(text: String) {
        filesPlaceholder.text = text
        filesPlaceholder.isVisible = text.isNotEmpty()
    }

    /** Hands the diff of [file] of the files shown to [showDiff]. */
    private fun showFileDiff(file: GitChangedFile) {
        val files = reader.files.get() as? PushFiles.Loaded ?: return
        statusLabel.text = ""
        statusLabel.isVisible = false
        showDiff(files.commit, files.parent, file) { message ->
            if (reader.files.get() === files) {
                statusLabel.text = message
                statusLabel.isVisible = true
            }
        }
    }

    /** A row of the commits: All with their number, a commit's subject, author and date, or that there are more. */
    private inner class PushCell : ListCell<PushRow>() {
        private val subject =
            Label().apply {
                styleClass += "git-push-subject"
                minWidth = 0.0
                maxWidth = Double.MAX_VALUE
                HBox.setHgrow(this, Priority.ALWAYS)
            }
        private val author = Label().apply { styleClass += "git-push-author" }
        private val date = Label().apply { styleClass += "git-push-date" }
        private val box = HBox(subject, author, date).apply { styleClass += "git-push-cell" }

        override fun updateItem(
            item: PushRow?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = null
            if (empty || item == null) {
                graphic = null
                return
            }
            val commit = (item as? PushRow.Commit)?.commit
            subject.text =
                when (item) {
                    is PushRow.All -> "All ${commitCount(item.count, item.more)}"
                    is PushRow.Commit -> item.commit.subject
                    PushRow.More -> "and more…"
                }
            subject.styleClass.removeAll("git-push-all", "git-push-more")
            if (item is PushRow.All) subject.styleClass += "git-push-all"
            if (item == PushRow.More) subject.styleClass += "git-push-more"
            author.text = commit?.author.orEmpty()
            date.text = commit?.let { DATE_FORMAT.format(it.authorTime) }.orEmpty()
            listOf(author, date).forEach {
                it.isVisible = commit != null
                it.isManaged = commit != null
            }
            graphic = box
        }
    }

    private companion object {
        const val COMMITS_SHARE = 0.5

        val DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
    }
}
