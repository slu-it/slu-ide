package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitCommitChanges
import dev.sluide.git.GitOutgoing
import dev.sluide.git.GitPushRejection
import dev.sluide.git.GitPushResult
import dev.sluide.git.GitPushTarget
import dev.sluide.git.GitRefs
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.ChangeFile
import dev.sluide.gitlog.data.ChangeNode
import dev.sluide.gitlog.operations.Fetch
import dev.sluide.gitlog.operations.commitCount
import dev.sluide.gitlog.push.FORCE_PUSH_BUTTON
import dev.sluide.gitlog.push.PUSH_KEYS
import dev.sluide.gitlog.push.PushFiles
import dev.sluide.gitlog.push.PushRow
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.ButtonType
import javafx.scene.control.TextArea
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Modality
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the Push dialog of the git log (ADR 0049): what it reads and shows, pushing and force pushing, the header
 * and Commit and Push. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogPushUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private val second = GitCommit("c2", listOf("c1"), "Ann", "ann@x", Instant.EPOCH, "Second")
    private val first = GitCommit("c1", listOf("r0"), "Bob", "bob@x", Instant.EPOCH, "First")

    /** Two commits to push to a branch the remote has, at r1; the merge base differs from c1's parent. */
    private val twoCommits = GitOutgoing(listOf(second, first), false, "r1", 0, "mb")

    private val readme = GitChangedFile(GitChange.MODIFIED, "README.md")
    private val app = GitChangedFile(GitChange.ADDED, "src/App.kt")

    private fun dialog() = checkNotNull(view.actions.pushDialog)

    /** Closes the dialog on the JavaFX thread, before the fixture closes the view. */
    @AfterEach
    fun closePush() {
        WaitForAsyncUtils.asyncFx { view.actions.pushDialog?.close() }.get()
    }

    /** Opens the repository and the Push dialog with [what] to push, and waits until it is read. */
    private fun openPush(
        robot: FxRobot,
        what: (String, String) -> GitOutgoing? = { _, _ -> twoCommits },
    ) {
        outgoing = what
        changedFilesOf = { commit, _ -> if (commit == "c1") listOf(readme) else listOf(readme, app) }
        robot.interact {
            view.open(repo)
            view.showPush()
        }
        awaitRead()
    }

    /** Waits until the dialog read what it shows, and its files if it lists commits. */
    private fun awaitRead() {
        eventually {
            assertTrue(dialog().reader.state.get() != null)
            assertFalse(dialog().reader.loading.get())
            assertFalse(dialog().reader.files.get() is PushFiles.Loading)
        }
    }

    private fun fx(action: () -> Unit) {
        Platform.runLater(action)
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun <T> read(value: () -> T): T = WaitForAsyncUtils.asyncFx<T> { value() }.get()

    private fun pushDisabled() = read { dialog().pushButton.isDisabled }

    private fun rows(): List<String> =
        read {
            dialog().commitList.items.map { row ->
                when (row) {
                    is PushRow.All -> "All ${commitCount(row.count, row.more)}"
                    is PushRow.Commit -> row.commit.subject
                    PushRow.More -> "and more…"
                }
            }
        }

    /** The file names of the dialog's tree, depth first. */
    private fun files(): List<String> =
        read {
            fun names(item: TreeItem<ChangeNode>): List<String> =
                item.children.flatMap { listOf(it.value.name) + names(it) }
            names(dialog().tree.root)
        }

    private fun selectFile(
        robot: FxRobot,
        path: String,
    ) {
        robot.interact { dialog().tree.selectionModel.select(fileItem(dialog().tree.root, path)) }
        WaitForAsyncUtils.waitForFxEvents()
    }

    /** The item of the file at [path] below [item]. */
    private fun fileItem(
        item: TreeItem<ChangeNode>,
        path: String,
    ): TreeItem<ChangeNode> = checkNotNull(findFile(item, path)) { path }

    private fun findFile(
        item: TreeItem<ChangeNode>,
        path: String,
    ): TreeItem<ChangeNode>? {
        if ((item.value as? ChangeFile)?.file?.path == path) return item
        return item.children.firstNotNullOfOrNull { findFile(it, path) }
    }

    private fun typeTarget(
        robot: FxRobot,
        vararg texts: String,
    ) {
        robot.interact { texts.forEach { dialog().targetField.text = it } }
    }

    private fun press(
        target: Node,
        code: KeyCode,
        shortcut: Boolean = false,
        alt: Boolean = false,
    ) {
        val mac = System.getProperty("os.name").startsWith("Mac")
        fx {
            // The shortcut is Cmd on macOS, else Ctrl.
            val event = KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, alt, shortcut && mac)
            Event.fireEvent(target, event)
        }
    }

    @Test
    fun `the dialog is prefilled from the push target, owned by the IDE window and not modal`(robot: FxRobot) {
        openPush(robot)
        val stage = dialog().stage

        assertSame(view.scene.window, stage.owner)
        assertEquals(Modality.NONE, stage.modality)
        assertTrue(stage.isResizable)
        assertEquals(900.0, stage.width, 1.0)
        assertEquals("Push Commits to origin", stage.title)
        assertTrue(
            dialog()
                .commitList.scene.root.styleClass
                .contains("git-log"),
        )
        assertEquals("main", dialog().branchLabel.text)
        assertEquals("origin", dialog().remoteLabel.text)
        assertTrue(dialog().remoteLabel.isVisible)
        assertFalse(dialog().remoteBox.isVisible, "one remote: no choice")
        assertEquals("main", dialog().targetField.text)
        assertFalse(dialog().newBadge.isVisible)
        assertEquals(listOf("All 2 commits", "Second", "First"), rows())
        assertEquals(0, read { dialog().commitList.selectionModel.selectedIndex })
        assertEquals("As of the last fetch of origin.", dialog().fetchNote.text)
        assertFalse(dialog().behindLabel.isVisible)
        assertFalse(pushDisabled())
        assertTrue(outgoingThreads.all { it == "sluide-git-push" }, outgoingThreads.toString())
        assertEquals("origin main", outgoingReads.first())
    }

    @Test
    fun `several remotes are a choice, and choosing another reads it and renames the dialog`(robot: FxRobot) {
        currentRemotes = listOf("origin", "fork")
        openPush(robot)
        assertTrue(dialog().remoteBox.isVisible)
        assertFalse(dialog().remoteLabel.isVisible)
        assertEquals(listOf("origin", "fork"), dialog().remoteBox.items)

        robot.interact { dialog().remoteBox.value = "fork" }
        awaitRead()

        assertEquals("fork main", outgoingReads.last())
        assertEquals("Push Commits to fork", dialog().stage.title)
        assertEquals("As of the last fetch of fork.", dialog().fetchNote.text)
    }

    @Test
    fun `a target the remote does not have is New, an invalid name disables Push and says why`(robot: FxRobot) {
        openPush(robot) { _, branch -> if (branch == "feature") twoCommits.copy(remoteCommit = null) else twoCommits }

        typeTarget(robot, "feature")
        eventually { assertTrue(dialog().newBadge.isVisible) }
        assertEquals(
            "refs/remotes/origin/feature does not exist: the push creates feature on origin.",
            dialog().newBadge.tooltip.text,
        )
        assertFalse(pushDisabled())

        typeTarget(robot, "a..b")
        assertEquals("'a..b' is not a valid branch name.", dialog().problemLabel.text)
        assertTrue(dialog().problemLabel.isVisible)
        assertTrue(pushDisabled())

        typeTarget(robot, "")
        assertEquals("Enter a name.", dialog().problemLabel.text)
        typeTarget(robot, "main")
        awaitRead()
        assertFalse(dialog().problemLabel.isVisible)
        assertFalse(dialog().newBadge.isVisible)
        assertFalse(pushDisabled())
    }

    @Test
    fun `editing the target reads again after a pause, and only the latest name`(robot: FxRobot) {
        // The test lets the pause pass: reads asked for with a delay wait here until then.
        val waiting = CopyOnWriteArrayList<Pair<Long, () -> Unit>>()
        view.actions.schedulePush = { executor, delay, task ->
            if (delay == 0L) executor.execute(task) else waiting += delay to { executor.execute(task) }
        }
        openPush(robot)
        val before = outgoingReads.size

        typeTarget(robot, "f", "fe", "feat")
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(pushDisabled(), "not before it is read")
        assertEquals(listOf(300L, 300L, 300L), waiting.map { it.first })
        assertEquals(before, outgoingReads.size, "not before the pause")

        robot.interact { waiting.forEach { it.second() } }
        awaitRead()
        assertEquals(listOf("origin feat"), outgoingReads.drop(before))
        assertEquals("feat", dialog().targetField.text)
    }

    @Test
    fun `All shows the files against the base, a commit its own, and Show Diff passes the parent`(robot: FxRobot) {
        val diffs = CopyOnWriteArrayList<CommitFileDiff>()
        view.onShowDiff = { diffs += it }
        openPush(robot)

        assertEquals(listOf("src", "App.kt", "README.md"), files())
        assertEquals("c2 mb", changedFilesReads.last())
        selectFile(robot, "src/App.kt")
        robot.interact { dialog().tree.showDiffItem.fire() }
        eventually { assertEquals(1, diffs.size) }
        assertEquals(listOf("src/App.kt", "c2", "mb"), diffs.single().let { listOf(it.path, it.commit, it.parent) })
        assertEquals("c2 mb src/App.kt", fileDiffLoads.single())

        robot.interact { dialog().commitList.selectionModel.select(2) }
        awaitRead()
        assertEquals(listOf("README.md"), files())
        assertEquals("c1 r0", changedFilesReads.last())
        selectFile(robot, "README.md")
        robot.interact { dialog().tree.showDiffItem.fire() }
        eventually { assertEquals(2, diffs.size) }
        assertEquals(listOf("c1", "r0"), diffs.last().let { listOf(it.commit, it.parent) })

        // What keeps a diff from being shown is told in the dialog.
        commitFileDiff = null
        robot.interact { dialog().tree.showDiffItem.fire() }
        eventually { assertEquals("The diff could not be read.", dialog().statusLabel.text) }
        assertTrue(dialog().statusLabel.isVisible)
    }

    @Test
    fun `more commits than listed show as N+ and a last row`(robot: FxRobot) {
        openPush(robot) { _, _ -> twoCommits.copy(more = true) }
        assertEquals(listOf("All 2+ commits", "Second", "First", "and more…"), rows())
    }

    @Test
    fun `the remote's own commits are noted, and Force Push is not for main or master`(robot: FxRobot) {
        openPush(robot) { _, _ -> twoCommits.copy(behind = 2) }
        assertEquals(
            "origin/main has 2 commits that your branch does not have; a normal push will be rejected. " +
                "Fetch, then merge or rebase, or force push.",
            dialog().behindLabel.text,
        )
        assertTrue(dialog().behindLabel.isVisible)
        assertFalse(pushDisabled(), "git's answer is up to the user")
        assertTrue(dialog().forcePushItem.isDisable)
        assertFalse(dialog().protectedLabel.isVisible)

        robot.interact { dialog().pushButton.show() }
        assertTrue(dialog().protectedLabel.isVisible)
        assertEquals("Force push to main is not allowed (protected branch).", dialog().protectedLabel.text)
        robot.interact { dialog().pushButton.hide() }

        typeTarget(robot, "MASTER")
        awaitRead()
        assertTrue(dialog().forcePushItem.isDisable, "in any case")
        typeTarget(robot, "feature")
        awaitRead()
        assertFalse(dialog().forcePushItem.isDisable)
        assertFalse(dialog().protectedLabel.isVisible)
    }

    @Test
    fun `everything up to date disables Push and Force Push and says so`(robot: FxRobot) {
        openPush(robot) { _, _ -> GitOutgoing(emptyList(), false, "h1", 0, null) }
        assertTrue(pushDisabled())
        assertTrue(dialog().forcePushItem.isDisable)
        assertEquals("Everything is up to date.", dialog().reasonLabel.text)
        assertTrue(dialog().reasonLabel.isVisible)
        assertEquals("Nothing to push.", dialog().commitPlaceholder.text)
        assertEquals(emptyList(), rows())
    }

    @Test
    fun `behind the target only Force Push can push, and not to a protected branch`(robot: FxRobot) {
        val remote = "f".repeat(40)
        openPush(robot) { _, _ -> GitOutgoing(emptyList(), false, remote, 2, null) }
        assertEquals("Your branch is behind origin/main; nothing to push.", dialog().reasonLabel.text)
        assertFalse(dialog().behindLabel.isVisible, "said once")
        assertTrue(pushDisabled(), "main is protected: nothing at all")

        typeTarget(robot, "feature")
        awaitRead()
        assertEquals("Your branch is behind origin/feature; nothing to push.", dialog().reasonLabel.text)
        assertFalse(pushDisabled(), "Force Push moves the target back")
        assertFalse(read { dialog().pushAllowed.get() })
        assertTrue(dialog().pushButton.styleClass.contains("git-push-force-only"))
        assertFalse(dialog().forcePushItem.isDisable)
        robot.interact { dialog().pushButton.fire() }
        assertTrue(dialog().stage.isShowing, "Push itself pushes nothing")
        assertTrue(writes.calls.isEmpty())

        Platform.runLater { dialog().forcePushItem.fire() }
        val pane = awaitDialog(robot)
        assertEquals(
            "2 commits on origin/feature that are not in your branch will be lost.",
            (pane.lookup(".git-confirmation-details") as TextArea).text,
        )
        press(robot, pane, FORCE_PUSH_BUTTON)
        eventually { assertEquals(listOf("push --force-with-lease=feature:$remote origin main:feature"), writes.calls) }
    }

    @Test
    fun `a new branch without new commits can be pushed and says what it creates`(robot: FxRobot) {
        headCommit = "0123456789abcdef"
        openPush(robot) { _, _ -> GitOutgoing(emptyList(), false, null, 0, null) }
        assertEquals("No new commits: pushing creates origin/main at 01234567.", dialog().commitPlaceholder.text)
        assertTrue(dialog().newBadge.isVisible)
        assertFalse(dialog().reasonLabel.isVisible)
        assertTrue(read { dialog().pushAllowed.get() })
        assertFalse(dialog().pushButton.styleClass.contains("git-push-force-only"))
    }

    @Test
    fun `a detached or unborn HEAD and no remote disable Push with the reason`(robot: FxRobot) {
        currentRefs = GitRefs(refs.refs, null)
        openPush(robot)
        assertTrue(pushDisabled())
        assertEquals("HEAD is detached: check out a branch to push it.", dialog().commitPlaceholder.text)
        assertFalse(dialog().reasonLabel.isVisible, "said once, in place of the commits")
        assertTrue(dialog().targetField.isDisable)
        assertFalse(dialog().problemLabel.isVisible, "no name to check")

        currentRefs = refs
        headCommit = null
        robot.interact { dialog().reader.load(null, null) }
        awaitRead()
        assertEquals("There is no commit to push.", dialog().commitPlaceholder.text)
        assertTrue(pushDisabled())

        headCommit = "h1"
        currentRemotes = emptyList()
        robot.interact { dialog().reader.load(null, null) }
        awaitRead()
        assertEquals(
            "The repository has no remote. Add one in a terminal: git remote add <name> <url>.",
            dialog().commitPlaceholder.text,
        )
        assertTrue(pushDisabled())
        assertEquals("Push Commits", dialog().stage.title)
    }

    @Test
    fun `Push closes the dialog and pushes, the header says so, then what was pushed`(robot: FxRobot) {
        pushTarget = GitPushTarget("origin", "main", hasUpstream = false)
        openPush(robot)
        robot.interact { view.expanded.set(true) }
        eventually { assertTrue(view.table.items.isNotEmpty()) }
        writes.gate = CountDownLatch(1)
        val loadsBefore = loads.size

        robot.interact { dialog().pushButton.fire() }

        assertNull(view.actions.pushDialog)
        eventually { assertEquals("Pushing main to origin/main…", status(robot)) }
        assertTrue(cancelShown())
        writes.gate?.countDown()
        eventually { assertEquals("Pushed 2 commits to origin/main.", status(robot)) }
        assertEquals(listOf("push -u origin main:main"), writes.calls)
        assertEquals(listOf("sluide-git-write"), writes.threads)
        assertEquals(emptyList(), fileChanges, "no unsaved-editor question")
        assertFalse(cancelShown())
        eventually { assertTrue(loads.size > loadsBefore, "refreshed") }
        assertNoDialog()
    }

    @Test
    fun `Force Push asks first, Cancel does nothing, confirming pushes with the lease of the commit shown`(
        robot: FxRobot,
    ) {
        val remote = "f".repeat(40)
        openPush(robot) { _, branch ->
            if (branch == "feature") twoCommits.copy(remoteCommit = remote, behind = 1) else twoCommits
        }
        typeTarget(robot, "feature")
        awaitRead()

        Platform.runLater { dialog().forcePushItem.fire() }
        var pane = awaitDialog(robot)
        assertEquals("Force Push", (pane.scene.window as Stage).title)
        assertEquals("Force push main to origin/feature?", pane.headerText)
        assertEquals(
            "1 commit on origin/feature that is not in your branch will be lost.",
            (pane.lookup(".git-confirmation-details") as TextArea).text,
        )
        press(robot, pane, ButtonType.CANCEL)
        assertTrue(writes.calls.isEmpty())
        assertTrue(dialog().stage.isShowing)

        Platform.runLater { dialog().forcePushItem.fire() }
        pane = awaitDialog(robot)
        press(robot, pane, FORCE_PUSH_BUTTON)

        eventually { assertEquals("Force pushed main to origin/feature.", status(robot)) }
        assertEquals(listOf("push --force-with-lease=feature:$remote origin main:feature"), writes.calls)
        assertNull(view.actions.pushDialog)
    }

    @Test
    fun `a rejection shows Push Failed with the backend's message, Cancel in the header shows no dialog`(
        robot: FxRobot,
    ) {
        openPush(robot)
        writes.pushResult = {
            GitPushResult(
                GitResult(false, "origin/main has commits that your branch does not have.\n! [rejected]"),
                rejection = GitPushRejection.NON_FAST_FORWARD,
            )
        }
        robot.interact { dialog().pushButton.fire() }

        val pane = awaitDialog(robot)
        assertEquals("Push Failed", (pane.scene.window as Stage).title)
        assertEquals("main could not be pushed to origin/main.", pane.headerText)
        assertEquals(
            "origin/main has commits that your branch does not have.\n! [rejected]",
            (pane.lookup(".git-message") as TextArea).text,
        )
        press(robot, pane, ButtonType.OK)

        writes.pushResult = { GitPushResult(GitResult(true, "")) }
        writes.gate = CountDownLatch(1)
        robot.interact { view.showPush() }
        awaitRead()
        robot.interact { dialog().pushButton.fire() }
        eventually { assertTrue(cancelShown()) }
        robot.interact { view.cancelButton.fire() }
        eventually { assertEquals("Push cancelled; it may have reached origin. Fetch to check.", status(robot)) }
        assertNoDialog()
    }

    @Test
    fun `showPush raises the open dialog, opens while a write runs, and another folder closes it`(robot: FxRobot) {
        openPush(robot)
        val shown = dialog()
        robot.interact { view.showPush() }
        assertSame(shown, view.actions.pushDialog)
        assertEquals(
            1,
            robot.listWindows().count {
                it.isShowing &&
                    it.scene.root.styleClass
                        .contains("git-push")
            },
        )

        robot.interact { view.open(repo) }
        assertFalse(shown.stage.isShowing)
        assertNull(view.actions.pushDialog)

        // A fetch runs: the dialog opens, Push waits for the fetch, and reads again once it ended.
        writes.gate = CountDownLatch(1)
        robot.interact { view.fetchButton.fire() }
        robot.interact { view.showPush() }
        awaitRead()
        assertTrue(pushDisabled())
        assertEquals(listOf("All 2 commits", "Second", "First"), rows())
        val reads = outgoingReads.size
        outgoing = { _, _ -> twoCommits.copy(commits = listOf(second), remoteCommit = "c1", base = "c1") }
        writes.gate?.countDown()

        eventually { assertFalse(dialog().pushButton.isDisabled) }
        awaitRead()
        assertTrue(outgoingReads.size > reads, "read again")
        assertEquals(listOf("All 1 commit", "Second"), rows())
    }

    @Test
    fun `a force push confirmed after the dialog read again leases the commit read last`(robot: FxRobot) {
        val looked = "a".repeat(40)
        val moved = "b".repeat(40)
        pushTarget = GitPushTarget("origin", "feature", hasUpstream = true)
        openPush(robot) { _, _ -> twoCommits.copy(remoteCommit = looked, behind = 1) }
        val reads = outgoingReads.size

        // Opening it again reads again, without waiting for the focus.
        outgoing = { _, _ -> twoCommits.copy(remoteCommit = moved, behind = 3) }
        robot.interact { view.showPush() }
        eventually { assertTrue(outgoingReads.size > reads) }
        awaitRead()

        Platform.runLater { dialog().forcePushItem.fire() }
        val pane = awaitDialog(robot)
        assertEquals(
            "3 commits on origin/feature that are not in your branch will be lost.",
            (pane.lookup(".git-confirmation-details") as TextArea).text,
        )
        press(robot, pane, FORCE_PUSH_BUTTON)
        eventually { assertEquals(listOf("push --force-with-lease=feature:$moved origin main:feature"), writes.calls) }
    }

    @Test
    fun `the dialog reads again when it gains the focus`(robot: FxRobot) {
        openPush(robot)
        val stage = dialog().stage
        robot.interact { (view.scene.window as Stage).requestFocus() }
        eventually { assertFalse(stage.isFocused) }
        val reads = outgoingReads.size

        robot.interact { stage.requestFocus() }

        eventually { assertTrue(outgoingReads.size > reads) }
    }

    @Test
    fun `closing the view closes the dialog`(robot: FxRobot) {
        openPush(robot)
        val stage = dialog().stage

        robot.interact { view.close() }

        assertFalse(stage.isShowing)
        assertNull(view.actions.pushDialog)
    }

    @Test
    fun `Enter in the target field does not push, Escape closes the dialog`(robot: FxRobot) {
        openPush(robot)
        press(dialog().targetField, KeyCode.ENTER)
        assertTrue(writes.calls.isEmpty())
        assertTrue(dialog().stage.isShowing)

        val stage = dialog().stage
        press(dialog().targetField, KeyCode.ESCAPE)
        assertFalse(stage.isShowing)
        assertNull(view.actions.pushDialog)
    }

    @Test
    fun `the header's Push opens the dialog, also collapsed, and is disabled once the log found no remotes`(
        robot: FxRobot,
    ) {
        outgoing = { _, _ -> twoCommits }
        robot.interact { view.open(repo) }
        assertTrue(view.pushButton.isVisible)
        assertFalse(view.pushButton.isDisable)
        assertEquals("Push… (${PUSH_KEYS.displayText})", view.pushButton.tooltip.text)
        robot.interact { view.pushButton.fire() }
        awaitRead()
        assertFalse(view.expanded.get(), "the button does not expand the log")
        robot.interact { dialog().close() }

        currentRemotes = emptyList()
        openAndExpand(robot)
        eventually { assertTrue(view.pushButton.isDisable) }
    }

    private fun startCommit(robot: FxRobot) {
        outgoing = { _, _ -> twoCommits }
        commitChanges = GitCommitChanges(listOf(readme), emptyList(), null, emptyList(), false)
        robot.interact {
            view.open(repo)
            view.showCommit()
        }
        eventually {
            assertTrue(
                checkNotNull(view.tabs.commitView)
                    .tree.root.children
                    .isNotEmpty(),
            )
        }
        robot.interact { checkNotNull(view.tabs.commitView).messageArea.text = "Fix" }
    }

    @Test
    fun `Commit and Push commits, then opens the Push dialog, a failed commit does not`(robot: FxRobot) {
        startCommit(robot)
        val commitView = checkNotNull(view.tabs.commitView)
        assertFalse(commitView.commitAndPushButton.isDisabled)

        writes.result = { GitResult(false, "pre-commit: tests fail") }
        Platform.runLater { commitView.commitAndPushButton.fire() }
        val pane = awaitDialog(robot)
        assertEquals("Commit Failed", (pane.scene.window as Stage).title)
        press(robot, pane, ButtonType.OK)
        assertNull(view.actions.pushDialog)

        writes.result = { GitResult(true, "") }
        robot.interact { commitView.commitAndPushButton.fire() }
        eventually { assertTrue(view.actions.pushDialog != null) }
        assertEquals(listOf("commit README.md|: Fix", "commit README.md|: Fix"), writes.calls)
        assertEquals("Committed 5e6f7a8b: Fix.", status(robot))
        awaitRead()
    }

    @Test
    fun `a cancelled Commit and Push does not open the Push dialog`(robot: FxRobot) {
        startCommit(robot)
        writes.gate = CountDownLatch(1)
        robot.interact { checkNotNull(view.tabs.commitView).commitAndPushButton.fire() }
        eventually { assertTrue(cancelShown()) }
        writes.result = { GitResult(false, "Cancelled.") }

        robot.interact { view.cancelButton.fire() }
        writes.gate?.countDown()

        eventually { assertEquals("Commit cancelled.", status(robot)) }
        assertNull(view.actions.pushDialog)
        assertNoDialog()
    }

    @Test
    fun `Shortcut+Alt+K commits and pushes from the Commit tab`(robot: FxRobot) {
        startCommit(robot)
        val area = checkNotNull(view.tabs.commitView).messageArea
        press(area, KeyCode.K, shortcut = true, alt = true)

        eventually { assertTrue(view.actions.pushDialog != null) }
        assertEquals(listOf("commit README.md|: Fix"), writes.calls)
        assertEquals("", read { area.text }, "the draft is done")
    }
}
