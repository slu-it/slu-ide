package dev.sluide.gitlog

import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitDetails
import dev.sluide.git.GitConflictReason
import dev.sluide.git.GitFetchChanges
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitOperation
import dev.sluide.git.GitPendingOperation
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPick
import dev.sluide.git.GitPickResult
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitResult
import dev.sluide.git.GitTreePath
import dev.sluide.git.GitUncheckedConflict
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.RadioButton
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Region
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The git log's operations that change the repository (ADR 0041), with fake reads and writes. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesUiTest {
    private val repo = Path.of("/repo")

    /** A line of ten commits; h6 also merges h9. */
    private val commits =
        (1..10).map {
            val parents = listOf(it + 1, if (it == 6) 9 else 0).filter { parent -> parent in 2..10 }
            GitCommit("h$it", parents.map { parent -> "h$parent" }, "A", "a@x", Instant.EPOCH, "S $it")
        }

    /** HEAD and main on h1, two local branches in a folder, two remote branches and two tags. */
    private val refs =
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
    private var currentRefs = refs

    /** The remotes the source reads; `null` when they cannot be read. */
    @Volatile
    private var currentRemotes: List<String>? = listOf("origin")

    /** The cherry-pick or revert in progress the source reads. */
    @Volatile
    private var pendingPick: GitInProgress? = null

    /** The skip of every page load. */
    private val loads = CopyOnWriteArrayList<Int>()

    private val writes = FakeWrites()

    /** The answers of [GitLogView.beforeFileChange], one per call; `true` once they are used up. */
    private val beforeFileChange = ArrayDeque<Boolean>()

    /** The actions [GitLogView.beforeFileChange] was asked about. */
    private val fileChanges = mutableListOf<String>()
    private val beforeCheckoutCalls get() = fileChanges.count { it == "Check Out" }

    private lateinit var view: GitLogView

    @Start
    fun start(stage: Stage) {
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

    private fun openAndExpand(robot: FxRobot) {
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
    private fun eventually(assertion: () -> Unit) {
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
    private fun shownDialog(): DialogPane? =
        WaitForAsyncUtils
            .asyncFx<DialogPane?> {
                javafx.stage.Window
                    .getWindows()
                    .filter { it.isShowing }
                    .firstNotNullOfOrNull { it.scene?.root as? DialogPane }
            }.get()

    /** Waits for a dialog (a window of its own, found with [FxRobot.listWindows]) and returns its pane. */
    private fun awaitDialog(robot: FxRobot): DialogPane {
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

    private fun assertNoDialog() {
        WaitForAsyncUtils.waitForFxEvents()
        assertNull(shownDialog())
    }

    /** Fires [item] later, as a click in its menu would: its dialog blocks the JavaFX thread until it is answered. */
    private fun fireLater(item: MenuItem) {
        Platform.runLater { item.fire() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** Presses [button] of the dialog [pane], which closes it. */
    private fun press(
        robot: FxRobot,
        pane: DialogPane,
        button: ButtonType,
    ) {
        robot.interact { (pane.lookupButton(button) as Button).fire() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun status(robot: FxRobot) = robot.lookup(".git-log-status").queryAs(Label::class.java).text

    private fun selectRows(
        robot: FxRobot,
        vararg indices: Int,
    ) {
        robot.interact {
            view.table.selectionModel.clearSelection()
            indices.forEach(view.table.selectionModel::select)
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private val branches get() = view.branchesPane

    private fun branchItem(path: String): TreeItem<BranchNode> {
        fun find(item: TreeItem<BranchNode>): TreeItem<BranchNode>? =
            if (item.value?.path == path) item else item.children.firstNotNullOfOrNull(::find)
        return checkNotNull(find(branches.tree.root)) { path }
    }

    private fun selectBranches(
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
    private fun paneActions(): String =
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
    private fun pressInTree(
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

    @Test
    fun `the table's write actions need exactly one commit and check out a revision on the write thread`(
        robot: FxRobot,
    ) {
        openAndExpand(robot)
        val items =
            listOf(
                view.checkoutRevisionItem,
                view.newBranchItem,
                view.newTagItem,
                view.cherryPickItem,
                view.revertItem,
                view.resetItem,
            )
        assertEquals(
            listOf(
                "Copy Revision Number",
                "Compare with Local",
                null,
                "Checkout Revision",
                "New Branch\u2026",
                "New Tag\u2026",
                null,
                "Cherry-Pick",
                "Revert Commit",
                "Reset Current Branch to Here\u2026",
            ),
            view.table.contextMenu.items
                .map { it.text },
        )

        selectRows(robot)
        assertTrue(items.all { it.isDisable })
        selectRows(robot, 1, 2)
        assertTrue(items.all { it.isDisable })
        selectRows(robot, 1)
        assertTrue(items.none { it.isDisable })

        val loadsBefore = loads.size
        fireLater(view.checkoutRevisionItem)

        eventually { assertEquals("Checked out h2.", status(robot)) }
        assertEquals(listOf("switch --detach h2"), writes.calls)
        assertEquals(listOf("sluide-git-write"), writes.threads)
        assertEquals(1, beforeCheckoutCalls)
        eventually { assertTrue(loads.size > loadsBefore, "refreshed") }
        assertNoDialog()
    }

    @Test
    fun `while an operation runs the header says so and every write action is disabled`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 2)
        writes.gate = CountDownLatch(1)

        fireLater(view.checkoutRevisionItem)

        eventually { assertEquals("Checking out h3\u2026", status(robot)) }
        assertTrue(view.checkoutRevisionItem.isDisable)
        assertTrue(view.newBranchItem.isDisable)
        assertTrue(view.newTagItem.isDisable)
        assertTrue(view.cherryPickItem.isDisable)
        assertTrue(view.revertItem.isDisable)
        assertTrue(view.resetItem.isDisable)
        selectBranches(robot, "Local/feature/a")
        assertEquals("- - - -", paneActions())
        assertTrue(branches.copyNameItem.isDisable.not(), "reading actions stay")
        // Keys do nothing either.
        pressInTree(KeyCode.F2)
        pressInTree(KeyCode.DELETE)
        pressInTree(KeyCode.BACK_SPACE, shortcut = true)
        assertNoDialog()

        writes.gate?.countDown()

        eventually { assertEquals("Checked out h3.", status(robot)) }
        assertEquals("C N R D", paneActions())
        selectRows(robot, 2)
        assertFalse(view.checkoutRevisionItem.isDisable)
    }

    @Test
    fun `a cancelled unsaved-editor question aborts a checkout`(robot: FxRobot) {
        openAndExpand(robot)
        selectBranches(robot, "Local/feature/a")
        beforeFileChange += false

        fireLater(branches.checkoutItem)

        assertEquals(1, beforeCheckoutCalls)
        assertEquals(emptyList(), writes.calls)
        assertEquals("", status(robot))

        fireLater(branches.checkoutItem)
        eventually { assertEquals(listOf("switch feature/a"), writes.calls) }
        assertEquals(2, beforeCheckoutCalls)
        eventually { assertEquals("Checked out feature/a.", status(robot)) }
    }

    @Test
    fun `a checkout done although git reported an error says both`(robot: FxRobot) {
        openAndExpand(robot)
        writes.result = { GitResult(false, "hook failed", changed = true) }
        selectBranches(robot, "Local/feature/a")

        fireLater(branches.checkoutItem)

        val pane = awaitDialog(robot)
        assertEquals("Checked out feature/a, but git reported an error.", pane.headerText)
        assertEquals("hook failed", (pane.lookup(".git-message") as TextArea).text)
        assertEquals("Checked out feature/a, but git reported an error.", status(robot))
        press(robot, pane, ButtonType.OK)
    }

    @Test
    fun `the branches pane offers its actions as the selection allows`(robot: FxRobot) {
        openAndExpand(robot)
        assertEquals(
            listOf(
                "Filter Log by Branch",
                null,
                "Copy Name",
                null,
                "Compare with Current",
                "Show Diff with Working Tree",
                null,
                "Checkout",
                "New Branch\u2026",
                "Rename\u2026",
                "Delete",
                null,
                "Fetch",
            ),
            branches.tree.contextMenu.items
                .map { it.text },
        )

        selectBranches(robot, "Local/feature/a")
        assertEquals("C N R D", paneActions())
        assertEquals("New Branch from 'feature/a'\u2026", branches.newBranchItem.text)
        selectBranches(robot, "Local/main")
        assertEquals("- N R -", paneActions(), "the current branch")
        selectBranches(robot, "HEAD")
        assertEquals("- N - -", paneActions())
        assertEquals("New Branch from 'HEAD'\u2026", branches.newBranchItem.text)
        selectBranches(robot, "Remote/origin/old")
        assertEquals("C N - -", paneActions())
        selectBranches(robot, "Remote/origin/main")
        assertEquals("- N - -", paneActions(), "its local branch is the current one")
        selectBranches(robot, "Tags/v0.9")
        assertEquals("C N - D", paneActions())
        selectBranches(robot, "Local/feature")
        assertEquals("- - - -", paneActions(), "a folder")
        assertEquals("New Branch\u2026", branches.newBranchItem.text)
        selectBranches(robot, "Local")
        assertEquals("- - - -", paneActions(), "a group")
        selectBranches(robot, "Local/feature/a", "Tags/v0.9")
        assertEquals("- - - D", paneActions())
        selectBranches(robot, "Local/feature/a", "Local/main")
        assertEquals("- - - -", paneActions())
        selectBranches(robot, "Local/feature/a", "Remote/origin/old")
        assertEquals("- - - -", paneActions())
    }

    /** Whether the header's Cancel button shows. */
    private fun cancelShown() = WaitForAsyncUtils.asyncFx<Boolean> { view.cancelButton.isVisible }.get()

    @Test
    fun `Fetch in the header fetches all remotes with Cancel shown meanwhile, then says what changed`(robot: FxRobot) {
        currentRemotes = listOf("origin", "fork")
        writes.remotes = listOf("origin", "fork")
        openAndExpand(robot)
        assertEquals("Fetch All Remotes", view.fetchButton.tooltip.text)
        assertFalse(view.fetchButton.isDisable)
        assertFalse(cancelShown())
        writes.gate = CountDownLatch(1)
        writes.fetchResult = { remote ->
            GitFetchResult(
                GitResult(true, ""),
                changes =
                    GitFetchChanges(
                        newBranches =
                            if (remote ==
                                "origin"
                            ) {
                                1
                            } else {
                                0
                            },
                    ),
            )
        }
        val loadsBefore = loads.size

        robot.interact { view.fetchButton.fire() }

        eventually { assertEquals("Fetching origin\u2026", status(robot)) }
        assertTrue(cancelShown())
        assertTrue(view.fetchButton.isDisable)
        selectBranches(robot, "Remote/origin")
        assertTrue(branches.fetchItem.isDisable, "no other operation meanwhile")
        selectRows(robot, 1)
        assertTrue(view.checkoutRevisionItem.isDisable)

        writes.gate?.countDown()

        eventually { assertEquals("Fetched 2 remotes: 1 new branch.", status(robot)) }
        assertEquals(listOf("fetch origin", "fetch fork"), writes.calls)
        assertEquals(listOf("sluide-git-write", "sluide-git-write"), writes.threads)
        assertFalse(cancelShown())
        assertFalse(view.fetchButton.isDisable)
        assertEquals(emptyList(), fileChanges, "no unsaved-editor question")
        eventually { assertTrue(loads.size > loadsBefore, "refreshed") }
        assertNoDialog()
    }

    @Test
    fun `Cancel stops the fetch and skips the other remotes without an error`(robot: FxRobot) {
        currentRemotes = listOf("origin", "fork")
        writes.remotes = listOf("origin", "fork")
        openAndExpand(robot)
        writes.gate = CountDownLatch(1)
        val loadsBefore = loads.size

        robot.interact { view.fetchButton.fire() }
        eventually { assertTrue(cancelShown()) }
        robot.interact { view.cancelButton.fire() }

        eventually { assertEquals("Fetch cancelled.", status(robot)) }
        assertEquals(listOf("fetch origin"), writes.calls)
        assertFalse(cancelShown())
        assertFalse(view.fetchButton.isDisable)
        eventually { assertTrue(loads.size > loadsBefore, "refreshed") }
        assertNoDialog()
    }

    @Test
    fun `Fetch shows in the collapsed header and is disabled once the log found no remotes`(robot: FxRobot) {
        currentRemotes = emptyList()
        robot.interact { view.open(repo) }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(view.fetchButton.isVisible)
        assertFalse(view.fetchButton.isDisable, "not known before the log is read")

        openAndExpand(robot)
        eventually { assertTrue(view.fetchButton.isDisable) }

        currentRemotes = listOf("origin")
        robot.interact { view.refresh() }
        eventually { assertFalse(view.fetchButton.isDisable) }
        robot.interact { view.expanded.set(false) }
        robot.interact { view.fetchButton.fire() }
        eventually { assertEquals("Fetched origin: nothing new.", status(robot)) }
        assertEquals(listOf("fetch origin"), writes.calls)
        assertFalse(view.expanded.get(), "the button does not expand the log")
    }

    @Test
    fun `a remote that fails is shown in an error dialog while the header tells what was fetched`(robot: FxRobot) {
        writes.remotes = listOf("origin", "gone")
        openAndExpand(robot)
        writes.fetchResult = { remote ->
            if (remote == "gone") {
                GitFetchResult(GitResult(false, "fatal: 'gone' does not appear to be a git repository"))
            } else {
                GitFetchResult(GitResult(true, ""), changes = GitFetchChanges(updatedBranches = 2))
            }
        }

        robot.interact { view.fetchButton.fire() }

        val pane = awaitDialog(robot)
        assertEquals("Fetch Failed", (pane.scene.window as Stage).title)
        assertEquals("gone could not be fetched.", pane.headerText)
        assertEquals(
            "fatal: 'gone' does not appear to be a git repository",
            (pane.lookup(".git-message") as TextArea).text,
        )
        assertEquals("Fetched origin: 2 branches updated.", status(robot))
        press(robot, pane, ButtonType.OK)
    }

    @Test
    fun `Fetch in the branches pane fetches only the selected remote, if git has it`(robot: FxRobot) {
        // origin is left out of fetching all remotes, but fetched when asked for, as by git fetch origin.
        writes.remotes = listOf("fork")
        currentRemotes = listOf("origin", "fork")
        openAndExpand(robot)

        selectBranches(robot, "Local/main")
        assertTrue(branches.fetchItem.isDisable)
        selectBranches(robot, "Remote/origin")
        assertFalse(branches.fetchItem.isDisable)

        fireLater(branches.fetchItem)

        eventually { assertEquals("Fetched origin: nothing new.", status(robot)) }
        assertEquals(listOf("fetch origin"), writes.calls)

        selectBranches(robot, "Remote/origin/old")
        fireLater(branches.fetchItem)
        eventually { assertEquals(listOf("fetch origin", "fetch origin"), writes.calls) }

        // The remote was removed; its remote branches are still there.
        currentRemotes = listOf("fork")
        robot.interact { view.refresh() }
        eventually { assertTrue(branches.fetchItem.isDisable) }
        selectBranches(robot, "Remote/origin")
        assertTrue(branches.fetchItem.isDisable)
    }

    @Test
    fun `checks out a remote branch as a new tracking branch or its local branch, and a tag detached`(robot: FxRobot) {
        openAndExpand(robot)

        selectBranches(robot, "Remote/origin/old")
        fireLater(branches.checkoutItem)
        eventually { assertEquals("Checked out new branch old tracking origin/old.", status(robot)) }

        writes.localBranches = setOf("old")
        fireLater(branches.checkoutItem)
        eventually { assertEquals("Checked out local branch old.", status(robot)) }

        selectBranches(robot, "Tags/v0.9")
        fireLater(branches.checkoutItem)
        eventually { assertEquals("Checked out v0.9.", status(robot)) }

        assertEquals(
            listOf("switch --track refs/remotes/origin/old", "switch old", "switch --detach refs/tags/v0.9"),
            writes.calls,
        )
        assertEquals(3, beforeCheckoutCalls)
    }

    @Test
    fun `the new branch dialog checks the name and creates the branch at the commit, checked out or not`(
        robot: FxRobot,
    ) {
        openAndExpand(robot)
        selectRows(robot, 1)
        writes.unpassable = setOf("Gr\u00fc\u00dfe")

        fireLater(view.newBranchItem)
        var pane = awaitDialog(robot)
        val dialog = NameControls(pane)
        assertEquals("New Branch", (pane.scene.window as Stage).title)
        val create = pane.lookupButton(CREATE_BUTTON)
        val checkout = checkNotNull(dialog.checkout)
        val overwrite = checkNotNull(dialog.overwrite)
        assertTrue(checkout.isSelected)
        assertFalse(overwrite.isSelected)
        assertTrue(create.isDisable)
        assertEquals("Enter a name.", dialog.problem.text)

        fun type(text: String) {
            robot.interact { dialog.field.text = text }
        }
        type("feature/b")
        assertEquals("Branch 'feature/b' already exists", dialog.problem.text)
        assertTrue(create.isDisable)
        robot.interact { overwrite.isSelected = true }
        assertEquals("Moves 'feature/b' from h5.", dialog.problem.text, "a note, no problem")
        assertFalse(create.isDisable)
        robot.interact { overwrite.isSelected = false }
        type("a..b")
        assertEquals("'a..b' is not a valid branch name.", dialog.problem.text)
        type("Gr\u00fc\u00dfe")
        assertEquals("This name cannot be passed to git in this locale.", dialog.problem.text)
        type("x")
        assertFalse(create.isDisable)
        robot.interact { checkout.isSelected = false }
        press(robot, pane, CREATE_BUTTON)

        eventually { assertEquals("Created branch x.", status(robot)) }
        assertEquals(listOf("branch x h2"), writes.calls)
        assertEquals(0, beforeCheckoutCalls, "no checkout")

        fireLater(view.newBranchItem)
        pane = awaitDialog(robot)
        robot.interact { NameControls(pane).field.text = "y" }
        press(robot, pane, CREATE_BUTTON)
        eventually { assertEquals(listOf("branch x h2", "switch -c y h2"), writes.calls) }
        eventually { assertEquals("Created and checked out branch y.", status(robot)) }
        assertEquals(1, beforeCheckoutCalls)

        // From a branch of the pane, its full refname is the start point. The current branch is never overwritten;
        // another one is moved, once confirmed if it has commits that are not on the start point.
        writes.tips = mapOf("feature/a" to "h3")
        writes.ancestry = { _, _ -> false }
        selectBranches(robot, "Remote/origin/old")
        fireLater(branches.newBranchItem)
        pane = awaitDialog(robot)
        val overwriting = NameControls(pane)
        robot.interact {
            overwriting.field.text = "main"
            overwriting.overwrite?.isSelected = true
        }
        assertEquals("Branch 'main' is checked out.", overwriting.problem.text)
        assertTrue(pane.lookupButton(CREATE_BUTTON).isDisable)
        robot.interact { overwriting.checkout?.isSelected = false }
        assertEquals("Branch 'main' is checked out.", overwriting.problem.text, "whatever Checkout says")
        robot.interact { overwriting.field.text = "feature/a" }
        assertEquals("Moves 'feature/a' from h3.", overwriting.problem.text)
        assertFalse(pane.lookupButton(CREATE_BUTTON).isDisable)
        press(robot, pane, CREATE_BUTTON)
        val question = awaitDialog(robot)
        assertEquals("Overwrite Branch", (question.scene.window as Stage).title)
        assertEquals(
            "'feature/a' has commits that are not on origin/old; they will only be reachable through the reflog. " +
                "Overwrite?",
            question.headerText,
        )
        assertEquals(0.0, (question.content as Region).height, "no empty band below the question")
        assertTrue((question.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        assertEquals(2, writes.calls.size, "not moved before it is confirmed")
        press(robot, question, OVERWRITE_BUTTON)
        eventually { assertEquals("branch -f feature/a refs/remotes/origin/old", writes.calls.last()) }

        // Cancel creates nothing.
        fireLater(view.newBranchItem)
        pane = awaitDialog(robot)
        robot.interact { NameControls(pane).field.text = "z" }
        press(robot, pane, ButtonType.CANCEL)
        assertEquals(3, writes.calls.size)
    }

    /** The controls of a name dialog's [pane], by their style classes. */
    private class NameControls(
        pane: DialogPane,
    ) {
        val field = pane.lookup(".git-ref-name") as TextField
        val problem = pane.lookup(".git-ref-name-problem") as Label
        val checkout = pane.lookup(".git-ref-checkout") as CheckBox?
        val overwrite = pane.lookup(".git-ref-overwrite") as CheckBox?
        val message = pane.lookup(".git-ref-message") as TextArea?
    }

    @Test
    fun `the new tag dialog creates a lightweight tag without a message and an annotated one with it`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 0)

        fireLater(view.newTagItem)
        var pane = awaitDialog(robot)
        val dialog = NameControls(pane)
        assertEquals("New Tag", (pane.scene.window as Stage).title)
        robot.interact { dialog.field.text = "v1.0" }
        assertEquals("Tag 'v1.0' already exists", dialog.problem.text)
        assertTrue(pane.lookupButton(CREATE_BUTTON).isDisable)
        robot.interact { dialog.field.text = "v2" }
        press(robot, pane, CREATE_BUTTON)
        eventually { assertEquals("Created tag v2.", status(robot)) }

        fireLater(view.newTagItem)
        pane = awaitDialog(robot)
        robot.interact {
            NameControls(pane).field.text = "v3"
            NameControls(pane).message?.text = "Gr\u00fc\u00dfe\n\nzweite Zeile"
        }
        press(robot, pane, CREATE_BUTTON)
        eventually { assertEquals("Created tag v3.", status(robot)) }

        assertEquals(listOf("tag v2 h1", "tag -a v3 h1: Gr\u00fc\u00dfe\n\nzweite Zeile"), writes.calls)
        assertEquals(0, beforeCheckoutCalls)
    }

    @Test
    fun `F2 renames a local branch, the old name prefilled and selected`(robot: FxRobot) {
        openAndExpand(robot)
        selectBranches(robot, "Local/feature/a")

        pressInTree(KeyCode.F2)
        val pane = awaitDialog(robot)
        val dialog = NameControls(pane)
        val rename = pane.lookupButton(RENAME_BUTTON)
        assertEquals("Rename Branch", (pane.scene.window as Stage).title)
        eventually { assertEquals("feature/a", dialog.field.selectedText) }
        assertTrue(rename.isDisable, "the same name")
        assertEquals("", dialog.problem.text)
        robot.interact { dialog.field.text = "feature/b" }
        assertEquals("Branch 'feature/b' already exists", dialog.problem.text)
        robot.interact { dialog.field.text = "feature/c" }
        assertFalse(rename.isDisable)
        press(robot, pane, RENAME_BUTTON)

        eventually { assertEquals("Renamed branch feature/a to feature/c.", status(robot)) }
        assertEquals(listOf("branch -m feature/a feature/c"), writes.calls)

        // Not for other refs.
        selectBranches(robot, "Tags/v0.9")
        pressInTree(KeyCode.F2)
        assertNoDialog()
    }

    @Test
    fun `Delete asks first, then deletes branches and tags`(robot: FxRobot) {
        openAndExpand(robot)
        selectBranches(robot, "Local/feature/a", "Local/feature/b", "Tags/v0.9")

        pressInTree(KeyCode.DELETE)
        var pane = awaitDialog(robot)
        assertEquals("Delete", (pane.scene.window as Stage).title)
        assertEquals("Delete 2 branches and 1 tag?", pane.headerText)
        val names = pane.lookup(".git-delete-names") as TextArea
        assertEquals("branch feature/a\nbranch feature/b\ntag v0.9", names.text)
        assertFalse(names.isEditable)
        assertTrue((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, pane, ButtonType.CANCEL)
        assertEquals(emptyList(), writes.calls)
        // Shortcut+Backspace too, as in the file explorer (Mac keyboards often have no Delete key).
        pressInTree(KeyCode.BACK_SPACE, shortcut = true)
        press(robot, awaitDialog(robot), ButtonType.CANCEL)
        pressInTree(KeyCode.BACK_SPACE)
        assertNoDialog()
        assertEquals(emptyList(), writes.calls)

        fireLater(branches.deleteItem)
        pane = awaitDialog(robot)
        press(robot, pane, DELETE_BUTTON)

        eventually { assertEquals("Deleted 3 refs.", status(robot)) }
        assertEquals(listOf("branch -d feature/a feature/b", "tag -d v0.9"), writes.calls)
        assertEquals(0, beforeCheckoutCalls)

        // Not the current branch.
        selectBranches(robot, "Local/main")
        pressInTree(KeyCode.DELETE)
        assertNoDialog()
    }

    @Test
    fun `a branch that is not fully merged is deleted with force after a second confirmation`(robot: FxRobot) {
        openAndExpand(robot)
        val refusal = "error: the branch 'feature/a' is not fully merged\nhint: If you are sure \u2026"
        writes.result = { line -> if (line.startsWith("branch -d")) GitResult(false, refusal) else GitResult(true, "") }
        writes.notDeleted = { listOf("feature/a") }
        selectBranches(robot, "Local/feature/a", "Local/feature/b")

        fireLater(branches.deleteItem)
        press(robot, awaitDialog(robot), DELETE_BUTTON)

        val pane = awaitDialog(robot)
        assertEquals("Force delete branch 'feature/a'?", pane.headerText)
        val message = pane.lookup(".git-message") as TextArea
        assertEquals(refusal, message.text)
        assertTrue((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        assertEquals("Deleted branch feature/b.", status(robot))
        press(robot, pane, FORCE_DELETE_BUTTON)

        eventually { assertEquals(listOf("branch -d feature/a feature/b", "branch -D feature/a"), writes.calls) }
        eventually { assertEquals("Deleted branch feature/a.", status(robot)) }
        assertNoDialog()
    }

    @Test
    fun `a failure shows git's message in an error dialog and refreshes the log`(robot: FxRobot) {
        openAndExpand(robot)
        val message =
            "error: Your local changes to the following files would be overwritten by checkout:\n\tfile.txt\n" +
                "Please commit your changes or stash them before you switch branches.\nAborting"
        writes.result = { GitResult(false, message) }
        selectBranches(robot, "Local/feature/a")
        val loadsBefore = loads.size

        fireLater(branches.checkoutItem)

        val pane = awaitDialog(robot)
        assertEquals("Checkout Failed", (pane.scene.window as Stage).title)
        assertEquals("feature/a could not be checked out.", pane.headerText)
        val area = pane.lookup(".git-message") as TextArea
        assertEquals(message, area.text)
        assertFalse(area.isEditable)
        assertTrue(area.isWrapText)
        assertEquals(82, area.prefColumnCount, "as wide as the longest line")
        assertEquals(4, area.prefRowCount, "as high as its lines")
        assertTrue("git-message" in area.styleClass)
        assertTrue((pane.scene.window as Stage).owner === view.scene.window)
        assertTrue("error" in pane.styleClass, "an error alert")
        eventually { assertTrue(loads.size > loadsBefore, "refreshed") }
        assertEquals("", status(robot))
        press(robot, pane, ButtonType.OK)
        assertNoDialog()
    }

    @Test
    fun `git's message wraps long lines and scrolls beyond a dozen rows`() {
        val long = "x".repeat(150)
        val wrapped = WaitForAsyncUtils.asyncFx<TextArea> { gitMessage("$long\nshort") }.get()
        assertEquals(100, wrapped.prefColumnCount)
        assertEquals(4, wrapped.prefRowCount, "two rows for the long line, one for the short, one to spare")
        val many = WaitForAsyncUtils.asyncFx<TextArea> { gitMessage((1..30).joinToString("\n") { "line $it" }) }.get()
        assertEquals(12, many.prefRowCount)
        assertEquals(40, many.prefColumnCount)
    }

    @Test
    fun `closing the view during an operation drops its outcome`(robot: FxRobot) {
        openAndExpand(robot)
        writes.gate = CountDownLatch(1)
        writes.result = { GitResult(false, "error: interrupted") }
        selectBranches(robot, "Local/feature/a")

        fireLater(branches.checkoutItem)
        eventually { assertEquals("Checking out feature/a\u2026", status(robot)) }
        robot.interact { view.close() }
        writes.gate?.countDown()
        eventually { assertNotNull(writes.calls.singleOrNull()) }
        eventually { assertFalse(branches.writing.get()) }

        assertEquals(0, writes.interruptions, "git is not stopped halfway")
        assertNoDialog()
    }

    @Test
    fun `cherry-picks and reverts the selected commit after asking about unsaved editors`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 1)
        val loadsBefore = loads.size

        fireLater(view.cherryPickItem)
        eventually { assertEquals("Cherry-picked h2 as 5e6f7a8b.", status(robot)) }
        eventually { assertTrue(loads.size > loadsBefore, "refreshed") }

        fireLater(view.revertItem)
        eventually { assertEquals("Reverted h2 in 5e6f7a8b.", status(robot)) }

        assertEquals(listOf("cherry-pick h2", "revert h2"), writes.calls)
        assertEquals(listOf("sluide-git-write", "sluide-git-write"), writes.threads)
        assertEquals(listOf("Cherry-Pick", "Revert"), fileChanges)
        assertNoDialog()

        // Cancelled, nothing runs.
        beforeFileChange += false
        fireLater(view.cherryPickItem)
        assertEquals(2, writes.calls.size)
        assertEquals("Reverted h2 in 5e6f7a8b.", status(robot))

        // Nothing to commit is told in the header, not in a dialog.
        writes.pickResult = { GitPickResult.Empty("main") }
        fireLater(view.cherryPickItem)
        eventually {
            assertEquals("Nothing to cherry-pick: the changes of h2 are already on main.", status(robot))
        }
        assertNoDialog()
    }

    @Test
    fun `a merge commit is cherry-picked relative to its first parent once confirmed`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 5)

        fireLater(view.cherryPickItem)
        var pane = awaitDialog(robot)
        assertEquals("Cherry-Pick", (pane.scene.window as Stage).title)
        assertEquals(
            "h6 is a merge commit. Cherry-pick the changes it brought in relative to its first parent?",
            pane.headerText,
        )
        assertTrue((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        assertEquals(0.0, (pane.content as Region).height, "no empty band below the question")
        press(robot, pane, ButtonType.CANCEL)
        assertEquals(emptyList(), writes.calls)
        assertEquals(emptyList(), fileChanges, "not asked before the merge is confirmed")

        fireLater(view.revertItem)
        pane = awaitDialog(robot)
        assertEquals("Revert Commit", (pane.scene.window as Stage).title)
        press(robot, pane, REVERT_BUTTON)
        eventually { assertEquals(listOf("revert -m 1 h6"), writes.calls) }
        assertEquals(listOf("Revert"), fileChanges)
    }

    @Test
    fun `a stop with conflicts offers Resolve and Abort`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 2)
        writes.pickResult = { line ->
            if (line == "cherry-pick h3") {
                GitPickResult.Stopped(listOf("a.txt", "b/c.txt"), "error: could not apply h3")
            } else {
                GitPickResult.Done(FakeWrites.NEW_HEAD)
            }
        }

        fireLater(view.cherryPickItem)
        var pane = awaitDialog(robot)
        assertEquals("Cherry-Pick Stopped", (pane.scene.window as Stage).title)
        assertEquals("Cherry-pick of h3 stopped with conflicts in:", pane.headerText)
        assertEquals("a.txt\nb/c.txt", (pane.lookup(".git-conflict-paths") as TextArea).text)
        assertEquals("error: could not apply h3", (pane.expandableContent.lookup(".git-message") as TextArea).text)
        assertTrue((pane.lookupButton(RESOLVE_BUTTON) as Button).isDefaultButton)
        assertEquals("Abort Cherry-Pick", (pane.lookupButton(ABORT_CHERRY_PICK_BUTTON) as Button).text)
        assertEquals("Cherry-pick of h3 stopped with conflicts.", status(robot))
        press(robot, pane, RESOLVE_BUTTON)
        assertNoDialog()
        assertEquals(listOf("cherry-pick h3"), writes.calls)

        fireLater(view.cherryPickItem)
        pane = awaitDialog(robot)
        press(robot, pane, ABORT_CHERRY_PICK_BUTTON)
        eventually { assertEquals("Aborted the cherry-pick of h3.", status(robot)) }
        assertEquals(listOf("cherry-pick h3", "cherry-pick h3", "cherry-pick --abort"), writes.calls)
        assertEquals(listOf("Cherry-Pick", "Cherry-Pick", "Abort"), fileChanges)
    }

    @Test
    fun `a bar shows a cherry-pick in progress and continues or aborts it`(robot: FxRobot) {
        pendingPick = GitPendingPick(GitPick.CHERRY_PICK, "h4", listOf("a.txt", "b.txt"))
        openAndExpand(robot)

        eventually { assertTrue(view.pendingBar.isVisible) }
        val text = view.pendingBar.lookup(".git-log-pending-text") as Label
        assertEquals("Cherry-pick of h4 is in progress: 2 files have conflicts.", text.text)
        assertTrue(view.pendingBar.isManaged)
        selectRows(robot, 2)
        assertTrue(view.cherryPickItem.isDisable, "not while one is in progress")
        assertTrue(view.revertItem.isDisable)
        assertFalse(view.resetItem.isDisable, "the way out")

        writes.gate = CountDownLatch(1)
        robot.interact { view.continueButton.fire() }
        eventually { assertEquals("Continuing the cherry-pick of h4…", status(robot)) }
        assertTrue(view.continueButton.isDisable, "while it runs")
        assertTrue(view.abortButton.isDisable)
        pendingPick = null
        writes.gate?.countDown()
        eventually { assertEquals("Cherry-picked h4 as 5e6f7a8b.", status(robot)) }
        eventually { assertFalse(view.pendingBar.isVisible, "gone after the refresh") }
        assertFalse(view.pendingBar.isManaged)
        assertEquals(listOf("cherry-pick --continue"), writes.calls)
        assertEquals(listOf("Continue"), fileChanges)

        // A revert left from the terminal, its conflicts resolved.
        pendingPick = GitPendingPick(GitPick.REVERT, "h5", emptyList())
        robot.interact { view.refresh() }
        eventually { assertEquals("Revert of h5 is in progress: conflicts resolved.", text.text) }
        robot.interact { view.abortButton.fire() }
        eventually { assertEquals("Aborted the revert of h5.", status(robot)) }
        assertEquals(listOf("cherry-pick --continue", "revert --abort"), writes.calls)
        assertEquals(listOf("Continue", "Abort"), fileChanges)
    }

    @Test
    fun `continuing with conflict markers left shows an error`(robot: FxRobot) {
        pendingPick = GitPendingPick(GitPick.CHERRY_PICK, "h4", listOf("a.txt", "b/c.txt"))
        writes.pickResult = { GitPickResult.MarkersLeft(listOf("a.txt", "b/c.txt")) }
        openAndExpand(robot)
        eventually { assertTrue(view.pendingBar.isVisible) }

        robot.interact { Platform.runLater { view.continueButton.fire() } }
        val pane = awaitDialog(robot)
        assertEquals("Continue Failed", (pane.scene.window as Stage).title)
        assertEquals("The cherry-pick of h4 could not be continued.", pane.headerText)
        assertEquals("Conflict markers are left in: a.txt, b/c.txt", (pane.lookup(".git-message") as TextArea).text)
        press(robot, pane, ButtonType.OK)
        assertTrue(view.pendingBar.isVisible)
    }

    @Test
    fun `resets the current branch in the chosen mode, asking first when commits or local changes are lost`(
        robot: FxRobot,
    ) {
        openAndExpand(robot)
        selectRows(robot, 2)

        fireLater(view.resetItem)
        var pane = awaitDialog(robot)
        assertEquals("Reset Current Branch", (pane.scene.window as Stage).title)
        assertEquals("Reset branch 'main' (at h1) to h3", (pane.lookup(".git-reset-description") as Label).text)
        val mixed = pane.lookup(".git-reset-mixed") as RadioButton
        assertTrue(mixed.isSelected)
        assertFalse((pane.lookup(".git-reset-soft") as RadioButton).isSelected)
        val warning = pane.lookup(".git-reset-warning") as Label
        assertTrue(warning.text.contains("Local changes to tracked files will be lost."))
        press(robot, pane, RESET_BUTTON)
        eventually { assertEquals("Reset main to h3 (mixed); it was at h1.", status(robot)) }
        assertEquals(listOf("reset --mixed h3"), writes.calls)
        assertEquals(emptyList(), fileChanges, "files stay")

        writes.unreachable = 2
        writes.localChanges = 3
        fireLater(view.resetItem)
        pane = awaitDialog(robot)
        robot.interact { (pane.lookup(".git-reset-hard") as RadioButton).isSelected = true }
        press(robot, pane, RESET_BUTTON)
        val question = awaitDialog(robot)
        assertEquals("Reset Current Branch", (question.scene.window as Stage).title)
        assertEquals(
            "2 commits will no longer be on any branch or tag; they stay reachable through the reflog for a while." +
                "\nLocal changes in 3 files will be lost.",
            question.headerText,
        )
        assertEquals(0.0, (question.content as Region).height, "no empty band below the question")
        assertTrue((question.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        assertEquals(listOf("Reset"), fileChanges)
        press(robot, question, ButtonType.CANCEL)
        assertEquals(1, writes.calls.size, "nothing reset")

        fireLater(view.resetItem)
        pane = awaitDialog(robot)
        robot.interact { (pane.lookup(".git-reset-hard") as RadioButton).isSelected = true }
        press(robot, pane, RESET_BUTTON)
        press(robot, awaitDialog(robot), RESET_BUTTON)
        eventually { assertEquals("Reset main to h3 (hard); it was at h1.", status(robot)) }
        assertEquals("reset --hard h3", writes.calls.last())
        assertEquals(listOf("Reset", "Reset"), fileChanges, "asked once per reset, not again when confirmed")

        // Soft asks nothing about editors; Cancel resets nothing.
        writes.unreachable = 0
        fireLater(view.resetItem)
        pane = awaitDialog(robot)
        robot.interact { (pane.lookup(".git-reset-soft") as RadioButton).isSelected = true }
        press(robot, pane, RESET_BUTTON)
        eventually { assertEquals("reset --soft h3", writes.calls.last()) }
        fireLater(view.resetItem)
        press(robot, awaitDialog(robot), ButtonType.CANCEL)
        assertEquals(3, writes.calls.size)
        assertEquals(listOf("Reset", "Reset"), fileChanges)
    }

    @Test
    fun `a detached HEAD is reset as HEAD, and without a HEAD commit there is no reset`(robot: FxRobot) {
        currentRefs = GitRefs(refs.refs, currentBranch = null)
        openAndExpand(robot)
        selectRows(robot, 3)

        fireLater(view.resetItem)
        val pane = awaitDialog(robot)
        assertEquals("Reset HEAD", (pane.scene.window as Stage).title)
        assertEquals("Reset detached HEAD (at h1) to h4", (pane.lookup(".git-reset-description") as Label).text)
        press(robot, pane, RESET_BUTTON)
        eventually { assertEquals("Reset HEAD to h4 (mixed); it was at h1.", status(robot)) }

        currentRefs = GitRefs(refs.refs.filter { it.kind != GitRefKind.HEAD }, "refs/heads/main")
        robot.interact { view.refresh() }
        eventually { assertTrue(view.resetItem.isDisable) }
        assertFalse(view.cherryPickItem.isDisable)
    }

    @Test
    fun `a merge, rebase or sequence in progress is left to the terminal`(robot: FxRobot) {
        pendingPick = GitPendingOperation(GitOperation.CHERRY_PICK_SEQUENCE)
        openAndExpand(robot)

        eventually { assertTrue(view.pendingBar.isVisible) }
        val text = view.pendingBar.lookup(".git-log-pending-text") as Label
        assertEquals("A cherry-pick sequence is in progress; continue or abort it in the terminal.", text.text)
        assertFalse(view.continueButton.isVisible)
        assertFalse(view.abortButton.isVisible)
        selectRows(robot, 1)
        assertTrue(view.cherryPickItem.isDisable)
        assertTrue(view.revertItem.isDisable)
        assertFalse(view.resetItem.isDisable)

        pendingPick = null
        robot.interact { view.refresh() }
        eventually { assertFalse(view.pendingBar.isVisible) }
        assertFalse(view.cherryPickItem.isDisable)
    }

    @Test
    fun `continuing with conflicts that cannot be checked asks first`(robot: FxRobot) {
        pendingPick = GitPendingPick(GitPick.CHERRY_PICK, "h4", listOf("bin.dat", "m.txt"))
        writes.pickResult = { line ->
            if (line.endsWith("as on disk")) {
                GitPickResult.Done(FakeWrites.NEW_HEAD)
            } else {
                GitPickResult.Unchecked(
                    listOf(
                        GitUncheckedConflict("bin.dat", GitConflictReason.BINARY),
                        GitUncheckedConflict("m.txt", GitConflictReason.DELETED_ON_ONE_SIDE),
                    ),
                )
            }
        }
        openAndExpand(robot)
        eventually { assertTrue(view.pendingBar.isVisible) }

        robot.interact { Platform.runLater { view.continueButton.fire() } }
        var pane = awaitDialog(robot)
        assertEquals("Continue Cherry-Pick", (pane.scene.window as Stage).title)
        assertEquals(
            "These conflicted files cannot be checked for conflict markers. Commit them as they are on disk?",
            pane.headerText,
        )
        assertEquals(
            "bin.dat \u2014 binary, the version on disk will be committed\n" +
                "m.txt \u2014 deleted on one side, the file on disk will be committed",
            (pane.lookup(".git-confirmation-details") as TextArea).text,
        )
        assertTrue((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, pane, ButtonType.CANCEL)
        assertEquals(listOf("cherry-pick --continue"), writes.calls)

        robot.interact { Platform.runLater { view.continueButton.fire() } }
        pane = awaitDialog(robot)
        press(robot, pane, COMMIT_AS_ON_DISK_BUTTON)
        eventually { assertEquals("Cherry-picked h4 as 5e6f7a8b.", status(robot)) }
        assertEquals(
            listOf("cherry-pick --continue", "cherry-pick --continue", "cherry-pick --continue as on disk"),
            writes.calls,
        )
        assertEquals(listOf("Continue", "Continue"), fileChanges, "not asked again once confirmed")
    }
}
