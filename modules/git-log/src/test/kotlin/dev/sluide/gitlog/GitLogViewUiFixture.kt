package dev.sluide.gitlog

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitChanges
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitFileHistoryEntry
import dev.sluide.git.GitFileHistoryPage
import dev.sluide.git.GitHunk
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitRevisionResolution
import dev.sluide.git.GitTreePath
import dev.sluide.gitlog.data.ChangeFile
import dev.sluide.gitlog.data.ChangeFolder
import dev.sluide.gitlog.data.ChangeNode
import dev.sluide.gitlog.data.FakeWrites
import dev.sluide.gitlog.data.GitLogRow
import dev.sluide.gitlog.data.GitLogSource
import dev.sluide.gitlog.data.GraphRow
import dev.sluide.gitlog.data.MAX_LANES
import dev.sluide.gitlog.filter.FilterButton
import dev.sluide.gitlog.filter.FilterItem
import dev.sluide.gitlog.filter.FilterPopup
import dev.sluide.gitlog.table.GraphCell
import dev.sluide.gitlog.table.GraphPane
import dev.sluide.gitlog.table.LANE_WIDTH
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TableRow
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.shape.Circle
import javafx.scene.shape.LineTo
import javafx.scene.shape.MoveTo
import javafx.scene.text.Text
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import javafx.scene.shape.Path as ShapePath

/**
 * The real git log view with a fake loader, shared by the `GitLogView*UiTest` classes. Needs a display, see the
 * `uiTest` Gradle task.
 */
abstract class GitLogViewUiFixture {
    internal val repo = Path.of("/repo")

    /** The commits of the repository, newest first; `null` makes loading fail. */
    @Volatile
    internal var history: List<GitCommit>? = commits(1200)

    /** The same for any other folder. */
    @Volatile
    internal var otherHistory: List<GitCommit>? = null

    /** Loads wait for this while it is not `null`. */
    @Volatile
    internal var gate: CountDownLatch? = null

    /** The (skip, limit) of every load. */
    internal val loads = CopyOnWriteArrayList<Pair<Int, Int>>()

    /** The filter of every load. */
    internal val filters = CopyOnWriteArrayList<GitLogFilter>()

    /** The commits each revision reaches, for the revisions of a filter. */
    @Volatile
    internal var reachable: Map<String, Set<String>> = emptyMap()

    /** The commit a filter's text names as a hash. */
    @Volatile
    internal var hashMatchOf: (GitLogFilter) -> GitCommit? = { null }

    /** The user name of the repository. */
    @Volatile
    internal var userName: String? = null

    /** The paths of `HEAD`'s tree. */
    @Volatile
    internal var treePaths: List<GitTreePath>? = emptyList()

    /** Reading the user name or the tree's paths of a root waits for its gate, if it has one. */
    internal val userNameGates = ConcurrentHashMap<Path, CountDownLatch>()
    internal val pathGates = ConcurrentHashMap<Path, CountDownLatch>()

    /** The root of every read of the user name and of the tree's paths, once its gate is open. */
    internal val userNameLoads = CopyOnWriteArrayList<Path>()
    internal val pathLoads = CopyOnWriteArrayList<Path>()

    /** The details of each commit; `null` makes reading them fail. */
    @Volatile
    internal var detailsOf: (String) -> GitCommitDetails? = ::defaultDetails

    /** Reading the details of a commit waits for its gate, if it has one. */
    internal val detailGates = ConcurrentHashMap<String, CountDownLatch>()

    /** The hash of every read of details, once its gate is open. */
    internal val detailLoads = CopyOnWriteArrayList<String>()

    /** The refs of the repository; `null` makes reading them fail. */
    @Volatile
    internal var refs: GitRefs? = null

    /** The files each commit changed; `null` makes reading them fail. */
    @Volatile
    internal var filesOf: (String) -> List<GitChangedFile>? = { someFiles }

    /** Reading the changed files of a commit waits for its gate, if it has one. */
    internal val fileGates = ConcurrentHashMap<String, CountDownLatch>()

    /** The (commit, first parent) of every read of changed files, once its gate is open. */
    internal val fileLoads = CopyOnWriteArrayList<Pair<String, String?>>()

    /** The diff of each changed file; `null` makes reading it fail. */
    @Volatile
    internal var diffOf: (GitChangedFile) -> GitFileDiff? = { file ->
        GitFileDiff.Text("old ${file.path}\n", "new ${file.path}\n", listOf(GitHunk(0..0, 0..0)))
    }

    /** The (commit, first parent, path) of every read of a diff. */
    internal val diffLoads = CopyOnWriteArrayList<Triple<String, String?, String>>()

    /** Reading a diff waits for this while it is not `null`. */
    @Volatile
    internal var diffGate: CountDownLatch? = null

    /** The full refnames of the branches containing each commit; `null` makes reading them fail. */
    @Volatile
    internal var branchesOf: (String) -> List<String>? = { emptyList() }

    /** Reading the branches containing a commit waits for its gate, if it has one. */
    internal val branchGates = ConcurrentHashMap<String, CountDownLatch>()

    /** The hash of every read of the branches containing a commit, once its gate is open. */
    internal val branchLoads = CopyOnWriteArrayList<String>()

    /** What each revision names; by default a hash of [history] names its commit, a unique prefix too. */
    @Volatile
    internal var resolutionOf: (String) -> GitRevisionResolution = ::defaultResolution

    /** Resolving revisions fails while this is set. */
    @Volatile
    internal var resolveFails = false

    /** Resolving revisions waits for this while it is not `null`. */
    @Volatile
    internal var resolveGate: CountDownLatch? = null

    /** The revisions of every resolution, once its gate is open. */
    internal val resolveLoads = CopyOnWriteArrayList<List<String>>()

    /** The history of each (path, start) (ADR 0046); `null` makes reading it fail. */
    @Volatile
    internal var fileHistoryOf: (String, String?) -> List<GitFileHistoryEntry>? = { _, _ -> emptyList() }

    /** Reading a file's history waits for this while it is not `null`. */
    @Volatile
    internal var historyGate: CountDownLatch? = null

    /** The cancellation of every read of a file's history. */
    internal val historyCancellations = CopyOnWriteArrayList<GitCancellation>()

    /** The (path, start, skip, limit) of every read of a file's history. */
    internal val historyLoads = CopyOnWriteArrayList<List<Any?>>()

    /** The (commit, file) of every read of a working-tree diff. */
    internal val workingTreeDiffLoads = CopyOnWriteArrayList<Pair<String, GitChangedFile>>()

    /** The working-tree diff of each file; `null` makes reading it fail. */
    @Volatile
    internal var workingTreeDiffOf: (GitChangedFile) -> GitFileDiff? = { file ->
        GitFileDiff.Text("old ${file.path}\n", "new ${file.path}\n", listOf(GitHunk(0..0, 0..0)))
    }

    internal lateinit var view: GitLogView

    internal val writes = FakeWrites()

    internal fun startView(stage: Stage) {
        val source =
            object : GitLogSource {
                override fun page(
                    root: Path,
                    filter: GitLogFilter,
                    skip: Int,
                    limit: Int,
                ): GitLogPage? {
                    if (!gate.passed()) return null
                    loads += skip to limit
                    filters += filter
                    return (if (root == repo) history else otherHistory)?.let { history ->
                        val all = history.filter { passes(filter, it) }
                        val page = all.drop(skip).take(limit)
                        GitLogPage(page, complete = skip + page.size >= all.size)
                    }
                }

                override fun hashMatch(
                    root: Path,
                    filter: GitLogFilter,
                ) = hashMatchOf(filter)

                override fun userName(root: Path): String? {
                    if (!userNameGates[root].passed()) return null
                    userNameLoads.add(root)
                    return if (root == repo) userName else null
                }

                override fun paths(root: Path): List<GitTreePath>? {
                    if (!pathGates[root].passed()) return null
                    pathLoads.add(root)
                    return if (root == repo) treePaths else emptyList()
                }

                override fun refs(root: Path) = if (root == repo) refs else null

                override fun details(
                    root: Path,
                    hash: String,
                ): GitCommitDetails? {
                    if (!detailGates[hash].passed()) return null
                    detailLoads += hash
                    return detailsOf(hash)
                }

                override fun branchesContaining(
                    root: Path,
                    hash: String,
                ): List<String>? {
                    if (!branchGates[hash].passed()) return null
                    branchLoads += hash
                    return branchesOf(hash)
                }

                override fun resolveCommits(
                    root: Path,
                    revisions: List<String>,
                ): List<GitRevisionResolution>? {
                    if (!resolveGate.passed()) return null
                    resolveLoads += revisions
                    return if (resolveFails) null else revisions.map(resolutionOf)
                }

                override fun changedFiles(
                    root: Path,
                    commit: String,
                    firstParent: String?,
                ): List<GitChangedFile>? {
                    if (!fileGates[commit].passed()) return null
                    fileLoads += commit to firstParent
                    return filesOf(commit)
                }

                override fun fileDiff(
                    root: Path,
                    commit: String,
                    firstParent: String?,
                    file: GitChangedFile,
                ): GitFileDiff? {
                    if (!diffGate.passed()) return null
                    diffLoads += Triple(commit, firstParent, file.path)
                    return diffOf(file)
                }

                override fun fileHistory(
                    root: Path,
                    path: String,
                    start: String?,
                    skip: Int,
                    limit: Int,
                    cancellation: GitCancellation,
                ): GitFileHistoryPage? {
                    historyCancellations += cancellation
                    if (!historyGate.passed()) return null
                    historyLoads += listOf(path, start, skip, limit)
                    val all = (if (root == repo) fileHistoryOf(path, start) else emptyList()) ?: return null
                    val page = all.drop(skip).take(limit)
                    return GitFileHistoryPage(page, complete = skip + page.size >= all.size)
                }

                override fun workingTreeFileDiff(
                    root: Path,
                    commit: String,
                    file: GitChangedFile,
                ): GitFileDiff? {
                    workingTreeDiffLoads += commit to file
                    return workingTreeDiffOf(file)
                }

                override fun commitChanges(root: Path): GitCommitChanges? = null

                override fun headCommit(root: Path): String? = null

                override fun headMessage(root: Path): String? = null
            }
        view = GitLogView(source, Platform::runLater, writes)
        stage.scene = Scene(view, 800.0, 400.0)
        stage.show()
    }

    /**
     * Waits until this gate (if any) is open; `false` if the wait is interrupted: the view's executor is shut down at
     * the end of a test, possibly before a read it released has woken up.
     */
    internal fun CountDownLatch?.passed(): Boolean =
        try {
            this?.await(10, TimeUnit.SECONDS)
            true
        } catch (_: InterruptedException) {
            false
        }

    @AfterEach
    fun close() {
        gate?.countDown()
        detailGates.values.forEach(CountDownLatch::countDown)
        fileGates.values.forEach(CountDownLatch::countDown)
        diffGate?.countDown()
        historyGate?.countDown()
        branchGates.values.forEach(CountDownLatch::countDown)
        resolveGate?.countDown()
        userNameGates.values.forEach(CountDownLatch::countDown)
        pathGates.values.forEach(CountDownLatch::countDown)
        view.close()
    }

    /**
     * Whether [commit] passes [filter], as far as the fake understands it: the text as a substring of the subject
     * (ignoring case unless asked), the revisions by [reachable], the users by the author's name.
     */
    internal fun passes(
        filter: GitLogFilter,
        commit: GitCommit,
    ): Boolean {
        val text = filter.text.trim()
        return commit.subject.contains(text, ignoreCase = !filter.matchCase) &&
            (filter.revisions.isEmpty() || filter.revisions.any { commit.hash in reachable[it].orEmpty() }) &&
            (filter.users.isEmpty() || filter.users.any { it.equals(commit.author, ignoreCase = true) })
    }

    /** A commit of [history] whose hash is or starts with [revision]; ambiguous if several do. */
    internal fun defaultResolution(revision: String): GitRevisionResolution {
        val matching = history.orEmpty().filter { it.hash.startsWith(revision) }
        return when {
            matching.any { it.hash == revision } -> GitRevisionResolution.Commit(revision)
            matching.size == 1 -> GitRevisionResolution.Commit(matching.single().hash)
            matching.size > 1 -> GitRevisionResolution.Ambiguous
            else -> GitRevisionResolution.Missing
        }
    }

    /** A linear history of [count] commits, newest first. */
    internal fun commits(
        count: Int,
        prefix: String = "h",
    ) = (1..count).map {
        GitCommit(
            "$prefix$it",
            if (it < count) listOf("$prefix${it + 1}") else emptyList(),
            "Author $it",
            "a$it@example.com",
            Instant.ofEpochSecond(it * 60L),
            "Subject $it",
        )
    }

    internal fun openAndExpand(robot: FxRobot) {
        robot.interact {
            view.open(repo)
            view.expanded.set(true)
        }
        eventually { assertTrue(view.table.items.isNotEmpty()) }
    }

    /** Hides the branches pane and drags the details pane out of view, so that the table takes the whole width. */
    internal fun hideDetails(robot: FxRobot) {
        robot.interact {
            bar.branchesToggle.isSelected = false
            view.inner.setDividerPositions(1.0)
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Waits until [assertion] holds on the JavaFX thread. */
    internal fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            val failure = WaitForAsyncUtils.asyncFx<Throwable?> { runCatching(assertion).exceptionOrNull() }.get()
            if (failure == null) return
            if (System.nanoTime() > deadline) throw failure
            Thread.sleep(20)
        }
    }

    internal fun click(
        robot: FxRobot,
        node: Node,
        clickCount: Int = 1,
        sceneX: Double = 1.0,
        sceneY: Double = 1.0,
    ) {
        robot.interact { fireClick(node, clickCount, sceneX, sceneY) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Fires a click at [node] on the JavaFX thread, without waiting: for steps that must not be interrupted. */
    internal fun fireClick(
        node: Node,
        clickCount: Int = 1,
        sceneX: Double = 1.0,
        sceneY: Double = 1.0,
    ) {
        for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)) {
            Event.fireEvent(
                node,
                // Its x and y are the scene's, made local to each node the event reaches.
                MouseEvent(
                    type,
                    sceneX,
                    sceneY,
                    sceneX,
                    sceneY,
                    MouseButton.PRIMARY,
                    clickCount,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )
        }
    }

    internal fun shownSubjects(robot: FxRobot): List<String> =
        robot
            .lookup(".git-log-table .table-row-cell")
            .queryAllAs(TableRow::class.java)
            .filter { !it.isEmpty && it.item != null }
            .sortedBy { it.index }
            .map { (it.item as GitLogRow).commit.subject }

    internal fun placeholder(robot: FxRobot) =
        robot.lookup(".git-log-table .placeholder .label").queryAs(Label::class.java).text

    internal fun clipboard() = WaitForAsyncUtils.asyncFx<String> { Clipboard.getSystemClipboard().string }.get()

    internal fun commit(
        hash: String,
        vararg parents: String,
    ) = GitCommit(hash, parents.toList(), "A", "a@example.com", Instant.EPOCH, "Subject $hash")

    /** A line of the graph: from (x, y) to (x, y) in a colour index. */
    internal data class Segment(
        val fromX: Double,
        val fromY: Double,
        val toX: Double,
        val toY: Double,
        val colour: Int,
    )

    /** The coloured lines [pane] draws (not its halo). */
    internal fun drawn(pane: GraphPane): Set<Segment> =
        pane.childrenUnmodifiable
            .filterIsInstance<ShapePath>()
            .filter { "git-graph-edge" in it.styleClass }
            .flatMap { path ->
                val colour =
                    path.styleClass
                        .single { it.matches(Regex("git-graph-edge-\\d")) }
                        .last()
                        .digitToInt()
                path.elements.chunked(2).map { (move, line) ->
                    move as MoveTo
                    line as LineTo
                    Segment(move.x, move.y, line.x, line.y, colour)
                }
            }.toSet()

    /** The lines [row] should be drawn with at [height]: all but those with both ends beyond the lane cap. */
    internal fun expected(
        row: GraphRow,
        height: Double,
    ): Set<Segment> {
        fun x(position: Int) = (position + 0.5) * LANE_WIDTH
        val upper = (0 until row.upperEdges).map { Triple(row.upperFrom(it), row.upperTo(it), row.upperColour(it)) }
        val lower = (0 until row.lowerEdges).map { Triple(row.lowerFrom(it), row.lowerTo(it), row.lowerColour(it)) }
        return (
            upper.map { (from, to, colour) -> Segment(x(from), 0.0, x(to), height / 2, colour) to (from to to) } +
                lower.map { (from, to, colour) -> Segment(x(from), height / 2, x(to), height, colour) to (from to to) }
        ).filter { (_, ends) -> ends.first < MAX_LANES || ends.second < MAX_LANES }
            .map { it.first }
            .toSet()
    }

    /** Asserts that [cell] shows exactly the graph of its row, or none when it is empty. */
    internal fun assertDrawn(cell: GraphCell) {
        val item = cell.item
        if (item == null) {
            assertNull(cell.graphic, "empty cell ${cell.index}")
            assertNull(cell.content.parent, "empty cell ${cell.index}")
            return
        }
        val graph = item.graph
        assertSame<Node?>(cell.content, cell.graphic)
        assertEquals(minOf(graph.width, MAX_LANES) * LANE_WIDTH, cell.graph.width, "row ${cell.index}")
        assertEquals(expected(graph, cell.height), drawn(cell.graph), "row ${cell.index}")
        val dot =
            cell.graph.childrenUnmodifiable
                .filterIsInstance<Circle>()
                .single { "git-graph-dot" in it.styleClass }
        assertEquals((graph.dot + 0.5) * LANE_WIDTH, dot.centerX, "row ${cell.index}")
        assertEquals(cell.height / 2, dot.centerY, "row ${cell.index}")
        assertTrue("git-graph-dot-${graph.colour}" in dot.styleClass, "row ${cell.index}: ${dot.styleClass}")
        assertEquals(
            1,
            dot.styleClass.count { it.startsWith("git-graph-dot-") },
            "row ${cell.index}: ${dot.styleClass}",
        )
    }

    internal fun graphCells(robot: FxRobot) =
        robot
            .lookup(".git-graph-cell")
            .queryAllAs(GraphCell::class.java)
            .filter { it.tableRow?.isVisible == true && it.index >= 0 }

    internal fun ref(
        name: String,
        kind: GitRefKind,
        commit: String,
    ) = GitRef(name, kind, commit)

    /** HEAD and main (with origin/main) on h1, a tag on h3, a remote-only branch on h40. */
    internal val someRefs =
        GitRefs(
            listOf(
                ref("HEAD", GitRefKind.HEAD, "h1"),
                ref("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1"),
                ref("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, "h1"),
                ref("refs/tags/v1.0", GitRefKind.TAG, "h1"),
                ref("refs/tags/v0.9", GitRefKind.TAG, "h3"),
                ref("refs/remotes/origin/old", GitRefKind.REMOTE_BRANCH, "h40"),
            ),
            "refs/heads/main",
        )

    internal fun visibleIcons(cell: GraphCell) =
        cell.labels
            .lookupAll(".git-ref-icon")
            .filter { it.isVisible && it.isManaged }
            .map { icon -> icon.styleClass.single { it.startsWith("git-ref-") && it != "git-ref-icon" } }

    /** The details of a commit of [commits]: committed by its author when it was written, with a long body. */
    internal fun defaultDetails(hash: String): GitCommitDetails? {
        val commit = history?.firstOrNull { it.hash == hash } ?: return null
        val body = (1..40).joinToString(" ") { "Body of ${hash.removePrefix("h")}." }
        val message = "${commit.subject}\n\n$body\n"
        return GitCommitDetails(commit, commit.author, commit.authorEmail, commit.authorTime, message)
    }

    internal fun at(time: Instant): String {
        val zone = java.time.ZoneId.systemDefault()
        val date =
            java.time.format.DateTimeFormatter
                .ofLocalizedDate(java.time.format.FormatStyle.SHORT)
        val clock =
            java.time.format.DateTimeFormatter
                .ofLocalizedTime(java.time.format.FormatStyle.SHORT)
        return "${date.withZone(zone).format(time)} at ${clock.withZone(zone).format(time)}"
    }

    internal fun select(
        robot: FxRobot,
        vararg indices: Int,
    ) {
        robot.interact {
            view.table.selectionModel.clearSelection()
            indices.forEach(view.table.selectionModel::select)
        }
    }

    internal val details get() = view.detailsPane

    internal fun showsDetailsOf(subject: String) =
        details.scroll.isVisible && !details.placeholder.isVisible && details.subject.text == subject

    internal fun placeholderShown() =
        if (details.placeholder.isVisible &&
            !details.scroll.isVisible
        ) {
            details.placeholder.text
        } else {
            null
        }

    internal val someFiles =
        listOf(
            GitChangedFile(GitChange.MODIFIED, "src/main/kotlin/dev/One.kt"),
            GitChangedFile(GitChange.ADDED, "src/main/kotlin/dev/Two.kt"),
            GitChangedFile(GitChange.DELETED, "README.md"),
            GitChangedFile(GitChange.RENAMED, "docs/new.md", "docs/old.md"),
        )

    internal val changes get() = view.changesPane

    /** The expanded tree as indented names, folders ending with `/`. */
    internal fun shownTree(): List<String> {
        fun lines(
            item: TreeItem<ChangeNode>,
            indent: String,
        ): List<String> =
            item.children.flatMap { child ->
                when (val node = child.value) {
                    is ChangeFolder -> {
                        listOf("$indent${node.name}/") +
                            if (child.isExpanded) lines(child, "$indent  ") else emptyList()
                    }

                    is ChangeFile -> {
                        listOf("$indent${node.name}")
                    }
                }
            }
        return if (changes.tree.isVisible) lines(changes.tree.root, "") else emptyList()
    }

    internal fun filesPlaceholder() =
        if (changes.placeholder.isVisible && !changes.tree.isVisible) changes.placeholder.text else null

    /** The shown cells of the tree, top to bottom. */
    internal fun treeCells(robot: FxRobot): List<TreeCell<*>> =
        robot
            .lookup(".git-changes-tree .tree-cell")
            .queryAllAs(TreeCell::class.java)
            .filter { !it.isEmpty && it.item != null }
            .sortedBy { it.index }

    /** The cell of the file or folder named [name]. */
    internal fun treeCell(
        robot: FxRobot,
        name: String,
    ): TreeCell<*> = treeCells(robot).single { (it.item as ChangeNode).name == name }

    /** Selects the file or folder named [name] in the tree. */
    internal fun selectFile(
        robot: FxRobot,
        name: String,
    ) {
        robot.interact {
            val index =
                (0 until changes.tree.expandedItemCount).single {
                    changes.tree
                        .getTreeItem(it)
                        .value.name == name
                }
            changes.tree.selectionModel.clearAndSelect(index)
        }
    }

    internal fun pressInTree(
        robot: FxRobot,
        code: KeyCode,
        shortcut: Boolean = false,
    ) {
        val mac = System.getProperty("os.name").startsWith("Mac")
        robot.interact {
            Event.fireEvent(
                changes.tree,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, false, shortcut && mac),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    internal fun status(robot: FxRobot) = robot.lookup(".git-log-status").queryAs(Label::class.java).text

    internal fun showFilesOfFirstCommit(robot: FxRobot) {
        openAndExpand(robot)
        select(robot, 0)
        eventually { assertTrue(shownTree().isNotEmpty()) }
    }

    internal val bar get() = view.filterBar

    /** Fires a key press at [node], with the shortcut key (Ctrl, Cmd on macOS) if [shortcut]. */
    internal fun press(
        robot: FxRobot,
        node: Node,
        code: KeyCode,
        shortcut: Boolean = false,
    ) {
        val mac = System.getProperty("os.name").startsWith("Mac")
        robot.interact {
            Event.fireEvent(
                node,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, false, shortcut && mac),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** The texts of the items a filter popup lists, in order. */
    internal fun items(popup: FilterPopup) = popup.list.items.map { it.text }

    /** The list cell of [popup] showing [text]. */
    internal fun listCell(
        popup: FilterPopup,
        text: String,
    ): ListCell<*> =
        popup.list
            .lookupAll(".list-cell")
            .filterIsInstance<ListCell<*>>()
            .single { (it.item as? FilterItem)?.text == text }

    /** Clicks the list cell of [popup] showing [text]. */
    internal fun clickItem(
        robot: FxRobot,
        popup: FilterPopup,
        text: String,
        clickCount: Int = 1,
    ) {
        WaitForAsyncUtils.waitForFxEvents()
        val cell = listCell(popup, text)
        // A double click: a first click, then one with a click count of 2.
        for (count in 1..clickCount) click(robot, cell, count)
    }

    /**
     * Waits until the popup of [filter] lists [expected], showing it again if it was closed meanwhile: popups close
     * when another test JVM on the display takes the window's focus, and only a showing popup takes items read later.
     * If it stayed open, the items reached it while it was showing (`FilterPopup.refresh`).
     */
    internal fun eventuallyLists(
        filter: FilterButton,
        expected: List<String>,
    ) {
        var shownAgain = false
        var showingWhenListed = false
        eventually {
            if (!filter.popup.popup.isShowing) {
                filter.button.fire()
                shownAgain = true
            }
            assertEquals(expected, items(filter.popup))
            showingWhenListed = filter.popup.popup.isShowing
        }
        if (!shownAgain) assertTrue(showingWhenListed, "the items reached the open popup")
    }

    internal val branches get() = view.branchesPane

    /** HEAD and main on h1, branches in folders, two remotes (one far down) and two tags. */
    internal val branchRefs =
        GitRefs(
            listOf(
                ref("HEAD", GitRefKind.HEAD, "h1"),
                ref("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1"),
                ref("refs/heads/feature/a", GitRefKind.LOCAL_BRANCH, "h5"),
                ref("refs/heads/feature/b", GitRefKind.LOCAL_BRANCH, "h7"),
                ref("refs/heads/team/fix/x", GitRefKind.LOCAL_BRANCH, "h9"),
                ref("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, "h1"),
                ref("refs/remotes/origin/old", GitRefKind.REMOTE_BRANCH, "h40"),
                ref("refs/remotes/upstream/far", GitRefKind.REMOTE_BRANCH, "h800"),
                ref("refs/tags/v1.0", GitRefKind.TAG, "h1"),
                ref("refs/tags/v0.9", GitRefKind.TAG, "h3"),
            ),
            "refs/heads/main",
        )

    /** The tree as shown: the expanded nodes indented, groups and folders ending with `/`. */
    internal fun shownBranches(): List<String> = WaitForAsyncUtils.asyncFx<List<String>> { branchLines() }.get()

    /** [shownBranches], on the JavaFX thread. */
    internal fun branchLines(): List<String> {
        fun lines(
            item: TreeItem<BranchNode>,
            indent: String,
        ): List<String> =
            item.children.flatMap { child ->
                when (val node = child.value) {
                    is BranchParent -> {
                        listOf("$indent${node.name}/") +
                            if (child.isExpanded) lines(child, "$indent  ") else emptyList()
                    }

                    is BranchLeaf -> {
                        listOf("$indent${node.name}")
                    }
                }
            }
        return lines(branches.tree.root, "")
    }

    /** The tree item of the node at [path], also in collapsed nodes. */
    internal fun branchItem(path: String): TreeItem<BranchNode> {
        fun find(item: TreeItem<BranchNode>): TreeItem<BranchNode>? =
            if (item.value?.path == path) item else item.children.firstNotNullOfOrNull(::find)
        return checkNotNull(find(branches.tree.root)) { path }
    }

    /** Selects the nodes at [paths] in the tree, as with the mouse and Shift/Ctrl. */
    internal fun selectBranches(
        robot: FxRobot,
        vararg paths: String,
    ) {
        robot.interact {
            // At once, as a click with Shift does: not one after the other.
            val rows = paths.map { branches.tree.getRow(branchItem(it)) }
            branches.tree.selectionModel.clearSelection()
            branches.tree.selectionModel.selectIndices(rows.first(), *rows.drop(1).toIntArray())
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Clicks [cell] at its right end, where a click selects it (not on the disclosure arrow). */
    internal fun clickBranch(
        robot: FxRobot,
        cell: TreeCell<*>,
        clickCount: Int = 1,
    ) {
        val point = WaitForAsyncUtils.asyncFx<javafx.geometry.Point2D> { cell.localToScene(cell.width - 10, 5.0) }.get()
        click(robot, cell, clickCount, point.x, point.y)
    }

    internal fun branchCell(path: String): TreeCell<*> =
        branches.tree
            .lookupAll(".tree-cell")
            .filterIsInstance<TreeCell<*>>()
            .single { (it.item as? BranchNode)?.path == path }

    internal fun openBranches(robot: FxRobot) {
        refs = branchRefs
        openAndExpand(robot)
        eventually {
            assertTrue(
                branches.tree.root.children
                    .isNotEmpty(),
            )
        }
    }

    /** The indices of the table's rows in view, the first and last possibly cut off. */
    internal fun visibleRows(): IntRange {
        val flow = view.table.lookup(".virtual-flow") as VirtualFlow<*>
        return flow.firstVisibleCell.index..flow.lastVisibleCell.index
    }
}
