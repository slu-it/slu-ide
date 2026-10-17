package dev.sluide.gitlog

import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitRefs
import javafx.beans.binding.Bindings
import javafx.collections.ListChangeListener
import javafx.css.PseudoClass
import javafx.event.EventTarget
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.Tooltip
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A file's history in the git log area (ADR 0046): its commits in a table as in the log (Subject with the ref labels but
 * no graph, Author, Date, and Path while the file had more than one path), loaded by [history], and the details of the
 * selected one at the right ([CommitDetailsPane], read by [details], its branches by [branches]). Show Diff
 * (double-click, Enter, Shortcut+D), Compare with Local, Show in Log and Copy Revision Number (Shortcut+C) act on the
 * selected row through [showDiff], [compareWithLocal], [showInLog] and the clipboard; the details' hash links go to
 * [CommitDetailsPane.openCommit]. `git-log.css` styles it within `.git-log`.
 */
internal class FileHistoryView(
    val history: FileHistory,
    private val details: CommitDetails,
    private val branches: CommitBranches,
) : BorderPane() {
    /** Shows what the commit of a row changed in the file, against its first parent. */
    var showDiff: (GitLogRow, GitChangedFile) -> Unit = { _, _ -> }

    /** Shows the file at the commit of a row against the working tree. */
    var compareWithLocal: (GitLogRow, GitChangedFile) -> Unit = { _, _ -> }

    /** Shows a commit, by its full hash, in the log. */
    var showInLog: (String) -> Unit = {}

    /** Told whenever the selected row changes. */
    var selectionChanged: () -> Unit = {}

    private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

    internal val showDiffItem = MenuItem("Show Diff").apply { setOnAction { showSelectedDiff() } }
    internal val compareWithLocalItem =
        MenuItem("Compare with Local").apply {
            setOnAction {
                selected()?.let { (row, file) ->
                    compareWithLocal(row, file)
                }
            }
        }
    internal val showInLogItem =
        MenuItem("Show in Log").apply {
            setOnAction { selected()?.let { showInLog(it.first.commit.hash) } }
        }
    internal val copyRevisionNumberItem =
        MenuItem(
            "Copy Revision Number",
        ).apply { setOnAction { copyRevisionNumber() } }

    /** The file's path at each commit; shown only while there is more than one. */
    internal val pathColumn =
        column("Path", 220.0) { history.fileOf(it)?.path.orEmpty() }.apply {
            minWidth = 50.0
            visibleProperty().bind(history.multiplePaths)
        }

    /** The commits that changed the file, newest first; one can be selected. */
    internal val table: TableView<GitLogRow> =
        object : TableView<GitLogRow>(history.rows) {
            override fun createDefaultSkin() = PagingSkin(this, history::shown)
        }.apply {
            styleClass += "git-history-table"
            selectionModel.selectionMode = SelectionMode.SINGLE
            columns.addAll(
                subjectColumn(),
                column("Author", 160.0) { it.commit.author }.apply { minWidth = 50.0 },
                column("Date", 140.0) { dateFormat.format(it.commit.authorTime) }.apply { minWidth = 50.0 },
                pathColumn,
            )
            columnResizePolicy = SubjectTakesTheRest
            placeholder =
                Label().apply {
                    styleClass += "git-history-placeholder"
                    textProperty().bind(history.state.map { it.message(history.key.name) })
                }
            contextMenu =
                ContextMenu(
                    showDiffItem,
                    compareWithLocalItem,
                    showInLogItem,
                    SeparatorMenuItem(),
                    copyRevisionNumberItem,
                )
            val none = Bindings.isNull(selectionModel.selectedItemProperty())
            listOf(showDiffItem, compareWithLocalItem, showInLogItem, copyRevisionNumberItem).forEach {
                it.disableProperty().bind(none)
            }
            setRowFactory {
                TableRow<GitLogRow>().apply {
                    setOnMouseClicked { event ->
                        if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && !isEmpty) {
                            showSelectedDiff()
                            event.consume()
                        }
                    }
                }
            }
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                val handled =
                    when {
                        event.code == KeyCode.ENTER && !event.isShortcutDown -> showSelectedDiff()
                        SHOW_DIFF.match(event) -> showSelectedDiff()
                        COPY.match(event) -> copyRevisionNumber()
                        else -> false
                    }
                if (handled) event.consume()
            }
        }

    /** The details of the selected commit, with its branches and hash links (ADR 0045). */
    internal val detailsPane = CommitDetailsPane(details.state, branches.state)

    /** The table and, at first in the right 30 %, the details. */
    internal val split = SharedSplit(table, detailsPane, 0.7).apply { styleClass += "git-history-body" }

    /** The refs the branches containing a commit were read with; new ones read them again. */
    private var branchesRefs: GitRefs? = null

    init {
        styleClass += "git-history"
        center = split
        details.selection = { table.selectionModel.selectedItems.filterNotNull() }
        details.root = { history.root }
        // Read once the details are shown, so that they never wait for them.
        details.state.subscribe { state ->
            when (state) {
                is CommitDetailsState.Loaded -> {
                    branches.show(history.root, state.row.commit.hash)
                }

                is CommitDetailsState.Loading -> {}

                else -> {
                    branches.show(null, null)
                }
            }
        }
        history.state.subscribe { state ->
            if (state == FileHistoryState.LOADED && history.refs != branchesRefs) {
                branchesRefs = history.refs
                branches.refsChanged()
            }
        }
        history.selectedHash = {
            table.selectionModel.selectedItem
                ?.commit
                ?.hash
        }
        history.select = { index ->
            if (index >= 0) table.selectionModel.clearAndSelect(index) else table.selectionModel.clearSelection()
        }
        table.selectionModel.selectedItems.addListener(
            ListChangeListener {
                selectionChanged()
                details.selectionChanged()
            },
        )
    }

    /** The selected row with the file's change at its commit; `null` without one. */
    private fun selected(): Pair<GitLogRow, GitChangedFile>? {
        val row = table.selectionModel.selectedItem ?: return null
        return history.fileOf(row)?.let { row to it }
    }

    private fun showSelectedDiff(): Boolean {
        val (row, file) = selected() ?: return false
        showDiff(row, file)
        return true
    }

    /** Copies the full hash of the selected commit. */
    private fun copyRevisionNumber(): Boolean {
        val hash =
            table.selectionModel.selectedItem
                ?.commit
                ?.hash ?: return false
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(hash) })
        return true
    }

    /** Drops the reads still going; the tab closes. */
    fun close() {
        history.close()
        details.close()
        branches.close()
        detailsPane.close()
    }
}

private val SELECTED: PseudoClass = PseudoClass.getPseudoClass("selected")

/**
 * A tab in the header of the git log area (ADR 0046): its [title] with a [tip], for a history a close button (×). A
 * primary click selects it ([onSelect]), a middle click or the button closes it ([onClose]); the clicks go no further,
 * so the header does not expand or collapse the area for them. [selected] marks it in the accent colour. The close
 * button stays whole when the tab is narrower than it wants; the title gives way.
 */
internal class AreaTab(
    title: String,
    tip: String?,
    closable: Boolean,
) : HBox() {
    var onSelect: () -> Unit = {}
    var onClose: () -> Unit = {}

    /** The title, at most [MAX_TITLE_WIDTH] wide, shortened with an ellipsis (the tooltip has the path). */
    internal val label =
        Label(title).apply {
            styleClass += "git-log-tab-title"
            maxWidth = MAX_TITLE_WIDTH
        }

    internal val closeButton: Button? =
        if (closable) {
            Button(null, FontIcon(MaterialDesignC.CLOSE)).apply {
                styleClass += "git-log-tab-close"
                tooltip = Tooltip("Close")
                isFocusTraversable = false
                minWidth = USE_PREF_SIZE
                setOnAction { onClose() }
            }
        } else {
            null
        }

    /** Shows the [tip], if there is one. */
    internal val tooltip: Tooltip? = tip?.let(::Tooltip)

    /** Whether this is the selected tab. */
    var selected = false
        set(value) {
            field = value
            pseudoClassStateChanged(SELECTED, value)
        }

    init {
        styleClass += "git-log-tab"
        alignment = Pos.CENTER_LEFT
        children += label
        closeButton?.let(children::add)
        tooltip?.let { Tooltip.install(this, it) }
        setOnMouseClicked { event ->
            val onButton = closeButton?.contains(event.target) == true
            when {
                onButton -> {}

                event.button == MouseButton.PRIMARY -> {
                    onSelect()
                }

                event.button == MouseButton.MIDDLE && closable -> {
                    onClose()
                }
            }
            event.consume()
        }
    }

    private fun Node.contains(target: EventTarget?): Boolean =
        generateSequence(target as? Node) { it.parent }.any { it === this }

    private companion object {
        const val MAX_TITLE_WIDTH = 180.0
    }
}
