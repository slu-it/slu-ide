package dev.sluide.gitlog.area

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.gitlog.commit.CommitLoadState
import dev.sluide.gitlog.commit.CommitReader
import dev.sluide.gitlog.commit.CommitSnapshot
import dev.sluide.gitlog.commit.CommitView
import dev.sluide.gitlog.data.CommitBranches
import dev.sluide.gitlog.data.CommitDetails
import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.data.GitLogSource
import dev.sluide.gitlog.history.FileHistory
import dev.sluide.gitlog.history.FileHistoryKey
import dev.sluide.gitlog.history.FileHistoryState
import dev.sluide.gitlog.history.FileHistoryView
import dev.sluide.gitlog.operations.CommitChanges
import dev.sluide.gitlog.ui.SHORT_HASH
import dev.sluide.gitlog.ui.contains
import javafx.beans.property.BooleanProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.shape.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService

/**
 * The tabs of the git log area (ADR 0046): the Log ([log], focused through [logTable]), the Commit tab (ADR 0048) and
 * the open file histories, with their titles in the header ([titles]). The selected tab decides what the area shows,
 * handed to [show] while [expanded]; only that one loads. Header messages belong to a tab: switching clears the
 * [status]. The views read from [source] on threads of their own, a history's details and branches on [readDetails] and
 * [readBranches]; results are handled through [dispatch]. Go to (Shortcut+G) and the commits a history links to are the
 * log's: they select the Log and go on through [openGoTo] and [goToCommit].
 */
internal class AreaTabs(
    private val pages: GitLogPages,
    private val source: GitLogSource,
    private val diffs: CommitDiffs,
    private val status: HeaderStatus,
    private val expanded: BooleanProperty,
    private val running: ObservableBooleanValue,
    private val log: Node,
    private val logTable: Node,
    private val readDetails: (Runnable) -> Unit,
    private val readBranches: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val focusOwner: () -> Node?,
    private val fileExists: (Path) -> Boolean,
) {
    /** Shows what the selected tab has below the header, `null` nothing (collapsed); set by the view. */
    var show: (Node?) -> Unit = {}

    /** Opens Go to in the log, just selected (ADR 0045); set by the view. */
    var openGoTo: () -> Unit = {}

    /** Goes to the commit with a hash in the log, just selected (ADR 0046); set by the view. */
    var goToCommit: (String) -> Unit = {}

    /** Commits chosen files from the Commit tab (ADR 0048); set by the view. */
    var commit: (CommitChanges) -> Unit = {}

    /** Opens a file of the working tree; set by the view. */
    var openFile: (Path) -> Unit = {}

    /** The header's title while no file history is open (ADR 0046). */
    internal val title = Label("Git Log").apply { styleClass += "git-log-title" }

    /** The tab of the log, first of the header's tabs while the Commit tab or file histories are open (ADR 0046). */
    internal val logTab = AreaTab("Log", null, closable = false).apply { onSelect = { selectTab(AreaSelection.Log) } }

    /** The Commit tab (ADR 0048), right of the Log while open. */
    internal val commitAreaTab =
        AreaTab("Commit", null, closable = true).apply {
            onSelect = { selectTab(AreaSelection.Commit) }
            onClose = ::closeCommit
        }

    /**
     * The title, or the tabs of the log and the open file histories; narrower than they want, the tabs shorten their
     * titles, then the row is cut off at the right, so that the header's buttons stay whole.
     */
    internal val titles =
        HBox(title).apply {
            styleClass += "git-log-tabs"
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            clip =
                Rectangle().also { clip ->
                    clip.widthProperty().bind(widthProperty())
                    clip.heightProperty().bind(heightProperty())
                }
        }

    /** An open file history: its view and its tab in the header (ADR 0046). */
    private class HistoryTab(
        val view: FileHistoryView,
        val tab: AreaTab,
        /** The history's own thread for its pages (ADR 0046), not the log's. */
        val executor: ExecutorService,
    ) {
        fun close() {
            view.close()
            executor.shutdownNow()
        }
    }

    /** The open file histories, in the order of their tabs (ADR 0046). */
    private val histories = LinkedHashMap<FileHistoryKey, HistoryTab>()

    /** What a tab of the header shows: the Log, the Commit tab (ADR 0048) or a file history (ADR 0046). */
    private sealed interface AreaSelection {
        data object Log : AreaSelection

        data object Commit : AreaSelection

        data class History(
            val key: FileHistoryKey,
        ) : AreaSelection
    }

    /** The selected tab. */
    private var selected: AreaSelection = AreaSelection.Log

    /** What [show] got last. */
    private var content: Node? = null

    /** The views of the open file histories, in the order of their tabs. */
    internal val historyViews: List<FileHistoryView> get() = histories.values.map { it.view }

    /** The tabs of the open file histories, in their order. */
    internal val historyTabs: List<AreaTab> get() = histories.values.map { it.tab }

    /** The view of the selected file history; `null` while the Log or the Commit tab is selected. */
    internal val selectedHistoryView: FileHistoryView?
        get() = (selected as? AreaSelection.History)?.key?.let(histories::get)?.view

    /** The Commit tab's view and its own thread (ADR 0048), kept with its draft while the tab is closed. */
    private class CommitTab(
        val view: CommitView,
        /** Reads the tab's changes, `HEAD`'s message and diffs: `sluide-git-commit`. */
        val executor: ExecutorService,
    ) {
        fun close() {
            view.close()
            executor.shutdownNow()
        }
    }

    /** The Commit tab's view with its draft, from [showCommit] until another folder; also while the tab is closed. */
    private var commitTab: CommitTab? = null

    /** Whether the Commit tab is open in the header. */
    private var commitOpen = false

    /** The Commit tab's view, with its draft; `null` before [showCommit] and after another folder. */
    internal val commitView: CommitView? get() = commitTab?.view

    /** Whether the Commit tab is open in the header. */
    internal val commitShown: Boolean get() = commitOpen

    /**
     * Shows what the selected tab has below the header while [expanded]: the log, the Commit tab (ADR 0048) or a file
     * history (ADR 0046); only that one loads.
     */
    fun showContent() {
        val isExpanded = expanded.get()
        val history = selectedHistoryView
        val commit = commitTab?.view?.takeIf { selected == AreaSelection.Commit }
        content = if (isExpanded) history ?: commit ?: log else null
        show(content)
        pages.visible = isExpanded && history == null && commit == null
        histories.values.forEach { it.view.history.visible = isExpanded && it.view === history }
        commitTab?.view?.reader?.visible = isExpanded && commit != null
    }

    /** Gives the focus to what is shown: the table of the log or a file history, the Commit tab's message. */
    fun focusContent() {
        val content = content ?: return
        // The split pane adds the table to the scene with its skin, which CSS creates.
        content.applyCss()
        val commit = commitTab?.view?.takeIf { selected == AreaSelection.Commit }
        if (commit != null) commit.focusMessage() else (selectedHistoryView?.table ?: logTable).requestFocus()
    }

    /**
     * Opens the Commit tab (ADR 0048), or selects it if it is open, expands the area and gives the message the focus;
     * the draft of the tab closed before is kept. Nothing without a repository.
     */
    fun showCommit() {
        val root = pages.root ?: return
        if (commitTab == null) commitTab = commitTab(root)
        if (!commitOpen) {
            commitOpen = true
            updateTabs()
        }
        selectTab(AreaSelection.Commit)
    }

    /** The working tree changed: the Commit tab reads its changes again, at once if shown, else when shown next. */
    fun workingTreeChanged() {
        commitTab?.view?.reader?.reload()
    }

    /** A new Commit tab for the repository at [root], with a thread of its own, not read before it is shown. */
    private fun commitTab(root: Path): CommitTab {
        val commitExecutor =
            NamedThreads.single("sluide-git-commit")
        val reader =
            CommitReader(
                root,
                { repository ->
                    val head = source.headCommit(repository)
                    source.commitChanges(repository)?.let { CommitSnapshot(it, head) }
                },
                source::headMessage,
                commitExecutor::execute,
                dispatch,
            )
        val view =
            CommitView(reader, running).apply {
                commit = { operation -> this@AreaTabs.commit(operation) }
                showDiff = { file, head -> diffs.showHeadDiff(root, commitExecutor, file, head) }
                openFile = { file -> diffs.localFile(file)?.let(this@AreaTabs.openFile) }
                exists = { file -> diffs.localFile(file)?.let(fileExists) == true }
                failedWithChanges = { if (selected == AreaSelection.Commit) status.tell(COMMIT_READ_FAILED) }
                tell = status::tell
                tree.selectionModel.selectedItemProperty().subscribe { _ -> status.selectionChanged() }
                goesToLog()
            }
        return CommitTab(view, commitExecutor)
    }

    /** Closes the Commit tab, keeping its draft; if it was selected, the Log is. */
    private fun closeCommit() {
        if (!commitOpen) return
        val focusInClosed = commitTab?.view?.contains(focusOwner()) == true
        commitOpen = false
        if (selected == AreaSelection.Commit) {
            selected = AreaSelection.Log
            showContent()
            if (expanded.get() && focusInClosed) focusContent()
            tabChanged()
        }
        updateTabs()
    }

    /**
     * Shows the history of [file] (ADR 0046), a file below the opened repository, from `HEAD` on, in a tab of the area:
     * the one that shows it already, or a new one; selects it and expands the area. Ignored for files outside the
     * repository, for folders and without a repository.
     */
    fun showHistory(file: Path) {
        val root = pages.root ?: return
        val relative =
            try {
                root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
            } catch (_: IllegalArgumentException) {
                return
            }
        if (relative.toString().isEmpty() || relative.startsWith("..") || Files.isDirectory(file)) return
        openHistory(FileHistoryKey(relative.joinToString("/")))
    }

    /**
     * Shows the history of [file], one of the files [commit] changed, up to that commit (ADR 0046): of its path there
     * from the commit on; for a deleted file of its old path from the commit's first parent on.
     */
    fun showHistoryUpTo(
        commit: GitCommit,
        file: GitChangedFile,
    ) {
        val key =
            if (file.change == GitChange.DELETED) {
                FileHistoryKey(file.path, commit.parents.firstOrNull() ?: return)
            } else {
                FileHistoryKey(file.path, commit.hash)
            }
        openHistory(key)
    }

    /** Selects the tab of the history [key], opening one if there is none, and expands the area. */
    private fun openHistory(key: FileHistoryKey) {
        val root = pages.root ?: return
        if (key !in histories) histories[key] = historyTab(root, key)
        selectTab(AreaSelection.History(key))
    }

    /** A new tab for the history [key] of the repository at [root], not loaded before it is shown. */
    private fun historyTab(
        root: Path,
        key: FileHistoryKey,
    ): HistoryTab {
        val pagesExecutor =
            NamedThreads.single("sluide-git-history")
        val history = FileHistory(root, key, source::fileHistory, source::refs, pagesExecutor::execute, dispatch)
        val details =
            CommitDetails(source::details, { _, _ -> emptyList() }, readDetails, dispatch, source::resolveCommits)
        val branches =
            CommitBranches(source::branchesContaining, readBranches, dispatch).apply {
                currentBranch = { history.refs?.currentBranch }
            }
        val view =
            FileHistoryView(history, details, branches).apply {
                showDiff = diffs::showDiff
                compareWithLocal = { row, file -> diffs.compareWithLocal(key, row, file) }
                showInLog = ::showInLog
                detailsPane.openCommit = ::showInLog
                selectionChanged = { status.selectionChanged() }
                // Find has nothing to filter in a history.
                goesToLog()
            }
        history.failedWithRows =
            { if (selected == AreaSelection.History(key)) status.tell(FileHistoryState.FAILED.message(key.name)) }
        val tip = key.path + key.start?.let { " up to ${it.take(SHORT_HASH)}" }.orEmpty()
        val tab =
            AreaTab("History: ${key.name}", tip, closable = true).apply {
                onSelect = { selectTab(AreaSelection.History(key)) }
                onClose = { closeHistory(key) }
            }
        return HistoryTab(view, tab, pagesExecutor)
    }

    /** Go to (Shortcut+G) in this tab is the log's: it selects the Log and opens Go to there (ADR 0046). */
    private fun Node.goesToLog() {
        addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (GO_TO.match(event)) {
                selectTab(AreaSelection.Log)
                openGoTo()
                event.consume()
            }
        }
    }

    /**
     * Selects the tab [selection] if it is open: shows what it has, expanding the area if collapsed, and gives it the
     * focus.
     */
    private fun selectTab(selection: AreaSelection) {
        val open =
            when (selection) {
                AreaSelection.Log -> true
                AreaSelection.Commit -> commitOpen
                is AreaSelection.History -> selection.key in histories
            }
        if (!open) return
        val changed = selection != selected
        selected = selection
        updateTabs()
        if (changed) tabChanged()
        if (!expanded.get()) {
            // Shows and focuses it.
            expanded.set(true)
            return
        }
        showContent()
        focusContent()
    }

    /** Closes the tab of the history [key]; if it was selected, the tab left of it is. */
    private fun closeHistory(key: FileHistoryKey) {
        val order = tabOrder()
        val closed = histories.remove(key) ?: return
        val focusInClosed = closed.view.contains(focusOwner())
        closed.close()
        val selection = AreaSelection.History(key)
        if (selected == selection) {
            selected = order.getOrElse(order.indexOf(selection) - 1) { AreaSelection.Log }
            showContent()
            if (expanded.get() && focusInClosed) focusContent()
            tabChanged()
        }
        updateTabs()
    }

    /** The open tabs in their order: the Log, the Commit tab if open, the file histories. */
    private fun tabOrder(): List<AreaSelection> =
        listOf(AreaSelection.Log) + listOfNotNull(AreaSelection.Commit.takeIf { commitOpen }) +
            histories.keys.map(AreaSelection::History)

    /**
     * Another tab is selected: the header's message of the one before goes, the log's failure shows only with the Log,
     * and a history or the Commit tab whose last read failed while it was not shown (with rows or changes kept) says so
     * again.
     */
    private fun tabChanged() {
        status.clear()
        status.logShown(selected == AreaSelection.Log)
        if (selected == AreaSelection.Commit) {
            val reader = commitTab?.view?.reader ?: return
            if (reader.state.get() == CommitLoadState.FAILED && reader.snapshot != null) status.tell(COMMIT_READ_FAILED)
            return
        }
        val history = selectedHistoryView?.history ?: return
        if (history.state.get() == FileHistoryState.FAILED && history.rows.isNotEmpty()) {
            status.tell(FileHistoryState.FAILED.message(history.key.name))
        }
    }

    /**
     * Closes all file histories and the Commit tab, discarding its draft, e.g. for another folder; the Log is shown.
     */
    fun closeAll() {
        histories.values.forEach { it.close() }
        histories.clear()
        commitTab?.close()
        commitTab = null
        commitOpen = false
        selected = AreaSelection.Log
        status.logShown(true)
        updateTabs()
        showContent()
    }

    /** The header's title while only the Log is open, else the tabs, the selected one marked. */
    private fun updateTabs() {
        val tabs = listOf(logTab) + listOfNotNull(commitAreaTab.takeIf { commitOpen }) + historyTabs
        titles.children.setAll(if (tabs.size == 1) listOf(title) else tabs)
        logTab.selected = selected == AreaSelection.Log
        commitAreaTab.selected = selected == AreaSelection.Commit
        histories.forEach { (key, open) -> open.tab.selected = selected == AreaSelection.History(key) }
    }

    /** Selects the Log and goes to the commit [hash] there, the header telling why not (ADR 0046). */
    private fun showInLog(hash: String) {
        selectTab(AreaSelection.Log)
        goToCommit(hash)
    }

    /** The file histories and the Commit tab read again, now if shown, else when shown next. */
    fun refresh() {
        histories.values.forEach { it.view.history.refresh() }
        commitTab?.view?.reader?.reload()
    }

    /** Closes the file histories and the Commit tab, dropping their reads; the window closes. */
    fun close() {
        histories.values.forEach { it.close() }
        commitTab?.close()
    }
}

/** What the header says when the Commit tab's changes could not be read again while it shows those read before. */
private const val COMMIT_READ_FAILED = "The changes could not be read."
