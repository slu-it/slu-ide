package dev.sluide.gitlog

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.git.GitBranches
import dev.sluide.git.GitCommits
import dev.sluide.git.GitLog
import dev.sluide.git.GitPush
import dev.sluide.git.GitRebases
import dev.sluide.git.GitRefs
import dev.sluide.git.GitRemotes
import dev.sluide.gitlog.actions.RefActions
import dev.sluide.gitlog.area.AreaTabs
import dev.sluide.gitlog.area.CommitDiffs
import dev.sluide.gitlog.area.CommitNavigation
import dev.sluide.gitlog.area.CompareBar
import dev.sluide.gitlog.area.GO_TO
import dev.sluide.gitlog.area.HeaderBar
import dev.sluide.gitlog.area.HeaderStatus
import dev.sluide.gitlog.branches.BranchesPane
import dev.sluide.gitlog.data.CommitBranches
import dev.sluide.gitlog.data.CommitDetails
import dev.sluide.gitlog.data.CommitDetailsState
import dev.sluide.gitlog.data.FileDiffs
import dev.sluide.gitlog.data.GitLogCliSource
import dev.sluide.gitlog.data.GitLogCliWrites
import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.data.GitLogSource
import dev.sluide.gitlog.data.GitLogState
import dev.sluide.gitlog.data.GitLogWrites
import dev.sluide.gitlog.details.ChangedFilesPane
import dev.sluide.gitlog.details.CommitDetailsPane
import dev.sluide.gitlog.filter.FilterBar
import dev.sluide.gitlog.filter.FilterSources
import dev.sluide.gitlog.operations.Fetch
import dev.sluide.gitlog.operations.PendingBar
import dev.sluide.gitlog.table.CommitTable
import dev.sluide.gitlog.ui.GIT_LOG_CSS
import dev.sluide.gitlog.ui.SHORT_HASH
import dev.sluide.gitlog.ui.SharedSplit
import dev.sluide.gitlog.ui.contains
import dev.sluide.gitlog.workingtree.WorkingTreeWindow
import javafx.application.Platform
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.collections.ListChangeListener
import javafx.geometry.Orientation
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * The git log area of a window (ADR 0035): a header bar ("Git Log", a chevron, Refresh) and, while [expanded], the
 * commits of the repository given to [open] in a table, loaded in pages in the background as the user scrolls, with
 * the refs pointing at them, and to the right the files the selected commit changed above its details (ADRs 0037,
 * 0038). The diff of a changed file goes to [onShowDiff], a file to open to [onOpenFile]. Above the table, the
 * filters of the commits (ADR 0039), per window and reset by [open]. Left of them, the branches pane (ADR 0040).
 * The context menus of the table and the branches pane check out, create, rename and delete branches and tags
 * (ADR 0041); the table's also cherry-picks and reverts commits and resets the current branch, and a bar above the
 * table continues or aborts a cherry-pick or revert in progress (ADR 0042). Fetch in the header (all remotes) and in the
 * branches pane (one remote) fetches, cancellable while it runs (ADR 0043). Operations that change files ask
 * [beforeFileChange] first. Compare with Current in the branches pane lists one side of the comparison of a ref with
 * `HEAD`, with a bar above the table; Show Diff with Working Tree (branches pane) and Compare with Local (table) open a
 * [WorkingTreeWindow] with the files that differ from the working tree, whose diffs go to [onShowWorkingTreeDiff]
 * (ADR 0044). [showHistory] (and Show History in the changed files) opens a file's history in a tab of the area; while
 * one is open, the header's title becomes a row of tabs, the Log first, and the selected tab decides what the area shows
 * (ADR 0046). [showCommit] opens the Commit tab, right of the Log, to commit chosen files; its draft stays while it is
 * closed, until another folder (ADR 0048); [workingTreeChanged] has it read the changes again. [showPush] (and Push in
 * the header, Commit and Push in the Commit tab) opens the Push dialog, which pushes the current branch (ADR 0049).
 *
 * [source] reads the commits, refs, details, changed files and diffs on the thread `sluide-git-log` (a history, the
 * Commit tab and the Push dialog on threads of their own); [writes] changes the repository on the thread
 * `sluide-git-write`, one operation at a time. Their results are handled through [dispatch], the JavaFX thread in the
 * application.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class GitLogView internal constructor(
    private val source: GitLogSource,
    private val dispatch: (Runnable) -> Unit,
    private val writes: GitLogWrites,
    private val tasks: BackgroundTasks,
) : BorderPane(),
    AutoCloseable {
    @Autowired
    constructor(
        gitLog: GitLog,
        gitBranches: GitBranches,
        gitCommits: GitCommits,
        gitRebases: GitRebases,
        gitRemotes: GitRemotes,
        gitPush: GitPush,
        tasks: BackgroundTasks,
    ) : this(
        GitLogCliSource(gitLog, gitRemotes, gitCommits, gitPush),
        Platform::runLater,
        GitLogCliWrites(gitBranches, gitCommits, gitRebases, gitRemotes, gitPush),
        tasks,
    )

    private val executor =
        NamedThreads.single("sluide-git-log")

    private val pages =
        GitLogPages(
            source::page,
            source::refs,
            source::hashMatch,
            executor::execute,
            dispatch,
            tasks,
            loadPending = source::pending,
            loadRemotes = source::remotes,
            loadCounts = source::comparisonCounts,
        )

    private val details =
        CommitDetails(
            source::details,
            { root, commit, cancellation ->
                source.changedFiles(root, commit.hash, commit.parents.firstOrNull(), cancellation)
            },
            executor::execute,
            dispatch,
            source::resolveCommits,
        )

    private val fileDiffs = FileDiffs(source::fileDiff, executor::execute, dispatch)

    /** Reads the branches containing the shown commit (ADR 0045), not behind the pages of the log nor delaying them. */
    private val branchesExecutor =
        NamedThreads.single("sluide-git-branches")

    /** The branches containing the commit whose details are shown (ADR 0045). */
    private val commitBranches =
        CommitBranches(source::branchesContaining, branchesExecutor::execute, dispatch).apply {
            currentBranch = { pages.refs?.currentBranch }
        }

    /** The refs the branches containing a commit were read with; new ones read them again. */
    private var branchesRefs: GitRefs? = null

    /** Reads for the working-tree windows (ADR 0044), not behind the pages of the log. */
    private val compareExecutor =
        NamedThreads.single("sluide-git-compare")

    /**
     * Asked before an operation changes files on disk, with its action ("Check Out", "Cherry-Pick"), e.g. to save or
     * keep unsaved editors; `false` cancels the operation. Set by the window.
     */
    var beforeFileChange: (action: String) -> Boolean = { true }

    /** Whether the commits are shown below the header; collapsed (`false`) at first. */
    val expanded: BooleanProperty = SimpleBooleanProperty(false)

    /**
     * Called when the log collapses while the focus is in it, to give the focus to what should have it instead (the
     * window's editor); by default nothing is focused on purpose.
     */
    var returnFocus: () -> Unit = {}

    /** Shows what a commit changed in a file, e.g. side by side in an editor tab; set by the window (ADR 0038). */
    var onShowDiff: (CommitFileDiff) -> Unit = {}

    /** Opens a file of the working tree, e.g. in the editor; set by the window (ADR 0038). */
    var onOpenFile: (Path) -> Unit = {}

    /**
     * Told before an operation runs git, with whether it holds the git status (it changes or commits files); the window
     * lets its status wait for the operation's end (ADR 0062).
     */
    var onWriteStarted: (holdsStatus: Boolean) -> Unit = {}

    /**
     * Told after every operation, also a failed one (which may have done a part), to read the log again: new and
     * renamed branches alone are not noticed by the watch of `.git` (ADR 0041). The window lets the folder's trust check
     * what the operation may have made git run (ADR 0053) and its status count the change, on which it refreshes the
     * log once (ADR 0062); by default the log refreshes itself.
     */
    var onWriteFinished: () -> Unit = { refresh() }

    /**
     * Shows how a file differs between a commit and the working tree, e.g. side by side in an editor tab with the live
     * file on the right; `false` if it cannot be shown as text. Set by the window (ADR 0044).
     */
    var onShowWorkingTreeDiff: (WorkingTreeFileDiff) -> Boolean = { true }

    /** Whether there is a file at a path (not following a link); tests replace it, their repository is not on disk. */
    @Volatile
    internal var fileExists: (Path) -> Boolean = { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }

    /** The header's message (ADR 0046), with what the operation going on does and the log's failure. */
    private val status = HeaderStatus(pages)

    /** The bar of a comparison of a ref with `HEAD` (ADR 0044) below the filter bar; its `emptySide` is for the table. */
    internal val compareBar: CompareBar =
        CompareBar(pages, status::clear).apply {
            comparingShown = { comparing -> filterBar.branch.isDisable = comparing }
        }

    /** Shows the diffs of commits and with the working tree (ADR 0038, 0044); the callbacks are the window's. */
    internal val diffs =
        CommitDiffs(
            pages,
            source,
            fileDiffs,
            compareExecutor::execute,
            dispatch,
            { scene?.window },
            status::selection,
            { path -> fileExists(path) },
            status::tell,
        ).apply {
            showFileDiff = { diff -> onShowDiff(diff) }
            openFile = { file -> onOpenFile(file) }
            showWorkingTreeFileDiff = { diff -> onShowWorkingTreeDiff(diff) }
        }

    /** Starts the operations that change the repository (ADR 0041) and opens the Push dialog (ADR 0049). */
    internal val actions =
        RefActions(
            pages,
            source,
            writes,
            dispatch,
            diffs::showCommitDiff,
            diffs::localFile,
            { scene?.window },
            { path -> fileExists(path) },
            status::clear,
            status::operation,
            tasks,
        ).apply {
            beforeFileChange = { action -> this@GitLogView.beforeFileChange(action) }
            onWriteStarted = { holdsStatus -> this@GitLogView.onWriteStarted(holdsStatus) }
            onWriteFinished = { this@GitLogView.onWriteFinished() }
            openFile = { file -> onOpenFile(file) }
        }

    /** Whether the last read found no remote to fetch; `false` while that is not known (ADR 0043). */
    private val noRemotes = SimpleBooleanProperty(false)

    /** The bar of what git is in the middle of, below the filter bar (ADR 0042); the table's menu reads its `busy`. */
    internal val pendingBar =
        PendingBar(actions.running) { scene?.window }.apply { start = { operation -> actions.start(operation) } }

    /** The commits with their graph, newest first, and their context menu. */
    internal val table =
        CommitTable(pages, compareBar.emptySide, pendingBar.busy, actions).apply {
            compareWithLocal = { commit ->
                diffs.showWorkingTreeDiff(commit.hash, commit.hash.take(SHORT_HASH))
            }
            openGoTo = { navigation.openGoTo() }
            stopComparing = compareBar::stopComparing
        }

    /** Goes to commits in the table: of branches, linked ones and with Go to (ADRs 0040, 0045). */
    internal val navigation: CommitNavigation =
        CommitNavigation(
            pages,
            table,
            source::resolveCommits,
            executor::execute,
            dispatch,
            { filterSources.branchItems().drop(1) },
            { branchesPane.selectedRef },
            { body.firstShown },
            { detailsPane.contains(scene?.focusOwner) },
            status::tell,
        ).apply { clearFilters = { filterBar.clearFilters() } }

    /** What the filters above the table offer to pick. */
    private val filterSources: FilterSources =
        FilterSources(pages, source::userName, source::paths, executor::execute, dispatch).apply {
            userNameRead = { filterBar.user.popup.refresh() }
            pathsRead = { filterBar.path.popup.refresh() }
        }

    /** The filters above the table (ADR 0039). */
    internal val filterBar =
        FilterBar(
            filterSources::branchItems,
            filterSources::userItems,
            filterSources::pathItems,
            filterSources::readUserName,
            filterSources::readPaths,
            filterSources::freePath,
        ).apply {
            onChange = { filter -> pages.filter = filter }
            onDown = { table.requestFocus() }
        }

    /** The filter bar, the bar of a comparison and the bar of a cherry-pick or revert in progress above the table. */
    internal val commitsPane =
        BorderPane(table).apply {
            styleClass += "git-log-commits"
            top = VBox(filterBar, compareBar, pendingBar)
        }

    /** The details of the selected commit. */
    internal val detailsPane =
        CommitDetailsPane(details.state, commitBranches.state).apply {
            openCommit = { hash -> navigation.goToCommit(hash, hash.take(SHORT_HASH)) }
        }

    /** The files the selected commit changed. */
    internal val changesPane = ChangedFilesPane(details.files)

    /** The changed files above the details, half and half at first. */
    internal val side =
        SharedSplit(changesPane, detailsPane, 0.5, Orientation.VERTICAL).apply { styleClass += "git-log-side" }

    /** The branches, left of the commits (ADR 0040). */
    internal val branchesPane =
        BranchesPane().apply {
            navigate = navigation::navigateTo
            moved = navigation::branchMoved
            filter = { names ->
                // Filtering by branches ends a comparison, which would hide them.
                compareBar.stopComparing()
                filterBar.setBranches(names)
            }
            checkout = { operation -> actions.start(operation) }
            newBranchFrom = { ref -> actions.newBranch(ref.name, ref.shortName) }
            compare = compareBar::compareWith
            showWorkingTreeDiff = { ref -> diffs.showWorkingTreeDiff(ref.name, ref.shortName) }
            rename = actions::renameBranch
            delete = actions::deleteRefs
            fetch = { remote -> actions.start(Fetch(remote)) }
            writing.bind(actions.running)
        }

    /** The filters and the table and, at first in the right 30 %, the changed files and details ([SharedSplit]). */
    internal val inner = SharedSplit(commitsPane, side, 0.7).apply { styleClass += "git-log-inner" }

    /** The branches pane, while shown at first in the left 20 %, and the rest ([SharedSplit]). */
    internal val body =
        SharedSplit(branchesPane, inner, 0.2).apply {
            styleClass += "git-log-body"
            // A filter, so that no control in the body handles it first.
            addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                if (FIND.match(event)) {
                    if (branchesPane.contains(event.target)) branchesPane.focusSearch() else filterBar.focusText()
                    event.consume()
                } else if (GO_TO.match(event)) {
                    navigation.openGoTo()
                    event.consume()
                }
            }
        }

    /** The tabs of the area (ADRs 0046, 0048): the Log, the Commit tab and the file histories. */
    internal val tabs =
        AreaTabs(
            pages,
            source,
            diffs,
            status,
            expanded,
            actions.running,
            body,
            table,
            executor::execute,
            branchesExecutor::execute,
            dispatch,
            { scene?.focusOwner },
            { path -> fileExists(path) },
            tasks,
        ).apply {
            show = { content -> center = content }
            openGoTo = {
                // Laid out now: the popup is placed by the table's bounds, which it may never have had yet.
                this@GitLogView.applyCss()
                this@GitLogView.layout()
                navigation.openGoTo()
            }
            goToCommit = { hash -> navigation.goToCommit(hash, hash.take(SHORT_HASH)) }
            commit = { operation -> actions.start(operation) }
            openFile = { file -> onOpenFile(file) }
        }

    /** The header bar; clicking it (but not its buttons or tabs) expands or collapses the log. */
    private val headerBar =
        HeaderBar(
            tabs.titles,
            status.label,
            expanded,
            actions.running,
            actions.cancellable,
            noRemotes,
            ::refresh,
            { actions.start(Fetch()) },
            ::showPush,
            actions::cancel,
        )
    internal val header: HBox get() = headerBar.node
    internal val refreshButton get() = headerBar.refreshButton
    internal val fetchButton get() = headerBar.fetchButton
    internal val pushButton get() = headerBar.pushButton
    internal val cancelButton get() = headerBar.cancelButton

    init {
        styleClass += "git-log"
        stylesheets += GIT_LOG_CSS
        stylesheets += FILE_ICONS_CSS
        top = header
        // Here, as the tabs need the actions first.
        actions.committed = { operation -> tabs.commitView?.committed(operation) }
        pages.selectedHashes = { table.selectionModel.selectedItems.map { it.commit.hash } }
        pages.select = { indices ->
            table.selectionModel.clearSelection()
            indices.forEach(table.selectionModel::select)
        }
        details.selection = { table.selectionModel.selectedItems.filterNotNull() }
        details.root = { pages.root }
        // Read once the details are shown, so that they never wait for them; kept while the next commit's load.
        details.state.subscribe { state ->
            when (state) {
                is CommitDetailsState.Loaded -> {
                    commitBranches.show(pages.root, state.row.commit.hash)
                }

                is CommitDetailsState.Loading -> {}

                else -> {
                    commitBranches.show(null, null)
                }
            }
        }
        table.selectionModel.selectedItems.addListener(
            ListChangeListener {
                status.selectionChanged()
                details.selectionChanged()
            },
        )
        changesPane.tree.selectionModel
            .selectedItemProperty()
            .subscribe { _ -> status.selectionChanged(keepMessage = changesPane.tree.rebuilding) }
        // The refs are read with every first page, before its state is set.
        pages.state.subscribe { state ->
            when (state) {
                GitLogState.IDLE -> {
                    pendingBar.show(null)
                    table.refsChanged()
                    noRemotes.set(false)
                    branchesPane.remotes = emptyList()
                }

                GitLogState.LOADING -> {
                    branchesPane.loading()
                }

                else -> {
                    if (pages.refs != branchesRefs) {
                        branchesRefs = pages.refs
                        commitBranches.refsChanged()
                    }
                    branchesPane.show(pages.refs)
                    compareBar.update()
                    pendingBar.show(pages.pending)
                    noRemotes.set(pages.remotes?.isEmpty() == true)
                    branchesPane.remotes = pages.remotes.orEmpty()
                    table.refsChanged()
                }
            }
        }
        branchesPane.bar.minHeightProperty().bind(filterBar.heightProperty())
        filterBar.branchesToggle.selectedProperty().subscribe { _, shown ->
            val focusInPane = branchesPane.contains(scene?.focusOwner)
            body.firstShown = shown
            if (!shown && focusInPane) table.requestFocus()
        }
        pages.comparisonEnded = { comparison, end ->
            compareBar.comparisonChanged()
            status.tellAfterLoad(compareBar.endMessage(comparison, end))
        }
        changesPane.showDiff = diffs::showDiff
        changesPane.openFile = { file -> diffs.localFile(file)?.let(onOpenFile) }
        changesPane.exists = { file -> diffs.localFile(file)?.let(Files::exists) == true }
        changesPane.showHistory = { row, file -> tabs.showHistoryUpTo(row.commit, file) }
        expanded.subscribe { isExpanded ->
            val focusInBody = center?.contains(scene?.focusOwner) == true
            tabs.showContent()
            if (isExpanded) {
                tabs.focusContent()
            } else if (focusInBody) {
                returnFocus()
            }
        }
    }

    /**
     * Opens the Commit tab (ADR 0048), or selects it if it is open, expands the area and gives the message the focus;
     * the draft of the tab closed before is kept. Nothing without a repository.
     */
    fun showCommit() = tabs.showCommit()

    /**
     * Opens the Push dialog of the repository (ADR 0049), or brings the open one to the front; also while an operation
     * runs, with Push disabled until it ends. Nothing without a repository.
     */
    fun showPush() = actions.showPush()

    /**
     * The working tree changed (the window's git status, ADR 0013): the Commit tab reads its changes again, at once if
     * shown, else when it is shown next; nothing without one.
     */
    fun workingTreeChanged() = tabs.workingTreeChanged()

    /**
     * Shows the history of [file] (ADR 0046), a file below the opened repository, from `HEAD` on, in a tab of the area:
     * the one that shows it already, or a new one; selects it and expands the area. Ignored for files outside the
     * repository, for folders and without a repository.
     */
    fun showHistory(file: Path) = tabs.showHistory(file)

    /** Shows the log of the repository at [root]; `null` shows nothing. Loads once (or as soon as) [expanded]. */
    fun open(root: Path?) {
        fileDiffs.close()
        diffs.closeWorkingTreeWindows()
        // The Push dialog closes; an operation going on in the previous repository ends unnoticed; a fetch is stopped
        // (ADR 0043).
        actions.reset()
        navigation.goToPopup.hide()
        // Before the pages, which may start loading and have the pane say so.
        branchesPane.reset()
        commitBranches.clear()
        branchesRefs = null
        // Resets the filter and ends a comparison too, before the bar, which then tells no change.
        pages.open(root)
        tabs.closeAll()
        filterBar.reset()
        compareBar.comparisonChanged()
        filterSources.reset()
    }

    /** Shows [text] in the header, e.g. why a diff cannot be shown, until the next selection or load. */
    fun showStatus(text: String) = status.tell(text)

    /**
     * Loads the first page again now if [expanded], else the next time it is expanded; keeps the selection by hash.
     * Called for the Refresh button and for changes in `.git`, after which `HEAD`'s tree is read again too. The file
     * histories and the Commit tab read again as well, now if shown, else when shown next.
     */
    fun refresh() {
        // The tree may have changed: read again when the path popup is shown next.
        filterSources.markPathsStale()
        pages.refresh()
        tabs.refresh()
    }

    /**
     * Completes once everything the threads reading the log, the details and the branches were given so far has run;
     * for tests that assert that something was not read.
     */
    internal fun readersIdle(): CompletableFuture<Void> {
        val log = CompletableFuture<Unit>()
        executor.execute { log.complete(Unit) }
        val branches = CompletableFuture<Unit>()
        branchesExecutor.execute { branches.complete(Unit) }
        return CompletableFuture.allOf(log, branches)
    }

    override fun close() {
        tabs.close()
        diffs.closeWorkingTreeWindows()
        actions.close()
        compareExecutor.shutdownNow()
        pages.close()
        details.close()
        fileDiffs.close()
        commitBranches.close()
        branchesExecutor.shutdownNow()
        detailsPane.close()
        changesPane.close()
        executor.shutdownNow()
    }
}

private val FIND = KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN)
