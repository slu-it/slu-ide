package dev.sluide.gitlog

import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitTreePath
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.testfx.api.FxRobot
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The git log's operations that change the repository (ADR 0041), with fake reads and writes, shared by the `GitLogWrites*UiTest` classes. Needs a display. */
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

    /** The cherry-pick or revert in progress the source reads. */
    @Volatile
    internal var pendingPick: GitInProgress? = null

    /** The skip of every page load. */
    internal val loads = CopyOnWriteArrayList<Int>()

    internal val writes = FakeWrites()

    /** The answers of [GitLogView.beforeFileChange], one per call; `true` once they are used up. */
    internal val beforeFileChange = ArrayDeque<Boolean>()

    /** The actions [GitLogView.beforeFileChange] was asked about. */
    internal val fileChanges = mutableListOf<String>()
    internal val beforeCheckoutCalls get() = fileChanges.count { it == "Check Out" }

    internal lateinit var view: GitLogView

    internal fun startView(stage: Stage) {
        val source =
            object : GitLogSource {
                override fun page(
                    root: Path,
                    filter: GitLogFilter,
                    skip: Int,
                    limit: Int,
                ): GitLogPage {
                    loads += skip
                    return GitLogPage(commits.drop(skip).take(limit), complete = true)
                }

                override fun hashMatch(
                    root: Path,
                    filter: GitLogFilter,
                ): GitCommit? = null

                override fun userName(root: Path): String? = null

                override fun paths(root: Path): List<GitTreePath> = emptyList()

                override fun refs(root: Path) = currentRefs

                override fun pending(root: Path) = pendingPick

                override fun remotes(root: Path) = currentRemotes

                override fun details(
                    root: Path,
                    hash: String,
                ): GitCommitDetails? = null

                override fun changedFiles(
                    root: Path,
                    commit: String,
                    firstParent: String?,
                ): List<GitChangedFile> = emptyList()

                override fun fileDiff(
                    root: Path,
                    commit: String,
                    firstParent: String?,
                    file: GitChangedFile,
                ): GitFileDiff? = null
            }
        view = GitLogView(source, Platform::runLater, writes)
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

    /** Which of Checkout, New Branch, Rename and Delete of the branches pane are enabled, as `C N R D` or `-`. */
    internal fun paneActions(): String =
        WaitForAsyncUtils
            .asyncFx<String> {
                listOf(
                    branches.checkoutItem to "C",
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
