package dev.sluide.gitlog

import dev.sluide.git.GitBranches
import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommits
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitLog
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPick
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
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
import javafx.util.Callback
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignF
import org.kordamp.ikonli.materialdesign2.MaterialDesignR
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The git log area of a window (ADR 0035): a header bar ("Git Log", a chevron, Refresh) and, while [expanded], the
 * commits of the repository given to [open] in a table, loaded in pages in the background as the user scrolls, with
 * the refs pointing at them, and to the right the files the selected commit changed above its details (ADRs 0037,
 * 0038). The diff of a changed file goes to [onShowDiff], a file to open to [onOpenFile]. Above the table, the
 * filters of the commits (ADR 0039), per window and reset by [open]. Left of them, the branches pane (ADR 0040).
 * The context menus of the table and the branches pane check out, create, rename and delete branches and tags
 * (ADR 0041); the table's also cherry-picks and reverts commits and resets the current branch, and a bar above the
 * table continues or aborts a cherry-pick or revert in progress (ADR 0042). Operations that change files ask
 * [beforeFileChange] first.
 *
 * [source] reads the commits, refs, details, changed files and diffs on the thread `sluide-git-log`; [writes] changes
 * the repository on the thread `sluide-git-write`, one operation at a time. Their results are handled through
 * [dispatch], the JavaFX thread in the application.
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
    constructor(gitLog: GitLog, gitBranches: GitBranches, gitCommits: GitCommits) : this(
        GitLogCliSource(gitLog),
        Platform::runLater,
        GitLogCliWrites(gitBranches, gitCommits),
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
        )

    private val details =
        CommitDetails(
            source::details,
            { root, commit -> source.changedFiles(root, commit.hash, commit.parents.firstOrNull()) },
            executor::execute,
            dispatch,
        )

    private val fileDiffs = FileDiffs(source::fileDiff, executor::execute, dispatch)

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

    /** A message in the header, e.g. that a diff cannot be shown, until the next selection or load. */
    private val status = SimpleStringProperty("")

    /**
     * What the operation that changes the repository is doing ("Checking out main…") or did ("Checked out main."),
     * shown while there is no other [status]; until the next operation or [open].
     */
    private val operationStatus = SimpleStringProperty("")

    /** Counts the changes of the selected commits and files, so that a message about an earlier one is not shown. */
    private var selections = 0L

    private val chevron = FontIcon(MaterialDesignC.CHEVRON_RIGHT)

    private val refreshButton =
        Button(null, FontIcon(MaterialDesignR.REFRESH)).apply {
            styleClass += "git-log-refresh"
            tooltip = Tooltip("Refresh")
            isFocusTraversable = false
            setOnAction { refresh() }
        }

    /** The header bar; clicking it (but not Refresh) expands or collapses the log. */
    internal val header: HBox =
        HBox(
            chevron,
            Label("Git Log").apply { styleClass += "git-log-title" },
            // A failure while rows are shown (with no rows the table's placeholder says it), else the status, else what
            // an operation does or did.
            Label().apply {
                styleClass += "git-log-status"
                textProperty().bind(
                    Bindings.createStringBinding(
                        {
                            val state = pages.state.get()
                            when {
                                state == GitLogState.FAILED && pages.rows.isNotEmpty() -> state.message
                                status.get().isNotEmpty() -> status.get()
                                else -> operationStatus.get()
                            }
                        },
                        pages.state,
                        pages.rows,
                        status,
                        operationStatus,
                    ),
                )
            },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            refreshButton,
        ).apply {
            styleClass += "git-log-header"
            alignment = Pos.CENTER_LEFT
            setOnMouseClicked { event ->
                if (event.button == MouseButton.PRIMARY && !refreshButton.contains(event.target)) {
                    expanded.set(!expanded.get())
                }
            }
        }

    private val dateFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

    private val copyRevisionNumber = MenuItem("Copy Revision Number").apply { setOnAction { copyRevisionNumbers() } }

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
            placeholder = Label().apply { textProperty().bind(pages.state.map { it.message }) }
            contextMenu =
                ContextMenu(
                    copyRevisionNumber,
                    SeparatorMenuItem(),
                    checkoutRevisionItem,
                    newBranchItem,
                    newTagItem,
                    SeparatorMenuItem(),
                    cherryPickItem,
                    revertItem,
                    resetItem,
                )
            copyRevisionNumber.disableProperty().bind(Bindings.isEmpty(selectionModel.selectedItems))
            // One commit, and no operation running.
            val notOne = Bindings.size(selectionModel.selectedItems).isNotEqualTo(1).or(writer.running)
            listOf(checkoutRevisionItem, newBranchItem, newTagItem).forEach { it.disableProperty().bind(notOne) }
            // Not while git is in the middle of something: the bar says what.
            listOf(cherryPickItem, revertItem).forEach { it.disableProperty().bind(notOne.or(busy)) }
            resetItem.disableProperty().bind(notOne.or(hasHead.not()))
            addEventHandler(KeyEvent.KEY_PRESSED) { event ->
                if (COPY.match(event)) {
                    copyRevisionNumbers()
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

    private val pendingLabel = Label().apply { styleClass += "git-log-pending-text" }

    internal val continueButton =
        Button("Continue").apply {
            styleClass += "git-log-pending-continue"
            setOnAction { pending?.let { start(ContinuePick(it.kind, it.commit)) } }
            disableProperty().bind(writer.running)
        }

    internal val abortButton =
        Button("Abort").apply {
            styleClass += "git-log-pending-abort"
            setOnAction { pending?.let { start(AbortPick(it.kind, it.commit)) } }
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

    /** The filter bar and the bar of a cherry-pick or revert in progress above the table. */
    internal val commitsPane =
        BorderPane(table).apply {
            styleClass += "git-log-commits"
            top = VBox(filterBar, pendingBar)
        }

    /** The details of the selected commit. */
    internal val detailsPane = CommitDetailsPane(details.state)

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
            filter = filterBar::setBranches
            checkout = ::start
            newBranchFrom = { ref -> newBranch(ref.name, ref.shortName) }
            rename = ::renameBranch
            delete = ::deleteRefs
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
                }
            }
        }

    init {
        styleClass += "git-log"
        stylesheets += GIT_LOG_CSS
        top = header
        pages.selectedHashes = { table.selectionModel.selectedItems.map { it.commit.hash } }
        pages.select = { indices ->
            table.selectionModel.clearSelection()
            indices.forEach(table.selectionModel::select)
        }
        details.selection = { table.selectionModel.selectedItems.filterNotNull() }
        details.root = { pages.root }
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
                status.set("")
            }
        pages.state.subscribe { state -> if (state == GitLogState.LOADING) status.set("") }
        // The refs are read with every first page, before its state is set.
        pages.state.subscribe { state ->
            when (state) {
                GitLogState.IDLE -> {
                    showPending(null)
                    busy.set(false)
                    hasHead.set(false)
                }

                GitLogState.LOADING -> {
                    branchesPane.loading()
                }

                else -> {
                    branchesPane.show(pages.refs)
                    showPending(pages.pending as? GitPendingPick)
                    busy.set(pages.pending != null)
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
        changesPane.showDiff = ::showDiff
        changesPane.openFile = { file -> localFile(file)?.let(onOpenFile) }
        changesPane.exists = { file -> localFile(file)?.let(Files::exists) == true }
        expanded.subscribe { isExpanded ->
            val focusInBody = body.contains(scene?.focusOwner)
            center = if (isExpanded) body else null
            chevron.iconCode = if (isExpanded) MaterialDesignC.CHEVRON_DOWN else MaterialDesignC.CHEVRON_RIGHT
            pages.visible = isExpanded
            if (isExpanded) {
                // The split pane adds the table to the scene with its skin, which CSS creates.
                body.applyCss()
                table.requestFocus()
            } else if (focusInBody) {
                returnFocus()
            }
        }
    }

    /** Shows the log of the repository at [root]; `null` shows nothing. Loads once (or as soon as) [expanded]. */
    fun open(root: Path?) {
        fileDiffs.close()
        // An operation going on in the previous repository ends unnoticed.
        writer.forget()
        operationStatus.set("")
        // Before the pages, which may start loading and have the pane say so.
        branchesPane.reset()
        // Resets the filter too, before the bar, which then tells no change.
        pages.open(root)
        filterBar.reset()
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
            { missing ->
                if (branchesPane.selectedRef == ref) {
                    val name = ref.shortName
                    status.set(
                        when {
                            missing == CommitMissing.BEYOND_LIMIT -> {
                                "$name is more than ${"%,d".format(
                                    Locale.ENGLISH,
                                    GitLogPages.SEEK_LIMIT,
                                )} commits down."
                            }

                            pages.filter.isDefault -> {
                                "$name is not in the log."
                            }

                            else -> {
                                "$name is hidden by the filters."
                            }
                        },
                    )
                }
            },
        )
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
        val root = pages.root ?: return
        val commit = row.commit
        val requestedAt = selections
        fileDiffs.request(root, commit, file) { diff ->
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
                if (selections == requestedAt) showStatus(message)
            } else if (diff is GitFileDiff.Text) {
                onShowDiff(
                    CommitFileDiff(
                        root,
                        file.path,
                        file.oldPath,
                        file.change,
                        commit.hash,
                        commit.parents.firstOrNull(),
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
     * Called for the Refresh button and for changes in `.git`, after which `HEAD`'s tree is read again too.
     */
    fun refresh() {
        // The tree may have changed: read again when the path popup is shown next.
        pathsStale = true
        pages.refresh()
    }

    /** Shows the bar of the cherry-pick or revert [pending] in progress, or hides it for `null`. */
    private fun showPending(pending: GitPendingPick?) {
        this.pending = pending
        pendingLabel.text = pending?.let(::pendingText).orEmpty()
        pendingBar.isVisible = pending != null
        pendingBar.isManaged = pending != null
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
        status.set("")
        if (writer.run(root, operation) { outcome -> finished(operation, outcome) }) {
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
        // Also after a failure, which may have done a part. New and renamed branches alone are not noticed by the
        // watch of .git (ADR 0035).
        refresh()
        if (!outcome.result.success) {
            // Done all the same (a failing post-checkout hook): the header says what was done.
            val header = outcome.done?.takeIf { outcome.result.changed } ?: operation.failure
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
        writer.forget()
        // A running write is not interrupted: stopped halfway, a checkout would leave a half-switched working tree
        // (ADR 0041). Git finishes on its own; its outcome is dropped.
        writeExecutor.shutdown()
        pages.close()
        details.close()
        fileDiffs.close()
        detailsPane.close()
        changesPane.close()
        executor.shutdownNow()
    }

    private fun column(
        title: String,
        width: Double,
        value: (GitLogRow) -> String,
    ) = TableColumn<GitLogRow, String>(title).apply {
        prefWidth = width
        // Sorting would mix the order the pages are loaded in.
        isSortable = false
        setCellValueFactory { ReadOnlyStringWrapper(value(it.value)).readOnlyProperty }
    }

    /** The subject with the commit graph left of it (ADR 0036) and the ref labels at its right end (ADR 0037). */
    private fun subjectColumn() =
        TableColumn<GitLogRow, GitLogRow>("Subject").apply {
            prefWidth = 600.0
            minWidth = 80.0
            isSortable = false
            setCellValueFactory { ReadOnlyObjectWrapper(it.value).readOnlyProperty }
            setCellFactory { GraphCell() }
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

/** Reports the last visible row after every layout and scroll, so the next page is loaded in time. */
private class PagingSkin(
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
private object SubjectTakesTheRest :
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

private val COPY = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)

private val FIND = KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN)

/** Whether a file with this change had content on both sides, which may not have changed. */
private val GitChange.changesContent get() = this != GitChange.ADDED && this != GitChange.DELETED
