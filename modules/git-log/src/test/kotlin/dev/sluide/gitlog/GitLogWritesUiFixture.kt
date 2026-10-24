package dev.sluide.gitlog

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitChanges
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitOutgoing
import dev.sluide.git.GitPushTarget
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitTreePath
import dev.sluide.gitlog.data.BranchNode
import dev.sluide.gitlog.data.FakeWrites
import dev.sluide.gitlog.data.GitLogRow
import dev.sluide.gitlog.data.GitLogSource
import dev.sluide.gitlog.data.GitLogWrites
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.TableRow
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Region
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The git log's operations that change the repository (ADR 0041), with fake reads and writes, shared by the
 * `GitLogWrites*UiTest` classes. Needs a display.
 */
abstract class GitLogWritesUiFixture {
    internal val repo = Path.of("/repo")

    /** A line of ten commits; h6 also merges h9. */
    internal val commits =
        (1..10).map {
            val parents = listOf(it + 1, if (it == 6) 9 else 0).filter { parent -> parent in 2..10 }
            GitCommit("h$it", parents.map { parent -> "h$parent" }, "A", "a@x", Instant.EPOCH, "S $it")
        }

    /** HEAD and main on h1, two local branches in a folder, two remote branches and two tags. */
    internal val refs =
        GitRefs(
            listOf(
                GitRef("HEAD", GitRefKind.HEAD, "h1"),
                GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1"),
                GitRef("refs/heads/feature/a", GitRefKind.LOCAL_BRANCH, "h3"),
                GitRef("refs/heads/feature/b", GitRefKind.LOCAL_BRANCH, "h5"),
                GitRef("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, "h1"),
                GitRef("refs/remotes/origin/old", GitRefKind.REMOTE_BRANCH, "h4"),
                GitRef("refs/tags/v1.0", GitRefKind.TAG, "h1"),
                GitRef("refs/tags/v0.9", GitRefKind.TAG, "h2"),
            ),
            "refs/heads/main",
        )

    /** The refs the source reads. */
    @Volatile
    internal var currentRefs = refs

    /** The remotes the source reads; `null` when they cannot be read. */
    @Volatile
    internal var currentRemotes: List<String>? = listOf("origin")

    /** What git is in the middle of, as the source reads it (a cherry-pick, revert, rebase, ...). */
    @Volatile
    internal var pending: GitInProgress? = null

    /** What can be committed, as the source reads it (ADR 0048); `null` when it cannot be read. */
    @Volatile
    internal var commitChanges: GitCommitChanges? = GitCommitChanges(emptyList(), emptyList(), null, emptyList(), false)

    /** The commit `HEAD` points at, as the source reads it. */
    @Volatile
    internal var headCommit: String? = "h1"

    /** The message of `HEAD`, as the source reads it. */
    @Volatile
    internal var headMessage: String? = "S 1"

    /** The threads the commit tab's changes were read on, one per read. */
    internal val commitReads = CopyOnWriteArrayList<String>()

    /** The threads the message of `HEAD` was read on, one per read. */
    internal val headMessageReads = CopyOnWriteArrayList<String>()

    /** Holds every read of the message of `HEAD` until it is counted down; `null` lets them through. */
    @Volatile
    internal var headMessageGate: CountDownLatch? = null

    /** The working-tree diff the source reads, and every read as `<commit> <change> <path>`. */
    @Volatile
    internal var workingTreeDiff: GitFileDiff? = GitFileDiff.Text("old\n", "new\n", emptyList())
    internal val workingTreeDiffLoads = CopyOnWriteArrayList<String>()

    /** The name of the thread of every working-tree diff read. */
    internal val workingTreeDiffThreads = CopyOnWriteArrayList<String>()

    /** Where the source says the current branch is pushed by default (ADR 0049). */
    @Volatile
    internal var pushTarget: GitPushTarget? = GitPushTarget("origin", "main", hasUpstream = true)

    /** What the source says a push to a remote's branch would send. */
    @Volatile
    internal var outgoing: (remote: String, branch: String) -> GitOutgoing? = { _, _ -> null }

    /** Every read of what a push would send, as `<remote> <branch>`, with the thread it ran on. */
    internal val outgoingReads = CopyOnWriteArrayList<String>()
    internal val outgoingThreads = CopyOnWriteArrayList<String>()

    /** The files a commit changed against a parent, as the source reads them. */
    @Volatile
    internal var changedFilesOf: (commit: String, parent: String?) -> List<GitChangedFile>? = { _, _ -> emptyList() }

    /** Every read of the changed files, as `<commit> <parent or ->`. */
    internal val changedFilesReads = CopyOnWriteArrayList<String>()

    /** The diff of a commit's file the source reads, and every read as `<commit> <parent or -> <path>`. */
    @Volatile
    internal var commitFileDiff: GitFileDiff? = GitFileDiff.Text("old\n", "new\n", emptyList())
    internal val fileDiffLoads = CopyOnWriteArrayList<String>()

    /** The skip of every page load. */
    internal val loads = CopyOnWriteArrayList<Int>()

    internal val writes = FakeWrites()

    /** The answers of [GitLogView.beforeFileChange], one per call; `true` once they are used up. */
    internal val beforeFileChange = ArrayDeque<Boolean>()

    /** The actions [GitLogView.beforeFileChange] was asked about. */
    internal val fileChanges = mutableListOf<String>()
    internal val beforeCheckoutCalls get() = fileChanges.count { it == "Check Out" }

    internal lateinit var view: GitLogView

    /** A source of a test's own instead of the fixture's, `null` for the fixture's. */
    internal open fun ownSource(): GitLogSource? = null

    internal fun startView(stage: Stage) {
        val source =
            ownSource() ?: object : GitLogSource {
                override fun page(
                    root: Path,
                    filter: GitLogFilter,
                    skip: Int,
                    limit: Int,
                    cancellation: GitCancellation?,
                ): GitLogPage {
                    loads += skip
                    return GitLogPage(commits.drop(skip).take(limit), complete = true)
                }

                override fun hashMatch(
                    root: Path,
                    filter: GitLogFilter,
                    cancellation: GitCancellation?,
                ): GitCommit? = null

                override fun userName(root: Path): String? = null

                override fun paths(root: Path): List<GitTreePath> = emptyList()

                override fun refs(
                    root: Path,
                    cancellation: GitCancellation?,
                ) = currentRefs

                override fun pending(
                    root: Path,
                    cancellation: GitCancellation?,
                ) = pending

                override fun remotes(
                    root: Path,
                    cancellation: GitCancellation?,
                ) = currentRemotes

                override fun details(
                    root: Path,
                    hash: String,
                    cancellation: GitCancellation?,
                ): GitCommitDetails? = null

                override fun changedFiles(
                    root: Path,
                    commit: String,
                    firstParent: String?,
                    cancellation: GitCancellation?,
                ): List<GitChangedFile>? {
                    changedFilesReads += "$commit ${firstParent ?: "-"}"
                    return changedFilesOf(commit, firstParent)
                }

                override fun fileDiff(
                    root: Path,
                    commit: String,
                    firstParent: String?,
                    file: GitChangedFile,
                    cancellation: GitCancellation?,
                ): GitFileDiff? {
                    fileDiffLoads += "$commit ${firstParent ?: "-"} ${file.path}"
                    return commitFileDiff
                }

                override fun pushTarget(
                    root: Path,
                    branch: String,
                    cancellation: GitCancellation?,
                ) = pushTarget

                override fun outgoing(
                    root: Path,
                    remote: String,
                    branch: String,
                    limit: Int,
                    cancellation: GitCancellation?,
                ): GitOutgoing? {
                    outgoingReads += "$remote $branch"
                    outgoingThreads += Thread.currentThread().name
                    return outgoing(remote, branch)
                }

                override fun workingTreeFileDiff(
                    root: Path,
                    commit: String,
                    file: GitChangedFile,
                ): GitFileDiff? {
                    workingTreeDiffLoads += "$commit ${file.change} ${file.path}"
                    workingTreeDiffThreads += Thread.currentThread().name
                    return workingTreeDiff
                }

                override fun commitChanges(root: Path): GitCommitChanges? {
                    commitReads += Thread.currentThread().name
                    return commitChanges
                }

                override fun headCommit(
                    root: Path,
                    cancellation: GitCancellation?,
                ) = headCommit

                override fun headMessage(root: Path): String? {
                    headMessageReads += Thread.currentThread().name
                    headMessageGate?.await(10, TimeUnit.SECONDS)
                    return headMessage
                }
            }
        view = GitLogView(source, Platform::runLater, writes, BackgroundTasks.forTests())
        view.beforeFileChange = { action ->
            fileChanges += action
            beforeFileChange.removeFirstOrNull() ?: true
        }
        stage.scene = Scene(view, 900.0, 500.0)
        stage.show()
    }

    @AfterEach
    fun close() {
        writes.gate?.countDown()
        headMessageGate?.countDown()
        // A dialog left open by a failed test would block the next one.
        repeat(3) {
            val pane = shownDialog() ?: return@repeat
            Platform.runLater {
                (
                    pane.lookupButton(
                        ButtonType.CANCEL,
                    ) ?: pane.lookupButton(ButtonType.OK)
                )?.let { (it as Button).fire() }
            }
            WaitForAsyncUtils.waitForFxEvents()
        }
        view.close()
    }

    internal fun openAndExpand(robot: FxRobot) {
        robot.interact {
            view.open(repo)
            view.expanded.set(true)
        }
        eventually { assertTrue(view.table.items.isNotEmpty()) }
        eventually {
            assertTrue(
                view.branchesPane.tree.root.children
                    .isNotEmpty(),
            )
        }
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

    /** The pane of the dialog shown, if any. */
    internal fun shownDialog(): DialogPane? =
        WaitForAsyncUtils
            .asyncFx<DialogPane?> {
                javafx.stage.Window
                    .getWindows()
                    .filter { it.isShowing }
                    .firstNotNullOfOrNull { it.scene?.root as? DialogPane }
            }.get()

    /**
     * Asserts that the content of the question [pane] asks for no height, so there is no empty band between the
     * question and the buttons. Its preferred height, not its actual one: on Xvfb without a window manager the window
     * can come out a few pixels taller, and the dialog pane gives that spare height to its content.
     */
    internal fun assertNoBandBelowQuestion(pane: DialogPane) {
        assertEquals(0.0, (pane.content as Region).prefHeight(-1.0), "no empty band below the question")
    }

    /** Waits for a dialog (a window of its own, found with [FxRobot.listWindows]) and returns its pane. */
    internal fun awaitDialog(robot: FxRobot): DialogPane {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            val pane =
                WaitForAsyncUtils
                    .asyncFx<DialogPane?> {
                        robot
                            .listWindows()
                            .filter {
                                it.isShowing
                            }.firstNotNullOfOrNull { it.scene?.root as? DialogPane }
                    }.get()
            if (pane != null) return pane
            check(System.nanoTime() < deadline) { "no dialog shown" }
            Thread.sleep(20)
        }
    }

    internal fun assertNoDialog() {
        WaitForAsyncUtils.waitForFxEvents()
        assertNull(shownDialog())
    }

    /** Fires [item] later, as a click in its menu would: its dialog blocks the JavaFX thread until it is answered. */
    internal fun fireLater(item: MenuItem) {
        Platform.runLater { item.fire() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Presses [button] of the dialog [pane], which closes it. */
    internal fun press(
        robot: FxRobot,
        pane: DialogPane,
        button: ButtonType,
    ) {
        robot.interact { (pane.lookupButton(button) as Button).fire() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    internal fun status(robot: FxRobot) = robot.lookup(".git-log-status").queryAs(Label::class.java).text

    /** What the table says in place of rows. */
    internal fun placeholder(robot: FxRobot) =
        robot.lookup(".git-log-table .placeholder .label").queryAs(Label::class.java).text

    /** The subjects of the rows the table shows, top down. */
    internal fun shownSubjects(robot: FxRobot): List<String> =
        robot
            .lookup(".git-log-table .table-row-cell")
            .queryAllAs(TableRow::class.java)
            .filter { !it.isEmpty && it.item != null }
            .sortedBy { it.index }
            .map { (it.item as GitLogRow).commit.subject }

    internal fun selectRows(
        robot: FxRobot,
        vararg indices: Int,
    ) {
        robot.interact {
            view.table.selectionModel.clearSelection()
            indices.forEach(view.table.selectionModel::select)
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    internal val branches get() = view.branchesPane

    internal fun branchItem(path: String): TreeItem<BranchNode> {
        fun find(item: TreeItem<BranchNode>): TreeItem<BranchNode>? =
            if (item.value?.path == path) item else item.children.firstNotNullOfOrNull(::find)
        return checkNotNull(find(branches.tree.root)) { path }
    }

    internal fun selectBranches(
        robot: FxRobot,
        vararg paths: String,
    ) {
        robot.interact {
            paths.forEach { branchItem(it).parent?.isExpanded = true }
            val rows = paths.map { branches.tree.getRow(branchItem(it)) }
            branches.tree.selectionModel.clearSelection()
            branches.tree.selectionModel.selectIndices(rows.first(), *rows.drop(1).toIntArray())
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Which of Checkout, Rebase, New Branch, Rename and Delete of the branches pane are enabled, as `C B N R D` or `-`. */
    internal fun paneActions(): String =
        WaitForAsyncUtils
            .asyncFx<String> {
                listOf(
                    branches.checkoutItem to "C",
                    branches.rebaseItem to "B",
                    branches.newBranchItem to "N",
                    branches.renameItem to "R",
                    branches.deleteItem to "D",
                ).joinToString(" ") { (item, letter) -> if (item.isDisable) "-" else letter }
            }.get()

    /** Presses [code] in the focused tree, as typed: to the focus owner, with the tree's own key handling. */
    internal fun pressInTree(
        code: KeyCode,
        shortcut: Boolean = false,
    ) {
        Platform.runLater {
            branches.tree.requestFocus()
            val target = branches.tree.scene.focusOwner
            // Control and Meta: the shortcut on any platform.
            Event.fireEvent(target, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut, false, shortcut))
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Whether the header's Cancel button shows. */
    internal fun cancelShown() = WaitForAsyncUtils.asyncFx<Boolean> { view.cancelButton.isVisible }.get()
}
