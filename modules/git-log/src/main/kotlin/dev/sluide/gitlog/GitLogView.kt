package dev.sluide.gitlog

import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.git.GitBranches
import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommits
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitLog
import dev.sluide.git.GitPendingOperation
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPick
import dev.sluide.git.GitPush
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitRemotes
import dev.sluide.git.GitRevisionResolution
import dev.sluide.git.GitTreePath
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.property.BooleanProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.ListChangeListener
import javafx.event.EventTarget
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.ConstrainedColumnResizeBase
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SelectionMode
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.SplitPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.control.skin.TableViewSkin
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
import javafx.stage.Stage
import javafx.stage.WindowEvent
import javafx.util.Callback
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignR
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.InvalidPathException
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

    /** The open working-tree windows by the revision they compare (ADR 0044). */
    internal val workingTreeWindows = LinkedHashMap<String, WorkingTreeWindow>()

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
     * Shows how a file differs between a commit and the working tree, e.g. side by side in an editor tab with the live
     * file on the right; `false` if it cannot be shown as text. Set by the window (ADR 0044).
     */
    var onShowWorkingTreeDiff: (WorkingTreeFileDiff) -> Boolean = { true }

    /** Whether there is a file at a path (not following a link); tests replace it, their repository is not on disk. */
    @Volatile
    internal var fileExists: (Path) -> Boolean = { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }

    /** A message in the header, e.g. that a diff cannot be shown, until the next selection or load. */
    private val status = SimpleStringProperty("")

    /**
     * What the operation that changes the repository is doing ("Checking out main…") or did ("Checked out main."),
     * shown while there is no other [status]; until the next operation or [open].
     */
    private val operationStatus = SimpleStringProperty("")

    /**
     * What the table says while comparing (ADR 0044) and its side has no commits, without other filters set; empty
     * otherwise.
     */
    private val emptySide = SimpleStringProperty("")

    /**
     * What the header says once the log loaded again, e.g. why a comparison ended: replacing the rows changes the
     * selection, which clears the header.
     */
    private var statusAfterLoad: String? = null

    /** Counts the changes of the selected commits and files, so that a message about an earlier one is not shown. */
    private var selections = 0L

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

    /** Whether the Log tab is selected (or there are no tabs), so that the header tells the log's failures. */
    private val logShown = SimpleBooleanProperty(true)

    /** The header bar; clicking it (but not its buttons or tabs) expands or collapses the log. */
    internal val header: HBox =
        HBox(
            chevron,
            titles,
            // A failure while rows are shown (with no rows the table's placeholder says it), else the status, else what
            // an operation does or did.
            Label().apply {
                styleClass += "git-log-status"
                textProperty().bind(
                    Bindings.createStringBinding(
                        {
                            val state = pages.state.get()
                            val logFailed = state == GitLogState.FAILED && pages.rows.isNotEmpty()
                            when {
                                logShown.get() && logFailed -> state.message
                                status.get().isNotEmpty() -> status.get()
                                else -> operationStatus.get()
                            }
                        },
                        pages.state,
                        pages.rows,
                        status,
                        operationStatus,
                        logShown,
                    ),
                )
                minWidth = 0.0
            },
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
        GoToPopup { branchItems().drop(1) }.apply {
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
                singleCommit()?.let { showWorkingTreeDiff(it.hash, it.hash.take(ChangedFilesPane.SHORT_HASH)) }
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

    /** Whether git is in the middle of something, as read last (the bar shows it). */
    private val busy = SimpleBooleanProperty(false)

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
                                if (state == GitLogState.NO_MATCH && emptySide.get().isNotEmpty()) {
                                    emptySide.get()
                                } else {
                                    state.message
                                }
                            },
                            pages.state,
                            emptySide,
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
            listOf(cherryPickItem, revertItem).forEach { it.disableProperty().bind(notOne.or(busy)) }
            resetItem.disableProperty().bind(notOne.or(hasHead.not()))
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                if (COPY.match(event)) {
                    copyRevisionNumbers()
                    event.consume()
                } else if (event.code == KeyCode.ESCAPE && pages.comparison != null) {
                    stopComparing()
                    event.consume()
                }
            }
        }

    /** The author names of all rows loaded since the root was opened, for the user filter. */
    private val knownAuthors = HashSet<String>()

    /** The root whose user name is read (or being read); `null` if it is not asked for yet. */
    private var userNameRoot: Path? = null
    private var userName: String? = null

    /** The root whose tree paths are read (or being read); `null` if they are not asked for yet. */
    private var pathsRoot: Path? = null
    private var paths: List<GitTreePath> = emptyList()

    /** Whether the log was refreshed since the paths were read, so that they are read again. */
    private var pathsStale = false

    /** The filters above the table (ADR 0039). */
    internal val filterBar =
        FilterBar(::branchItems, ::userItems, ::pathItems, ::readUserName, ::readPaths, ::freePath).apply {
            onChange = { filter -> pages.filter = filter }
            onDown = { table.requestFocus() }
        }

    /** The single cherry-pick or revert in progress the bar continues or aborts; `null` for none or anything else. */
    private var pending: GitPendingPick? = null

    /** Shrinks with the log, as the buttons do not; its tooltip has the whole text. */
    private val pendingLabel =
        Label().apply {
            styleClass += "git-log-pending-text"
            minWidth = 0.0
            tooltip = Tooltip()
        }

    internal val continueButton =
        Button("Continue").apply {
            styleClass += "git-log-pending-continue"
            minWidth = USE_PREF_SIZE
            setOnAction { pending?.let { start(ContinuePick(it.kind, it.commit)) } }
            disableProperty().bind(writer.running)
        }

    internal val abortButton =
        Button("Abort").apply {
            styleClass += "git-log-pending-abort"
            minWidth = USE_PREF_SIZE
            setOnAction { pending?.let(::abortPending) }
            disableProperty().bind(writer.running)
        }

    /**
     * The bar of what git is in the middle of (ADR 0042), below the filter bar: a cherry-pick or revert to continue or
     * abort, or a merge, rebase or sequence left to the terminal; hidden while there is nothing.
     */
    internal val pendingBar =
        HBox(pendingLabel, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, continueButton, abortButton).apply {
            styleClass += "git-log-pending"
            alignment = Pos.CENTER_LEFT
            isVisible = false
            isManaged = false
        }

    /** Says what is compared with what (ADR 0044); shrinks first in a narrow log, its tooltip has the whole text. */
    internal val compareLabel =
        Label().apply {
            styleClass += "git-log-compare-text"
            minWidth = 0.0
            tooltip = Tooltip()
        }

    private val compareSides = ToggleGroup()

    /** Lists the commits of the compared ref that are not in `HEAD`. */
    internal val refOnlyToggle = compareSide(ComparisonSide.REF_ONLY)

    /** Lists the commits of `HEAD` that are not in the compared ref. */
    internal val headOnlyToggle = compareSide(ComparisonSide.HEAD_ONLY)

    internal val stopCompareButton =
        Button(null, FontIcon(MaterialDesignC.CLOSE)).apply {
            styleClass += "git-log-compare-close"
            tooltip = Tooltip("Stop Comparing")
            minWidth = USE_PREF_SIZE
            setOnAction { stopComparing() }
        }

    /**
     * The bar of a comparison of a ref with `HEAD` (ADR 0044), below the filter bar: what is compared, the two sides to
     * list with their counts, and a button that stops comparing; hidden while not comparing.
     */
    internal val compareBar =
        HBox(
            compareLabel,
            refOnlyToggle,
            headOnlyToggle,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            stopCompareButton,
        ).apply {
            styleClass += "git-log-compare"
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            isVisible = false
            isManaged = false
        }

    /** Set while the side toggles are set to the comparison's side, which is no choice of the user. */
    private var showingSide = false

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
                stopComparing()
                filterBar.setBranches(names)
            }
            checkout = ::start
            newBranchFrom = { ref -> newBranch(ref.name, ref.shortName) }
            compare = ::compareWith
            showWorkingTreeDiff = { ref -> showWorkingTreeDiff(ref.name, ref.shortName) }
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
                selections++
                status.set("")
                details.selectionChanged()
            },
        )
        changesPane.tree.selectionModel
            .selectedItemProperty()
            .subscribe { _ ->
                selections++
                if (!changesPane.tree.rebuilding) status.set("")
            }
        pages.state.subscribe { state -> if (state == GitLogState.LOADING) status.set("") }
        // The refs are read with every first page, before its state is set.
        pages.state.subscribe { state ->
            when (state) {
                GitLogState.IDLE -> {
                    statusAfterLoad = null
                    showPending(null)
                    hasHead.set(false)
                    noRemotes.set(false)
                    branchesPane.remotes = emptyList()
                }

                GitLogState.LOADING -> {
                    branchesPane.loading()
                }

                else -> {
                    statusAfterLoad?.let(status::set)
                    statusAfterLoad = null
                    if (pages.refs != branchesRefs) {
                        branchesRefs = pages.refs
                        commitBranches.refsChanged()
                    }
                    branchesPane.show(pages.refs)
                    updateCompareBar()
                    showPending(pages.pending)
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
        pages.rows.addListener(
            ListChangeListener { change ->
                while (change.next()) {
                    if (change.wasAdded()) change.addedSubList.mapTo(knownAuthors) { it.commit.author }
                }
            },
        )
        pages.comparisonEnded = { comparison, end ->
            comparisonChanged()
            val name = comparison.ref.shortName
            statusAfterLoad =
                when (end) {
                    ComparisonEnd.GONE -> "$name no longer exists."
                    ComparisonEnd.CURRENT -> "$name is the current branch now."
                }
        }
        compareSides.selectedToggleProperty().subscribe { old, new ->
            when {
                showingSide -> {}

                // Exactly one side is listed: a click on the selected one keeps it.
                new == null -> {
                    old?.isSelected = true
                }

                else -> {
                    pages.comparison = pages.comparison?.copy(side = new.userData as ComparisonSide)
                    updateCompareBar()
                }
            }
        }
        changesPane.showDiff = ::showDiff
        changesPane.openFile = { file -> localFile(file)?.let(onOpenFile) }
        changesPane.exists = { file -> localFile(file)?.let(Files::exists) == true }
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
                showDiff = ::showCommitDiff
                openFile = { file -> localFile(file)?.let(onOpenFile) }
                exists = { file -> localFile(file)?.let(fileExists) == true }
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
                showDiff = { file, head -> showHeadDiff(root, commitExecutor, file, head) }
                openFile = { file -> localFile(file)?.let(onOpenFile) }
                exists = { file -> localFile(file)?.let(fileExists) == true }
                failedWithChanges = { if (selected == AreaSelection.Commit) status.set(COMMIT_READ_FAILED) }
                tell = status::set
                tree.selectionModel.selectedItemProperty().subscribe { _ ->
                    selections++
                    status.set("")
                }
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

    /**
     * Reads how [file] differs between `HEAD` ([head]) and the working tree on the Commit tab's thread ([background])
     * and hands it to [onShowWorkingTreeDiff], labelled "HEAD" (ADR 0048); an added or untracked file is all new. A
     * binary, too large or unchanged file, a submodule or a failure is told in the header instead, unless the selection
     * changed meanwhile. Without a `HEAD` commit (no commit yet) there is nothing to compare with: the file itself is
     * opened with [onOpenFile], if it is in the working tree.
     */
    private fun showHeadDiff(
        root: Path,
        background: ExecutorService,
        file: GitChangedFile,
        head: String?,
    ) {
        if (head == null) {
            if (inWorkingTree(root, file.path)) localFile(file)?.let(onOpenFile)
            return
        }
        val requestedAt = selections
        status.set("")
        background.execute {
            val diff = source.workingTreeFileDiff(root, head, file)
            dispatch {
                if (pages.root != root) return@dispatch
                val message =
                    workingTreeMessage(file.path.substringAfterLast('/'), file, diff) { text ->
                        onShowWorkingTreeDiff(
                            WorkingTreeFileDiff(
                                root,
                                file.path,
                                file.oldPath,
                                file.change,
                                head,
                                "HEAD",
                                text.original,
                                text.current,
                                text.hunks,
                            ),
                        )
                    }
                if (message != null && selections == requestedAt) status.set(message)
            }
        }
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
                showDiff = ::showDiff
                compareWithLocal = { row, file -> compareWithLocal(key, row, file) }
                showInLog = ::showInLog
                detailsPane.openCommit = ::showInLog
                selectionChanged = {
                    selections++
                    status.set("")
                }
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
            { if (selected == AreaSelection.History(key)) status.set(FileHistoryState.FAILED.message(key.name)) }
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
        status.set("")
        logShown.set(selected == AreaSelection.Log)
        if (selected == AreaSelection.Commit) {
            val reader = commitTab?.view?.reader ?: return
            if (reader.state.get() == CommitLoadState.FAILED && reader.snapshot != null) status.set(COMMIT_READ_FAILED)
            return
        }
        val history = selectedHistoryView?.history ?: return
        if (history.state.get() == FileHistoryState.FAILED && history.rows.isNotEmpty()) {
            status.set(FileHistoryState.FAILED.message(history.key.name))
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
        logShown.set(true)
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

    /**
     * Reads how the file of the history [key] at the commit of [row] ([file]: its change there) differs from the file
     * in the working tree (at the history's path) in the background, and hands it to [onShowWorkingTreeDiff]; a binary,
     * too large or unchanged file, a submodule or a failure is told in the header instead, unless the selection changed
     * meanwhile (ADR 0046).
     */
    private fun compareWithLocal(
        key: FileHistoryKey,
        row: GitLogRow,
        file: GitChangedFile,
    ) {
        val root = pages.root ?: return
        val commit = row.commit.hash
        val requestedAt = selections
        status.set("")
        compareExecutor.execute {
            val compared =
                when {
                    // Not at the commit: all of the working-tree file is new.
                    file.change == GitChange.DELETED -> GitChangedFile(GitChange.ADDED, key.path)

                    !inWorkingTree(root, key.path) -> GitChangedFile(GitChange.DELETED, file.path)

                    file.path != key.path -> GitChangedFile(GitChange.RENAMED, key.path, file.path)

                    else -> GitChangedFile(GitChange.MODIFIED, key.path)
                }
            val diff = source.workingTreeFileDiff(root, commit, compared)
            dispatch {
                if (pages.root != root) return@dispatch
                val message =
                    workingTreeMessage(key.name, compared, diff) { text ->
                        onShowWorkingTreeDiff(
                            WorkingTreeFileDiff(
                                root,
                                compared.path,
                                compared.oldPath,
                                compared.change,
                                commit,
                                commit.take(ChangedFilesPane.SHORT_HASH),
                                text.original,
                                text.current,
                                text.hunks,
                            ),
                        )
                    }
                if (message != null && selections == requestedAt) status.set(message)
            }
        }
    }

    /**
     * What keeps the working-tree [diff] of the file [name] ([compared]) from being shown; `null` once [show] showed it.
     */
    private fun workingTreeMessage(
        name: String,
        compared: GitChangedFile,
        diff: GitFileDiff?,
        show: (GitFileDiff.Text) -> Boolean,
    ): String? =
        when (diff) {
            null -> {
                "The diff could not be read."
            }

            GitFileDiff.Binary -> {
                "$name is a binary file."
            }

            GitFileDiff.TooLarge -> {
                "$name is too large to compare."
            }

            is GitFileDiff.Submodule -> {
                "$name is a submodule."
            }

            is GitFileDiff.Text -> {
                val same = diff.original == diff.current && compared.change.changesContent
                when {
                    same -> "$name is the same in the working tree."
                    show(diff) -> null
                    else -> "$name cannot be shown as text."
                }
            }
        }

    /** Whether the working tree has a file at [path]; `true` if the JVM cannot name it (git reads it then). */
    private fun inWorkingTree(
        root: Path,
        path: String,
    ): Boolean =
        try {
            fileExists(root.resolve(path))
        } catch (_: InvalidPathException) {
            true
        }

    /** Shows the log of the repository at [root]; `null` shows nothing. Loads once (or as soon as) [expanded]. */
    fun open(root: Path?) {
        fileDiffs.close()
        closeWorkingTreeWindows()
        pushDialog?.close()
        goToPopup.hide()
        // An operation going on in the previous repository ends unnoticed; a fetch is stopped (ADR 0043).
        writer.cancel()
        writer.forget()
        operationStatus.set("")
        // Before the pages, which may start loading and have the pane say so.
        branchesPane.reset()
        commitBranches.clear()
        branchesRefs = null
        // Resets the filter and ends a comparison too, before the bar, which then tells no change.
        pages.open(root)
        closeTabs()
        filterBar.reset()
        comparisonChanged()
        knownAuthors.clear()
        userNameRoot = null
        userName = null
        pathsRoot = null
        paths = emptyList()
    }

    /** `HEAD`, the local branches, remote branches and tags of the refs read last, each kind by name. */
    private fun branchItems(): List<FilterItem> {
        val refs = pages.refs?.refs.orEmpty()
        val head = FilterItem("HEAD", "HEAD") { refIcon(GitRefKind.HEAD) }
        return listOf(head) +
            listOf(GitRefKind.LOCAL_BRANCH, GitRefKind.REMOTE_BRANCH, GitRefKind.TAG).flatMap { kind ->
                refs
                    .filter { it.kind == kind }
                    .sortedBy { it.shortName }
                    .map { ref -> FilterItem(ref.name, ref.shortName) { refIcon(kind) } }
            }
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
        status.set("")
        pages.loadUntil(
            ref.commit,
            { index -> if (branchesPane.selectedRef == ref) showRow(index) },
            { missing -> if (branchesPane.selectedRef == ref) status.set(missingMessage(ref.shortName, missing)) },
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
        status.set("")
        pages.loadUntil(
            hash,
            { index ->
                showRow(index)
                if (detailsPane.contains(scene?.focusOwner)) table.requestFocus()
            },
            { missing -> status.set(missingMessage(name, missing)) },
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

    /**
     * Lists the commits of [ref] that are not in `HEAD` (ADR 0044), with the bar above the table; the filters stay, the
     * branch filter set aside until the comparison ends. Not without a `HEAD` commit.
     */
    private fun compareWith(ref: GitRef) {
        if (pages.refs
                ?.refs
                .orEmpty()
                .none { it.kind == GitRefKind.HEAD }
        ) {
            return
        }
        status.set("")
        pages.comparison = LogComparison(ref)
        comparisonChanged()
    }

    /** Ends the comparison: the log shows what the filters say again. */
    private fun stopComparing() {
        if (pages.comparison == null) return
        pages.comparison = null
        comparisonChanged()
    }

    /** Shows the bar of the comparison, or hides it; the branch filter is disabled while comparing. */
    private fun comparisonChanged() {
        val comparison = pages.comparison
        compareBar.isVisible = comparison != null
        compareBar.isManaged = comparison != null
        filterBar.branch.isDisable = comparison != null
        showingSide = true
        try {
            compareSides.selectToggle(
                when (comparison?.side) {
                    ComparisonSide.REF_ONLY -> refOnlyToggle
                    ComparisonSide.HEAD_ONLY -> headOnlyToggle
                    null -> null
                },
            )
        } finally {
            showingSide = false
        }
        updateCompareBar()
    }

    /** The texts of the comparison's bar and empty table, with `HEAD`'s branch and the counts read last. */
    private fun updateCompareBar() {
        val comparison = pages.comparison
        if (comparison == null) {
            emptySide.set("")
            return
        }
        val name = comparison.ref.shortName
        val current = pages.refs?.currentBranch?.removePrefix("refs/heads/") ?: "HEAD"
        val counts = pages.counts
        compareLabel.text = "Comparing $name with $current"
        compareLabel.tooltip.text = compareLabel.text
        refOnlyToggle.text = "In $name, not in $current" + counts?.let { " (${it.refOnly})" }.orEmpty()
        headOnlyToggle.text = "In $current, not in $name" + counts?.let { " (${it.headOnly})" }.orEmpty()
        // Other filters may be why nothing is listed.
        val unfiltered = pages.filter.copy(revisions = emptyList()).isDefault
        emptySide.set(
            when {
                !unfiltered -> ""
                comparison.side == ComparisonSide.REF_ONLY -> "No commits in $name that are not in $current."
                else -> "No commits in $current that are not in $name."
            },
        )
    }

    private fun compareSide(side: ComparisonSide) =
        ToggleButton().apply {
            styleClass += "git-log-compare-side"
            toggleGroup = compareSides
            userData = side
            minWidth = USE_PREF_SIZE
        }

    /**
     * Opens a window with the files that differ between the commit [revision] names (a full refname, `HEAD` or a full
     * hash) and the working tree, titled with [label] (ADR 0044); one that is open for it already comes to the front. The
     * window is not owned by the IDE window, which comes to the front when a diff opens from it.
     */
    private fun showWorkingTreeDiff(
        revision: String,
        label: String,
    ) {
        val root = pages.root ?: return
        workingTreeWindows[revision]?.let { return it.show() }
        val comparison =
            WorkingTreeComparison(
                WorkingTreeTarget(root, revision, label),
                source::workingTreeChanges,
                source::workingTreeFileDiff,
                compareExecutor::execute,
                dispatch,
            )
        val window =
            WorkingTreeWindow(
                scene?.window,
                comparison,
                { diff -> onShowWorkingTreeDiff(diff).also { shown -> if (shown) bringToFront() } },
                { file -> onOpenFile(file) },
                ::localFile,
            )
        workingTreeWindows[revision] = window
        window.stage.addEventHandler(WindowEvent.WINDOW_HIDDEN) {
            if (workingTreeWindows[revision] === window) workingTreeWindows.remove(revision)
        }
        window.show()
    }

    /** Brings the IDE window in front of the working-tree windows, e.g. for a diff tab opened from one. */
    private fun bringToFront() {
        val window = scene?.window ?: return
        (window as? Stage)?.toFront()
        window.requestFocus()
    }

    /** Closes the working-tree windows, e.g. for another folder or when the IDE window closes. */
    private fun closeWorkingTreeWindows() {
        workingTreeWindows.values.toList().forEach(WorkingTreeWindow::close)
        workingTreeWindows.clear()
    }

    /** "me" if the repository has a user name, then the authors of the loaded rows by name. */
    private fun userItems(): List<FilterItem> {
        val me = userName?.let { FilterItem(it, "me ($it)", "me") }
        val authors =
            knownAuthors
                .filter { it != userName }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
                .map { FilterItem(it, it) }
        return listOfNotNull(me) + authors
    }

    /** The folders and files of `HEAD`'s tree, once read. */
    private fun pathItems(): List<FilterItem> = paths.map { pathItem(it.path, it.folder) }

    private fun freePath(text: String): FilterItem? =
        normalizeFilterPath(text)?.let { path -> pathItem(path, paths.any { it.folder && it.path == path }) }

    private fun pathItem(
        path: String,
        folder: Boolean,
    ) = FilterItem(path, path) {
        FontIcon(if (folder) MaterialDesignF.FOLDER_OUTLINE else MaterialDesignF.FILE_OUTLINE).apply {
            styleClass += "git-filter-icon"
        }
    }

    /** Reads the user name in the background, once per opened root; the user popup shows it when it is there. */
    private fun readUserName() {
        val root = pages.root ?: return
        if (userNameRoot == root) return
        userNameRoot = root
        executor.execute {
            val name = source.userName(root)
            dispatch {
                if (pages.root == root && userNameRoot == root) {
                    userName = name
                    filterBar.user.popup.refresh()
                }
            }
        }
    }

    /** Reads the tree paths in the background, once per opened root and refresh; the path popup shows them then. */
    private fun readPaths() {
        val root = pages.root ?: return
        if (pathsRoot == root && !pathsStale) return
        pathsRoot = root
        pathsStale = false
        executor.execute {
            val read = source.paths(root)
            dispatch {
                if (pages.root == root && pathsRoot == root) {
                    paths = read.orEmpty()
                    filterBar.path.popup.refresh()
                }
            }
        }
    }

    /**
     * [file] in the working tree; `null` without a repository or if its name cannot be a path in the JVM's locale
     * (non-ASCII characters in a plain ASCII one).
     */
    private fun localFile(file: GitChangedFile): Path? =
        try {
            pages.root?.resolve(file.path)
        } catch (_: InvalidPathException) {
            null
        }

    /** Shows [text] in the header, e.g. why a diff cannot be shown, until the next selection or load. */
    fun showStatus(text: String) = status.set(text)

    /**
     * Reads what the commit of [row] changed in [file] and hands it to [onShowDiff]; a binary, too large or unchanged
     * file, a submodule or a failure is told in the header instead, unless the selection changed meanwhile.
     */
    private fun showDiff(
        row: GitLogRow,
        file: GitChangedFile,
    ) {
        val commit = row.commit
        val requestedAt = selections
        showCommitDiff(commit.hash, commit.parents.firstOrNull(), file) { message ->
            if (selections == requestedAt) showStatus(message)
        }
    }

    /**
     * Reads what [commit] changed in [file] against [parent] (the first parent, or a push's base, ADR 0049) and hands it
     * to [onShowDiff]; a binary, too large or unchanged file, a submodule or a failure goes to [tell] instead.
     */
    private fun showCommitDiff(
        commit: String,
        parent: String?,
        file: GitChangedFile,
        tell: (String) -> Unit,
    ) {
        val root = pages.root ?: return
        fileDiffs.request(root, commit, parent, file) { diff ->
            val name = file.path.substringAfterLast('/')
            val message =
                when {
                    diff == null -> {
                        "The diff could not be read."
                    }

                    diff == GitFileDiff.Binary -> {
                        "$name is a binary file."
                    }

                    diff == GitFileDiff.TooLarge -> {
                        "$name is too large to compare."
                    }

                    diff is GitFileDiff.Submodule -> {
                        submoduleMessage(name, diff)
                    }

                    // A pure rename or a change of the mode only: no empty diff.
                    diff is GitFileDiff.Text && diff.original == diff.current && file.change.changesContent -> {
                        "$name has no content changes."
                    }

                    else -> {
                        null
                    }
                }
            if (message != null) {
                tell(message)
            } else if (diff is GitFileDiff.Text) {
                onShowDiff(
                    CommitFileDiff(
                        root,
                        file.path,
                        file.oldPath,
                        file.change,
                        commit,
                        parent,
                        diff.original,
                        diff.current,
                        diff.hunks,
                    ),
                )
            }
        }
    }

    private fun submoduleMessage(
        name: String,
        diff: GitFileDiff.Submodule,
    ): String {
        val old = diff.old?.take(ChangedFilesPane.SHORT_HASH)
        val new = diff.new?.take(ChangedFilesPane.SHORT_HASH)
        return when {
            old != null && new != null -> "$name is a submodule: $old → $new."
            new != null -> "$name is a submodule, added at $new."
            else -> "$name is a submodule, removed."
        }
    }

    /**
     * Loads the first page again now if [expanded], else the next time it is expanded; keeps the selection by hash.
     * Called for the Refresh button and for changes in `.git`, after which `HEAD`'s tree is read again too. The file
     * histories and the Commit tab read again as well, now if shown, else when shown next.
     */
    fun refresh() {
        // The tree may have changed: read again when the path popup is shown next.
        pathsStale = true
        pages.refresh()
        histories.values.forEach { it.view.history.refresh() }
        commitTab?.view?.reader?.reload()
    }

    /**
     * Shows the bar of what git is in the middle of ([inProgress]), with Continue and Abort for a single cherry-pick or
     * revert only; hides it for `null`.
     */
    private fun showPending(inProgress: GitInProgress?) {
        pending = inProgress as? GitPendingPick
        busy.set(inProgress != null)
        pendingLabel.text =
            when (inProgress) {
                is GitPendingPick -> pendingText(inProgress)
                is GitPendingOperation -> operationText(inProgress.operation)
                null -> ""
            }
        pendingLabel.tooltip.text = pendingLabel.text
        pendingBar.isVisible = inProgress != null
        pendingBar.isManaged = inProgress != null
        listOf(continueButton, abortButton).forEach {
            it.isVisible = pending != null
            it.isManaged = pending != null
        }
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

    /**
     * Aborts the cherry-pick or revert in progress ([pending]) once confirmed, as it undoes what was done to resolve its
     * conflicts; Cancel is the default. The dialog right after it stopped aborts without asking: nothing is resolved yet.
     */
    private fun abortPending(pending: GitPendingPick) {
        if (writer.running.get()) return
        val confirmation =
            RefConfirmation(
                "Abort ${pending.kind.title}",
                abortQuestion(pending.kind, pending.commit),
                abortButton(pending.kind),
                AbortPick(pending.kind, pending.commit),
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
        status.set("")
        if (writer.run(root, operation, operationStatus::set) { outcome -> finished(operation, outcome) }) {
            operationStatus.set(operation.progress)
        }
    }

    private fun finished(
        operation: RefOperation,
        outcome: RefOutcome,
    ) {
        operationStatus.set(outcome.done.orEmpty())
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
        refresh()
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
        closeWorkingTreeWindows()
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

/**
 * Two panes side by side (or one above the other, [orientation]) that keep their shares of the width (height) when it
 * changes: [share] for the first at first, then wherever the divider is dragged to (while the window is open). A plain
 * split pane would keep the pixel size of a pane that does not resize with it, or split the change equally between
 * panes that do.
 */
internal class SharedSplit(
    private val first: Node,
    second: Node,
    share: Double,
    orientation: Orientation = Orientation.HORIZONTAL,
) : SplitPane(first, second) {
    /** The first pane's share of the width (height); follows the divider when it is dragged. */
    var share = share
        private set

    private var laidOutSize = -1.0
    private var layingOut = false

    private val size get() = if (orientation == Orientation.VERTICAL) height else width

    /** Whether the first pane is shown; hidden, the second takes the whole size, shown again it gets its share back. */
    var firstShown = true
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                items.add(0, first)
                followDivider()
            } else {
                items.remove(first)
            }
            laidOutSize = -1.0
        }

    init {
        this.orientation = orientation
        // The skin would spread a change of the width equally over the panes that resize with it; with none it keeps
        // the divider position set before each layout at a new width.
        SplitPane.setResizableWithParent(first, false)
        SplitPane.setResizableWithParent(second, false)
        followDivider()
    }

    /** Puts the divider at the [share] and follows it from now on; there is a new one whenever the first pane is. */
    private fun followDivider() {
        setDividerPositions(share)
        // Moved outside a layout: dragged (or set). Within one the skin only fits it to the panes' minimum widths.
        dividers.single().positionProperty().subscribe { position -> if (!layingOut) this.share = position.toDouble() }
    }

    override fun layoutChildren() {
        layingOut = true
        try {
            if (size != laidOutSize) setDividerPositions(share)
            super.layoutChildren()
        } finally {
            layingOut = false
        }
        laidOutSize = size
    }
}

/** A column of the commits with the texts [value] gives, not sortable; also of a file's history (ADR 0046). */
internal fun column(
    title: String,
    width: Double,
    value: (GitLogRow) -> String,
) = TableColumn<GitLogRow, String>(title).apply {
    prefWidth = width
    // Sorting would mix the order the pages are loaded in.
    isSortable = false
    setCellValueFactory { ReadOnlyStringWrapper(value(it.value)).readOnlyProperty }
}

/**
 * The subject with the commit graph left of it (ADR 0036) and the ref labels at its right end (ADR 0037); rows without
 * a graph ([GraphRow.NONE], e.g. of a file's history) have the subject at the left.
 */
internal fun subjectColumn() =
    TableColumn<GitLogRow, GitLogRow>("Subject").apply {
        prefWidth = 600.0
        minWidth = 80.0
        isSortable = false
        setCellValueFactory { ReadOnlyObjectWrapper(it.value).readOnlyProperty }
        setCellFactory { GraphCell() }
    }

/** Reports the last visible row after every layout and scroll, so the next page is loaded in time. */
internal class PagingSkin(
    table: TableView<GitLogRow>,
    private val shown: (Int) -> Unit,
) : TableViewSkin<GitLogRow>(table) {
    // Called by the super constructor: uses `shown` only later, in layouts and scrolls.
    override fun createVirtualFlow(): VirtualFlow<TableRow<GitLogRow>> =
        object : VirtualFlow<TableRow<GitLogRow>>() {
            override fun layoutChildren() {
                super.layoutChildren()
                report()
            }

            override fun scrollPixels(delta: Double): Double = super.scrollPixels(delta).also { report() }

            private fun report() {
                val index = lastVisibleCell?.index ?: return
                if (index >= 0) shown(index)
            }
        }
}

/**
 * Keeps the columns as wide as the table: a resized table widens or narrows only the first column (the subject), so
 * the others keep their widths ([fitColumnWidths]); dragging a column's edge resizes the next column as well, and the
 * new widths of the others become the widths they return to.
 */
internal object SubjectTakesTheRest :
    ConstrainedColumnResizeBase(),
    Callback<TableView.ResizeFeatures<*>, Boolean> {
    override fun call(features: TableView.ResizeFeatures<*>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val resize = features as TableView.ResizeFeatures<GitLogRow>
        val columns = resize.table.visibleLeafColumns
        if (columns.isEmpty()) return false
        if (resize.column != null) {
            val resized = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_NEXT_COLUMN.call(resize)
            // Wanted widths are kept as preferred widths; setting them does not change the current ones.
            columns.drop(1).forEach { it.prefWidth = it.width }
            return resized
        }
        val others = columns.drop(1)
        val widths =
            fitColumnWidths(
                resize.contentWidth,
                columns.first().minWidth,
                others.map { it.prefWidth },
                others.map { it.minWidth },
            )
        columns.zip(widths).forEach { (column, width) -> resize.setColumnWidth(column, width) }
        return true
    }
}

/**
 * The widths of a first column that takes the rest of [contentWidth] and of the [others] (their wanted widths), whose
 * minimum widths are [otherMinWidths]. The others keep their widths while the first column can be at least
 * [firstMinWidth] wide; below that they shrink proportionally towards their minimum widths, so the last column stays
 * in view. Narrower than all minimum widths together, every column is at its minimum and the last ones are cut off at
 * the right (a constrained table has no horizontal scroll bar).
 */
internal fun fitColumnWidths(
    contentWidth: Double,
    firstMinWidth: Double,
    others: List<Double>,
    otherMinWidths: List<Double>,
): List<Double> {
    val wanted = others.sum()
    if (contentWidth - wanted >= firstMinWidth) return listOf(contentWidth - wanted) + others
    val shrinkable = others.zip(otherMinWidths).sumOf { (width, min) -> maxOf(0.0, width - min) }
    val excess = wanted - (contentWidth - firstMinWidth)
    val share = if (shrinkable > 0) minOf(1.0, excess / shrinkable) else 1.0
    val shrunk = others.zip(otherMinWidths).map { (width, min) -> maxOf(min, width - maxOf(0.0, width - min) * share) }
    return listOf(maxOf(firstMinWidth, contentWidth - shrunk.sum())) + shrunk
}

internal val COPY = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)

private val FIND = KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN)

private val GO_TO = KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN)

/** What the header says when the Commit tab's changes could not be read again while it shows those read before. */
private const val COMMIT_READ_FAILED = "The changes could not be read."
