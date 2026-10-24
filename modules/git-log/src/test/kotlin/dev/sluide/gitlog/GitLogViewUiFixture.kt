package dev.sluide.gitlog

import dev.sluide.backgroundtasks.BackgroundTasks
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
import dev.sluide.gitlog.data.FakeWrites
import dev.sluide.gitlog.data.GitLogSource
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
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
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

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

    /** The root of every read of the user name and of the tree's paths. */
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
                    cancellation: GitCancellation?,
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
                    cancellation: GitCancellation?,
                ) = hashMatchOf(filter)

                override fun userName(root: Path): String? {
                    userNameLoads.add(root)
                    return if (root == repo) userName else null
                }

                override fun paths(root: Path): List<GitTreePath>? {
                    pathLoads.add(root)
                    return if (root == repo) treePaths else emptyList()
                }

                override fun refs(
                    root: Path,
                    cancellation: GitCancellation?,
                ) = if (root == repo) refs else null

                override fun details(
                    root: Path,
                    hash: String,
                    cancellation: GitCancellation?,
                ): GitCommitDetails? {
                    if (!detailGates[hash].passed()) return null
                    detailLoads += hash
                    return detailsOf(hash)
                }

                override fun branchesContaining(
                    root: Path,
                    hash: String,
                    cancellation: GitCancellation?,
                ): List<String>? {
                    if (!branchGates[hash].passed()) return null
                    branchLoads += hash
                    return branchesOf(hash)
                }

                override fun resolveCommits(
                    root: Path,
                    revisions: List<String>,
                    cancellation: GitCancellation?,
                ): List<GitRevisionResolution>? {
                    if (!resolveGate.passed()) return null
                    resolveLoads += revisions
                    return if (resolveFails) null else revisions.map(resolutionOf)
                }

                override fun changedFiles(
                    root: Path,
                    commit: String,
                    firstParent: String?,
                    cancellation: GitCancellation?,
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
                    cancellation: GitCancellation?,
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

                override fun headCommit(
                    root: Path,
                    cancellation: GitCancellation?,
                ): String? = null

                override fun headMessage(root: Path): String? = null
            }
        view = GitLogView(source, Platform::runLater, writes, BackgroundTasks.forTests())
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

    /**
     * Refreshes the log and waits until the reload replaced the rows, what follows on the JavaFX thread ran, and the
     * readers' threads have run what that gave them, so that a read that did not happen by then does not happen.
     */
    internal fun refreshAndAwaitReload(robot: FxRobot) {
        val before = WaitForAsyncUtils.asyncFx<Any?> { view.table.items.firstOrNull() }.get()
        robot.interact { view.refresh() }
        eventually { assertNotSame(before, view.table.items.firstOrNull(), "the rows of the reload") }
        WaitForAsyncUtils.waitForFxEvents()
        view.readersIdle().get(10, TimeUnit.SECONDS)
        WaitForAsyncUtils.waitForFxEvents()
    }

    /**
     * Waits until the readers' threads have run what they were given and what that gave the JavaFX thread, so that a
     * read that did not happen by then does not happen: the end of a negative check, instead of a sleep.
     */
    internal fun awaitReadersIdle() {
        WaitForAsyncUtils.waitForFxEvents()
        view.readersIdle().get(10, TimeUnit.SECONDS)
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

    internal fun commit(
        hash: String,
        vararg parents: String,
    ) = GitCommit(hash, parents.toList(), "A", "a@example.com", Instant.EPOCH, "Subject $hash")

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

    internal val someFiles =
        listOf(
            GitChangedFile(GitChange.MODIFIED, "src/main/kotlin/dev/One.kt"),
            GitChangedFile(GitChange.ADDED, "src/main/kotlin/dev/Two.kt"),
            GitChangedFile(GitChange.DELETED, "README.md"),
            GitChangedFile(GitChange.RENAMED, "docs/new.md", "docs/old.md"),
        )

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
}
