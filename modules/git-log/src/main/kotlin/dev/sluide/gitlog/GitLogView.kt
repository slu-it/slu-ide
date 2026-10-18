package dev.sluide.gitlog

import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.git.GitBranches
import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommits
import dev.sluide.git.GitLog
import dev.sluide.git.GitPick
import dev.sluide.git.GitPush
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitRevisionResolution
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.collections.ListChangeListener
import javafx.event.EventTarget
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TableView
import javafx.scene.control.Tooltip
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.shape.Rectangle
import javafx.stage.WindowEvent
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignR
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
) : BorderPane(),
    AutoCloseable {
    @Autowired
    constructor(
        gitLog: GitLog,
        gitBranches: GitBranches,
        gitCommits: GitCommits,
        gitRemotes: GitRemotes,
        gitPush: GitPush,
    ) : this(
        GitLogCliSource(gitLog, gitRemotes, gitCommits, gitPush),
        Platform::runLater,
        GitLogCliWrites(gitBranches, gitCommits, gitRemotes, gitPush),
    )

    private val executor =
        Executors.newSingleThreadExecutor { Thread(it, "sluide-git-log").apply { isDaemon = true } }

    private val pages =
        GitLogPages(
            source::page,
            source::refs,
            source::hashMatch,
            executor::execute,
            dispatch,
            loadPending = source::pending,
            loadRemotes = source::remotes,
            loadCounts = source::comparisonCounts,
        )

    private val details =
        CommitDetails(
            source::details,
            { root, commit -> source.changedFiles(root, commit.hash, commit.parents.firstOrNull()) },
            executor::execute,
            dispatch,
            source::resolveCommits,
        )

    private val fileDiffs = FileDiffs(source::fileDiff, executor::execute, dispatch)

    /** Reads the branches containing the shown commit (ADR 0045), not behind the pages of the log nor delaying them. */
    private val branchesExecutor =
        Executors.newSingleThreadExecutor { Thread(it, "sluide-git-branches").apply { isDaemon = true } }

    /** The branches containing the commit whose details are shown (ADR 0045). */
    private val commitBranches =
        CommitBranches(source::branchesContaining, branchesExecutor::execute, dispatch).apply {
            currentBranch = { pages.refs?.currentBranch }
        }

    /** The refs the branches containing a commit were read with; new ones read them again. */
    private var branchesRefs: GitRefs? = null

    /** Reads for the working-tree windows (ADR 0044), not behind the pages of the log. */
    private val compareExecutor =
        Executors.newSingleThreadExecutor { Thread(it, "sluide-git-compare").apply { isDaemon = true } }

    private val writeExecutor =
        Executors.newSingleThreadExecutor { Thread(it, "sluide-git-write").apply { isDaemon = true } }

    /** Runs the operations that change the repository, one at a time (ADR 0041). */
    private val writer = RefWriter(writes, writeExecutor::execute, dispatch)

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
     * Called after every operation, also a failed one, to read the log again; the window first lets the folder's trust
     * check what the operation may have made git run (ADR 0053), and refreshes the log after that.
     */
    var afterWrite: () -> Unit = { refresh() }

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

    private val chevron = FontIcon(MaterialDesignC.CHEVRON_RIGHT)

    internal val refreshButton =
        Button(null, FontIcon(MaterialDesignR.REFRESH)).apply {
            styleClass += "git-log-refresh"
            tooltip = Tooltip("Refresh")
            isFocusTraversable = false
            // Always whole: the tabs left of it give way first.
            minWidth = USE_PREF_SIZE
            setOnAction { refresh() }
        }

    /** Whether the last read found no remote to fetch; `false` while that is not known (ADR 0043). */
    private val noRemotes = SimpleBooleanProperty(false)

    /** Fetches all remotes (ADR 0043); not while an operation runs or there is no remote. */
    internal val fetchButton =
        Button(null, FontIcon(MaterialDesignA.ARROW_DOWN)).apply {
            styleClass += "git-log-fetch"
            tooltip = Tooltip("Fetch All Remotes")
            isFocusTraversable = false
            minWidth = USE_PREF_SIZE
            setOnAction { start(Fetch()) }
            disableProperty().bind(writer.running.or(noRemotes))
        }

    /** Opens the Push dialog (ADR 0049); not without a remote, but while an operation runs. */
    internal val pushButton =
        Button(null, FontIcon(MaterialDesignA.ARROW_UP)).apply {
            styleClass += "git-log-push"
            tooltip = Tooltip("Push… (${PUSH_KEYS.displayText})")
            isFocusTraversable = false
            minWidth = USE_PREF_SIZE
            setOnAction { showPush() }
            disableProperty().bind(noRemotes)
        }

    /** Cancels the fetch, commit or push going on; shown only while there is one. */
    internal val cancelButton =
        Button("Cancel").apply {
            styleClass += "git-log-cancel"
            isFocusTraversable = false
            minWidth = USE_PREF_SIZE
            visibleProperty().bind(writer.cancellable)
            managedProperty().bind(writer.cancellable)
            setOnAction { writer.cancel() }
        }

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

    /** The header bar; clicking it (but not its buttons or tabs) expands or collapses the log. */
    internal val header: HBox =
        HBox(
            chevron,
            titles,
            status.label,
            cancelButton,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            pushButton,
            fetchButton,
            refreshButton,
        ).apply {
            styleClass += "git-log-header"
            alignment = Pos.CENTER_LEFT
            setOnMouseClicked { event ->
                val onButton =
                    listOf(refreshButton, pushButton, fetchButton, cancelButton).any { it.contains(event.target) }
                if (event.button == MouseButton.PRIMARY && !onButton) {
                    expanded.set(!expanded.get())
                }
            }
        }

    private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

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

    private val copyRevisionNumber = MenuItem("Copy Revision Number").apply { setOnAction { copyRevisionNumbers() } }

    internal val goToItem = MenuItem("Go to Hash/Branch/Tag\u2026").apply { setOnAction { openGoTo() } }

    /** Go to Hash/Branch/Tag (ADR 0045), with the branches and tags read last. */
    internal val goToPopup =
        GoToPopup { filterSources.branchItems().drop(1) }.apply {
            go = ::goTo
            clearFilters = ::clearFiltersAndGo
            closed = ::goToClosed
        }

    /** Counts the requests of Go to, so that the result of an earlier one, or one after the popup closed, is dropped. */
    private var goToRequests = 0L

    /** The commit Go to looks for in the log and what it is called, while it does. */
    private var goToTarget: Pair<String, String>? = null

    internal val compareWithLocalItem =
        MenuItem("Compare with Local").apply {
            setOnAction {
                singleCommit()?.let { diffs.showWorkingTreeDiff(it.hash, it.hash.take(ChangedFilesPane.SHORT_HASH)) }
            }
        }

    internal val checkoutRevisionItem =
        MenuItem("Checkout Revision").apply {
            setOnAction {
                singleCommit()?.let { start(CheckoutRevision(it.hash, it.hash.take(ChangedFilesPane.SHORT_HASH))) }
            }
        }
    internal val newBranchItem =
        MenuItem("New Branch…").apply {
            setOnAction { singleCommit()?.let { newBranch(it.hash, it.hash.take(ChangedFilesPane.SHORT_HASH)) } }
        }
    internal val newTagItem = MenuItem("New Tag…").apply { setOnAction { singleCommit()?.let { newTag(it.hash) } } }
    internal val cherryPickItem =
        MenuItem(pickTitle(GitPick.CHERRY_PICK)).apply { setOnAction { pick(GitPick.CHERRY_PICK) } }
    internal val revertItem = MenuItem(pickTitle(GitPick.REVERT)).apply { setOnAction { pick(GitPick.REVERT) } }
    internal val resetItem = MenuItem("Reset Current Branch to Here…").apply { setOnAction { resetToCommit() } }

    /** Whether the refs read last have a `HEAD` commit, which a reset needs. */
    private val hasHead = SimpleBooleanProperty(false)

    /** The bar of what git is in the middle of, below the filter bar (ADR 0042); the table's menu reads its `busy`. */
    internal val pendingBar =
        PendingBar(writer.running) { scene?.window }.apply { start = { operation -> this@GitLogView.start(operation) } }

    /** The commits with their graph, newest first; several can be selected. */
    internal val table: TableView<GitLogRow> =
        object : TableView<GitLogRow>(pages.rows) {
            override fun createDefaultSkin() = PagingSkin(this, pages::shown)
        }.apply {
            styleClass += "git-log-table"
            selectionModel.selectionMode = SelectionMode.MULTIPLE
            columns.addAll(
                subjectColumn(),
                column("Author", 160.0) { it.commit.author }.apply { minWidth = 50.0 },
                column("Date", 140.0) { dateFormat.format(it.commit.authorTime) }.apply { minWidth = 50.0 },
            )
            columnResizePolicy = SubjectTakesTheRest
            placeholder =
                Label().apply {
                    textProperty().bind(
                        Bindings.createStringBinding(
                            {
                                val state = pages.state.get()
                                if (state == GitLogState.NO_MATCH && compareBar.emptySide.get().isNotEmpty()) {
                                    compareBar.emptySide.get()
                                } else {
                                    state.message
                                }
                            },
                            pages.state,
                            compareBar.emptySide,
                        ),
                    )
                }
            contextMenu =
                ContextMenu(
                    copyRevisionNumber,
                    compareWithLocalItem,
                    SeparatorMenuItem(),
                    checkoutRevisionItem,
                    newBranchItem,
                    newTagItem,
                    SeparatorMenuItem(),
                    cherryPickItem,
                    revertItem,
                    resetItem,
                    SeparatorMenuItem(),
                    goToItem,
                )
            copyRevisionNumber.disableProperty().bind(Bindings.isEmpty(selectionModel.selectedItems))
            // One commit, and no operation running.
            val notOne = Bindings.size(selectionModel.selectedItems).isNotEqualTo(1).or(writer.running)
            listOf(checkoutRevisionItem, newBranchItem, newTagItem).forEach { it.disableProperty().bind(notOne) }
            // Only reads: also while an operation runs.
            compareWithLocalItem.disableProperty().bind(Bindings.size(selectionModel.selectedItems).isNotEqualTo(1))
            // Not while git is in the middle of something: the bar says what.
            listOf(cherryPickItem, revertItem).forEach { it.disableProperty().bind(notOne.or(pendingBar.busy)) }
            resetItem.disableProperty().bind(notOne.or(hasHead.not()))
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                if (COPY.match(event)) {
                    copyRevisionNumbers()
                    event.consume()
                } else if (event.code == KeyCode.ESCAPE && pages.comparison != null) {
                    compareBar.stopComparing()
                    event.consume()
                }
            }
        }

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
            openCommit = { hash -> goToCommit(hash, hash.take(ChangedFilesPane.SHORT_HASH)) }
        }

    /** The files the selected commit changed. */
    internal val changesPane = ChangedFilesPane(details.files)

    /** The changed files above the details, half and half at first. */
    internal val side =
        SharedSplit(changesPane, detailsPane, 0.5, Orientation.VERTICAL).apply { styleClass += "git-log-side" }

    /** The branches, left of the commits (ADR 0040). */
    internal val branchesPane =
        BranchesPane().apply {
            navigate = ::navigateTo
            moved = ::branchMoved
            filter = { names ->
                // Filtering by branches ends a comparison, which would hide them.
                compareBar.stopComparing()
                filterBar.setBranches(names)
            }
            checkout = ::start
            newBranchFrom = { ref -> newBranch(ref.name, ref.shortName) }
            compare = compareBar::compareWith
            showWorkingTreeDiff = { ref -> diffs.showWorkingTreeDiff(ref.name, ref.shortName) }
            rename = ::renameBranch
            delete = ::deleteRefs
            fetch = { remote -> start(Fetch(remote)) }
            writing.bind(writer.running)
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
                    openGoTo()
                    event.consume()
                }
            }
        }

    init {
        styleClass += "git-log"
        stylesheets += GIT_LOG_CSS
        stylesheets += FILE_ICONS_CSS
        top = header
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
                    hasHead.set(false)
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
                    hasHead.set(
                        pages.refs
                            ?.refs
                            .orEmpty()
                            .any { it.kind == GitRefKind.HEAD },
                    )
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
        changesPane.showHistory = { row, file -> showHistoryUpTo(row.commit, file) }
        expanded.subscribe { isExpanded ->
            val focusInBody = center?.contains(scene?.focusOwner) == true
            chevron.iconCode = if (isExpanded) MaterialDesignC.CHEVRON_DOWN else MaterialDesignC.CHEVRON_RIGHT
            showContent()
            if (isExpanded) {
                focusContent()
            } else if (focusInBody) {
                returnFocus()
            }
        }
    }

    /**
     * Shows what the selected tab has below the header while [expanded]: the log, the Commit tab (ADR 0048) or a file
     * history (ADR 0046); only that one loads.
     */
    private fun showContent() {
        val isExpanded = expanded.get()
        val history = selectedHistoryView
        val commit = commitTab?.view?.takeIf { selected == AreaSelection.Commit }
        center = if (isExpanded) history ?: commit ?: body else null
        pages.visible = isExpanded && history == null && commit == null
        histories.values.forEach { it.view.history.visible = isExpanded && it.view === history }
        commitTab?.view?.reader?.visible = isExpanded && commit != null
    }

    /** Gives the focus to what is shown: the table of the log or a file history, the Commit tab's message. */
    private fun focusContent() {
        val content = center ?: return
        // The split pane adds the table to the scene with its skin, which CSS creates.
        content.applyCss()
        val commit = commitTab?.view?.takeIf { selected == AreaSelection.Commit }
        if (commit != null) commit.focusMessage() else (selectedHistoryView?.table ?: table).requestFocus()
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

    /**
     * Runs a read of the Push dialog on its thread after a delay in milliseconds (ADR 0049); tests drive the delay
     * themselves.
     */
    internal var schedulePush: (ScheduledExecutorService, Long, Runnable) -> Unit =
        { executor, delay, task -> executor.schedule(task, delay, TimeUnit.MILLISECONDS) }

    /** The open Push dialog (ADR 0049); `null` while there is none. */
    internal var pushDialog: PushDialog? = null
        private set

    /**
     * Opens the Push dialog of the repository (ADR 0049), or brings the open one to the front; also while an operation
     * runs, with Push disabled until it ends. Nothing without a repository.
     */
    fun showPush() {
        val root = pages.root ?: return
        pushDialog?.let { return it.show() }
        val pushExecutor =
            Executors.newSingleThreadScheduledExecutor { Thread(it, "sluide-git-push").apply { isDaemon = true } }
        val reader =
            PushReader(
                root,
                source,
                { delay, task -> schedulePush(pushExecutor, delay, task) },
                dispatch,
            ) { name -> pushNameProblem(name) == null }
        val dialog =
            PushDialog(scene?.window, reader, writer.running, ::pushNameProblem).apply {
                push = { operation -> start(operation) }
                showDiff = diffs::showCommitDiff
                openFile = { file -> diffs.localFile(file)?.let(onOpenFile) }
                exists = { file -> diffs.localFile(file)?.let(fileExists) == true }
            }
        pushDialog = dialog
        dialog.stage.addEventHandler(WindowEvent.WINDOW_HIDDEN) {
            if (pushDialog === dialog) pushDialog = null
            pushExecutor.shutdownNow()
        }
        dialog.show()
    }

    /** What is wrong with [name] as the branch to push to; `null` if nothing. */
    private fun pushNameProblem(name: String) = nameProblem(name, RefNameKind.BRANCH, emptySet(), writes::canPass)

    /**
     * The working tree changed (the window's git status, ADR 0013): the Commit tab reads its changes again, at once if
     * shown, else when it is shown next; nothing without one.
     */
    fun workingTreeChanged() {
        commitTab?.view?.reader?.reload()
    }

    /** A new Commit tab for the repository at [root], with a thread of its own, not read before it is shown. */
    private fun commitTab(root: Path): CommitTab {
        val commitExecutor =
            Executors.newSingleThreadExecutor { Thread(it, "sluide-git-commit").apply { isDaemon = true } }
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
            CommitView(reader, writer.running).apply {
                commit = { operation -> start(operation) }
                showDiff = { file, head -> diffs.showHeadDiff(root, commitExecutor, file, head) }
                openFile = { file -> diffs.localFile(file)?.let(onOpenFile) }
                exists = { file -> diffs.localFile(file)?.let(fileExists) == true }
                failedWithChanges = { if (selected == AreaSelection.Commit) status.tell(COMMIT_READ_FAILED) }
                tell = status::tell
                tree.selectionModel.selectedItemProperty().subscribe { _ -> status.selectionChanged() }
                // Go to is the log's: it goes there, as from a history (ADR 0046).
                addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                    if (GO_TO.match(event)) {
                        selectTab(AreaSelection.Log)
                        this@GitLogView.applyCss()
                        this@GitLogView.layout()
                        openGoTo()
                        event.consume()
                    }
                }
            }
        return CommitTab(view, commitExecutor)
    }

    /** Closes the Commit tab, keeping its draft; if it was selected, the Log is. */
    private fun closeCommit() {
        if (!commitOpen) return
        val focusInClosed = commitTab?.view?.contains(scene?.focusOwner) == true
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
    internal fun showHistoryUpTo(
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
            Executors.newSingleThreadExecutor { Thread(it, "sluide-git-history").apply { isDaemon = true } }
        val history = FileHistory(root, key, source::fileHistory, source::refs, pagesExecutor::execute, dispatch)
        val details =
            CommitDetails(source::details, { _, _ -> emptyList() }, executor::execute, dispatch, source::resolveCommits)
        val branches =
            CommitBranches(source::branchesContaining, branchesExecutor::execute, dispatch).apply {
                currentBranch = { history.refs?.currentBranch }
            }
        val view =
            FileHistoryView(history, details, branches).apply {
                showDiff = diffs::showDiff
                compareWithLocal = { row, file -> diffs.compareWithLocal(key, row, file) }
                showInLog = ::showInLog
                detailsPane.openCommit = ::showInLog
                selectionChanged = { status.selectionChanged() }
                // Go to is the log's: it goes there (ADR 0046). Find has nothing to filter in a history.
                addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                    if (GO_TO.match(event)) {
                        selectTab(AreaSelection.Log)
                        // Laid out now: the popup is placed by the table's bounds, which it may never have had yet.
                        this@GitLogView.applyCss()
                        this@GitLogView.layout()
                        openGoTo()
                        event.consume()
                    }
                }
            }
        history.failedWithRows =
            { if (selected == AreaSelection.History(key)) status.tell(FileHistoryState.FAILED.message(key.name)) }
        val tip = key.path + key.start?.let { " up to ${it.take(ChangedFilesPane.SHORT_HASH)}" }.orEmpty()
        val tab =
            AreaTab("History: ${key.name}", tip, closable = true).apply {
                onSelect = { selectTab(AreaSelection.History(key)) }
                onClose = { closeHistory(key) }
            }
        return HistoryTab(view, tab, pagesExecutor)
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
        val focusInClosed = closed.view.contains(scene?.focusOwner)
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
    private fun closeTabs() {
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
        goToCommit(hash, hash.take(ChangedFilesPane.SHORT_HASH))
    }

    /** Shows the log of the repository at [root]; `null` shows nothing. Loads once (or as soon as) [expanded]. */
    fun open(root: Path?) {
        fileDiffs.close()
        diffs.closeWorkingTreeWindows()
        pushDialog?.close()
        goToPopup.hide()
        // An operation going on in the previous repository ends unnoticed; a fetch is stopped (ADR 0043).
        writer.cancel()
        writer.forget()
        status.operation("")
        // Before the pages, which may start loading and have the pane say so.
        branchesPane.reset()
        commitBranches.clear()
        branchesRefs = null
        // Resets the filter and ends a comparison too, before the bar, which then tells no change.
        pages.open(root)
        closeTabs()
        filterBar.reset()
        compareBar.comparisonChanged()
        filterSources.reset()
    }

    /**
     * Selects the row of [ref]'s commit and scrolls to it, loading pages until it is there; if it is not, the header
     * tells why. Only while the branches pane still has [ref] selected; `null` stops looking.
     */
    private fun navigateTo(ref: GitRef?) {
        if (ref == null) {
            pages.stopLoadingUntil()
            return
        }
        status.clear()
        pages.loadUntil(
            ref.commit,
            { index -> if (branchesPane.selectedRef == ref) showRow(index) },
            { missing -> if (branchesPane.selectedRef == ref) status.tell(missingMessage(ref.shortName, missing)) },
        )
    }

    /**
     * Selects the row of the commit [hash] and scrolls to it, loading pages until it is there; if it is not, the header
     * tells why, calling it [name] (ADR 0045). Replaces a search for another commit, e.g. of a branch. The table takes
     * the focus from the details (a clicked link), whose texts the next commit may not have.
     */
    private fun goToCommit(
        hash: String,
        name: String,
    ) {
        status.clear()
        pages.loadUntil(
            hash,
            { index ->
                showRow(index)
                if (detailsPane.contains(scene?.focusOwner)) table.requestFocus()
            },
            { missing -> status.tell(missingMessage(name, missing)) },
        )
    }

    /** Opens Go to Hash/Branch/Tag at the top of the table (ADR 0045); not without a repository. */
    private fun openGoTo() {
        if (pages.root == null) return
        goToPopup.show(table)
    }

    /**
     * Resolves [revision] (a full refname, or what was typed) in the background and goes to its commit (ADR 0045); the
     * popup says why not, calling it [name].
     */
    private fun goTo(
        revision: String,
        name: String,
    ) {
        val root = pages.root ?: return
        val request = ++goToRequests
        goToTarget = null
        executor.execute {
            val resolution = source.resolveCommits(root, listOf(revision))?.singleOrNull()
            dispatch {
                if (request != goToRequests || pages.root != root || !goToPopup.isShowing) return@dispatch
                when (resolution) {
                    is GitRevisionResolution.Commit -> seekGoTo(request, resolution.hash, name)
                    GitRevisionResolution.Ambiguous -> goToPopup.say("'$name' is ambiguous.")
                    GitRevisionResolution.Missing, null -> goToPopup.say("Unknown revision '$name'.")
                }
            }
        }
    }

    /**
     * Selects the row of the commit [hash] Go to resolved, loading pages until it is there: the popup closes and the
     * table takes the focus; if it is not there, the popup says why, offering to clear the filters that hide it.
     */
    private fun seekGoTo(
        request: Long,
        hash: String,
        name: String,
    ) {
        goToTarget = hash to name
        pages.loadUntil(
            hash,
            { index ->
                if (request == goToRequests) {
                    goToTarget = null
                    goToPopup.hide()
                    showRow(index)
                    table.requestFocus()
                }
            },
            { missing ->
                if (request == goToRequests) {
                    val hidden =
                        missing == CommitMissing.NOT_IN_LOG &&
                            pages.comparison == null &&
                            !pages.effectiveFilter.isDefault
                    goToPopup.say(missingMessage(name, missing), offerClearFilters = hidden)
                }
            },
        )
    }

    /** Clears the filters that hide the commit Go to looked for, and goes on to it. */
    private fun clearFiltersAndGo() {
        val (hash, name) = goToTarget ?: return
        filterBar.clearFilters()
        goToPopup.say(null)
        seekGoTo(goToRequests, hash, name)
    }

    /** Go to closed: what it still waits for is dropped, the log stops loading pages for it. */
    private fun goToClosed() {
        goToRequests++
        val target = goToTarget ?: return
        goToTarget = null
        if (pages.loadingUntil == target.first) pages.stopLoadingUntil()
    }

    /** Why the commit called [name] is not in the table, as [GitLogPages.loadUntil] found it [missing]. */
    private fun missingMessage(
        name: String,
        missing: CommitMissing,
    ): String =
        when {
            missing == CommitMissing.UNREADABLE -> {
                GitLogState.FAILED.message
            }

            missing == CommitMissing.BEYOND_LIMIT -> {
                "$name is more than ${"%,d".format(Locale.ENGLISH, GitLogPages.SEEK_LIMIT)} commits down."
            }

            pages.comparison != null -> {
                "$name is not in this comparison."
            }

            pages.effectiveFilter.isDefault -> {
                "$name is not in the log."
            }

            else -> {
                "$name is hidden by the filters."
            }
        }

    /**
     * Follows a selected branch that a refresh moved from [before] to [after], but only while the user is still on it:
     * the pane is shown and the table still has exactly its old commit selected, or its commit is still looked for.
     */
    private fun branchMoved(
        before: GitRef,
        after: GitRef,
    ) {
        val looking = pages.loadingUntil == before.commit
        val onIt = table.selectionModel.selectedItems.map { it.commit.hash } == listOf(before.commit)
        if (body.firstShown && (looking || onIt)) {
            navigateTo(after)
        } else if (looking) {
            pages.stopLoadingUntil()
        }
    }

    /** Selects only the row at [index] and scrolls it into the middle of the table, unless it is in view already. */
    private fun showRow(index: Int) {
        table.selectionModel.clearAndSelect(index)
        val flow = table.lookup(".virtual-flow") as? VirtualFlow<*>
        val first = flow?.firstVisibleCell?.index ?: -1
        val last = flow?.lastVisibleCell?.index ?: -1
        // The first and last rows may be cut off.
        if (index in first + 1..<last) return
        table.scrollTo(maxOf(0, index - maxOf(0, last - first) / 2))
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
        histories.values.forEach { it.view.history.refresh() }
        commitTab?.view?.reader?.reload()
    }

    /**
     * Cherry-picks or reverts ([kind]) the selected commit; a merge commit relative to its first parent, once
     * confirmed.
     */
    private fun pick(kind: GitPick) {
        val commit = singleCommit() ?: return
        if (writer.running.get()) return
        if (commit.parents.size <= 1) return start(PickCommit(kind, commit.hash))
        val confirmation =
            RefConfirmation(
                pickTitle(kind),
                mergeQuestion(kind, commit.hash),
                pickButton(kind),
                PickCommit(kind, commit.hash, firstParent = true),
            )
        if (refConfirmation(scene?.window, confirmation).showAndWait().orElse(ButtonType.CANCEL) ==
            confirmation.button
        ) {
            start(confirmation.confirmed)
        }
    }

    /** Asks how to reset the current branch (or a detached `HEAD`) to the selected commit, then resets it. */
    private fun resetToCommit() {
        val commit = singleCommit() ?: return
        if (writer.running.get()) return
        val refs = pages.refs ?: return
        val head = refs.refs.firstOrNull { it.kind == GitRefKind.HEAD }?.commit ?: return
        val branch = refs.currentBranch?.removePrefix("refs/heads/")
        val dialog = ResetDialog(scene?.window, resetTitle(branch), resetDescription(branch, head, commit.hash))
        val mode = dialog.showAndWait().orElse(null) ?: return
        start(ResetBranch(commit.hash, mode, branch))
    }

    /** The commit of the one selected row; `null` for none or several. */
    private fun singleCommit() =
        table.selectionModel.selectedItems
            .singleOrNull()
            ?.commit

    /**
     * Runs [operation] (ADR 0041) unless one runs already; one that changes files asks [beforeFileChange] first, unless
     * [askFirst] is `false` (it was asked already). The header says what
     * it does, then what it did, and the log is refreshed; a failure is shown in an error dialog with git's message.
     */
    private fun start(
        operation: RefOperation,
        askFirst: Boolean = true,
    ) {
        val root = pages.root ?: return
        if (writer.running.get()) return
        val action = operation.fileChange
        if (askFirst && action != null && !beforeFileChange(action)) return
        status.clear()
        if (writer.run(root, operation, status::operation) { outcome -> finished(operation, outcome) }) {
            status.operation(operation.progress)
        }
    }

    private fun finished(
        operation: RefOperation,
        outcome: RefOutcome,
    ) {
        status.operation(outcome.done.orEmpty())
        outcome.confirmation?.let { confirmation ->
            // Nothing done yet; the unsaved editors were asked about already.
            if (refConfirmation(scene?.window, confirmation).showAndWait().orElse(ButtonType.CANCEL) ==
                confirmation.button
            ) {
                start(confirmation.confirmed, askFirst = false)
            }
            return
        }
        // A commit that was made (not one cancelled) ends the draft (ADR 0048); a failed one keeps it.
        // Commit and Push opens the Push dialog after a commit that was made (ADR 0049).
        if (operation is CommitChanges && outcome.result.success && outcome.result.changed) {
            commitTab?.view?.committed(operation)
            if (operation.push) showPush()
        }
        // Also after a failure, which may have done a part. New and renamed branches alone are not noticed by the
        // watch of .git (ADR 0035).
        afterWrite()
        if (!outcome.result.success) {
            // Done all the same (a failing post-checkout hook): the header says what was done.
            val header = outcome.done?.takeIf { outcome.result.changed } ?: outcome.failure ?: operation.failure
            gitErrorDialog(scene?.window, operation.errorTitle, header, outcome.result.message).showAndWait()
        }
        outcome.stopped?.let { stop ->
            // Resolve (or closing the dialog) leaves it for the user, with the bar above the table.
            if (pickStoppedDialog(scene?.window, stop).showAndWait().orElse(null) == abortButton(stop.kind)) {
                start(AbortPick(stop.kind, stop.commit))
            }
        }
        if (outcome.refused.isNotEmpty()) {
            val confirmation =
                forceDeleteConfirmation(scene?.window, forceDeleteQuestion(outcome.refused), outcome.refusedMessage)
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == FORCE_DELETE_BUTTON) {
                start(DeleteRefs(outcome.refused, emptyList(), force = true))
            }
        }
    }

    /** The short names of the refs of [kind] the log read last. */
    private fun refNames(kind: GitRefKind): Set<String> =
        pages.refs
            ?.refs
            .orEmpty()
            .filter { it.kind == kind }
            .mapTo(HashSet()) { it.shortName }

    /**
     * Asks for a new branch at [startPoint] (a hash or full refname, shown as [startLabel]) and creates it; the current
     * branch is never overwritten, another one only once confirmed if it has commits that are not on the start point.
     */
    private fun newBranch(
        startPoint: String,
        startLabel: String,
    ) {
        val existing = refNames(GitRefKind.LOCAL_BRANCH)
        val current = pages.refs?.currentBranch?.removePrefix("refs/heads/")
        val tips =
            pages.refs
                ?.refs
                .orEmpty()
                .filter { it.kind == GitRefKind.LOCAL_BRANCH }
                .associate { it.shortName to it.commit }
        val dialog =
            NewBranchDialog(
                scene?.window,
                { name, overwrite ->
                    nameProblem(name, RefNameKind.BRANCH, existing, writes::canPass, overwrite, current = current)
                },
                { name, overwrite -> overwriteNote(name, overwrite, tips) },
            )
        val branch = dialog.showAndWait().orElse(null) ?: return
        start(CreateBranch(branch.name, startPoint, branch.checkout, branch.overwrite, startLabel))
    }

    /** Asks for a new tag at [commit] and creates it. */
    private fun newTag(commit: String) {
        val existing = refNames(GitRefKind.TAG)
        val dialog =
            NewTagDialog(scene?.window) { name -> nameProblem(name, RefNameKind.TAG, existing, writes::canPass) }
        val tag = dialog.showAndWait().orElse(null) ?: return
        start(CreateTag(tag.name, commit, tag.message))
    }

    /** Asks for a new name of the local branch [ref] and renames it. */
    private fun renameBranch(ref: GitRef) {
        val old = ref.shortName
        val existing = refNames(GitRefKind.LOCAL_BRANCH)
        val dialog =
            RenameBranchDialog(scene?.window, old) { name ->
                nameProblem(name, RefNameKind.BRANCH, existing, writes::canPass, unchanged = old)
            }
        val new = dialog.showAndWait().orElse(null) ?: return
        start(RenameBranch(old, new))
    }

    /** Asks whether to delete the local branches and tags [refs] and deletes them. */
    private fun deleteRefs(refs: List<GitRef>) {
        if (writer.running.get()) return
        val branches = refs.filter { it.kind == GitRefKind.LOCAL_BRANCH }.map { it.shortName }
        val tags = refs.filter { it.kind == GitRefKind.TAG }.map { it.shortName }
        val names = branches.map { "branch $it" } + tags.map { "tag $it" }
        val confirmation = deleteRefsConfirmation(scene?.window, deleteQuestion(branches, tags), names)
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != DELETE_BUTTON) return
        start(DeleteRefs(branches, tags))
    }

    /** Copies the full hashes of the selected commits, one per line in table order. */
    private fun copyRevisionNumbers() {
        val hashes =
            table.selectionModel.selectedIndices
                .sorted()
                .map { pages.rows[it].commit.hash }
        if (hashes.isEmpty()) return
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(hashes.joinToString("\n")) })
    }

    override fun close() {
        histories.values.forEach { it.close() }
        commitTab?.close()
        diffs.closeWorkingTreeWindows()
        pushDialog?.close()
        compareExecutor.shutdownNow()
        // A fetch is stopped: harmless, it updates refs one by one and no files (ADR 0043).
        writer.cancel()
        writer.forget()
        // Any other running write is not interrupted: stopped halfway, a checkout would leave a half-switched working
        // tree (ADR 0041). Git finishes on its own; its outcome is dropped.
        writeExecutor.shutdown()
        pages.close()
        details.close()
        fileDiffs.close()
        commitBranches.close()
        branchesExecutor.shutdownNow()
        detailsPane.close()
        changesPane.close()
        executor.shutdownNow()
    }

    private fun Node.contains(target: EventTarget?): Boolean {
        var node = target as? Node
        while (node != null) {
            if (node === this) return true
            node = node.parent
        }
        return false
    }
}

internal val COPY = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)

private val FIND = KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN)

private val GO_TO = KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN)

/** What the header says when the Commit tab's changes could not be read again while it shows those read before. */
private const val COMMIT_READ_FAILED = "The changes could not be read."
