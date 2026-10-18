package dev.sluide.gitlog

import dev.sluide.git.GitConflictReason
import dev.sluide.git.GitOperation
import dev.sluide.git.GitPendingOperation
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPick
import dev.sluide.git.GitPickResult
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitUncheckedConflict
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.TextArea
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI tests of the git log's writes: cherry-pick, revert, reset and the pending bar. Needs a display, see the `uiTest`
 * Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesCommitsUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `cherry-picks and reverts the selected commit after asking about unsaved editors`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 1)
        val loadsBefore = loads.size

        fireLater(view.table.cherryPickItem)
        eventually { assertEquals("Cherry-picked h2 as 5e6f7a8b.", status(robot)) }
        eventually { assertTrue(loads.size > loadsBefore, "refreshed") }

        fireLater(view.table.revertItem)
        eventually { assertEquals("Reverted h2 in 5e6f7a8b.", status(robot)) }

        assertEquals(listOf("cherry-pick h2", "revert h2"), writes.calls)
        assertEquals(listOf("sluide-git-write", "sluide-git-write"), writes.threads)
        assertEquals(listOf("Cherry-Pick", "Revert"), fileChanges)
        assertNoDialog()

        // Cancelled, nothing runs.
        beforeFileChange += false
        fireLater(view.table.cherryPickItem)
        assertEquals(2, writes.calls.size)
        assertEquals("Reverted h2 in 5e6f7a8b.", status(robot))

        // Nothing to commit is told in the header, not in a dialog.
        writes.pickResult = { GitPickResult.Empty("main") }
        fireLater(view.table.cherryPickItem)
        eventually {
            assertEquals("Nothing to cherry-pick: the changes of h2 are already on main.", status(robot))
        }
        assertNoDialog()
    }

    @Test
    fun `a merge commit is cherry-picked relative to its first parent once confirmed`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 5)

        fireLater(view.table.cherryPickItem)
        var pane = awaitDialog(robot)
        assertEquals("Cherry-Pick", (pane.scene.window as Stage).title)
        assertEquals(
            "h6 is a merge commit. Cherry-pick the changes it brought in relative to its first parent?",
            pane.headerText,
        )
        assertTrue((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        assertNoBandBelowQuestion(pane)
        press(robot, pane, ButtonType.CANCEL)
        assertEquals(emptyList(), writes.calls)
        assertEquals(emptyList(), fileChanges, "not asked before the merge is confirmed")

        fireLater(view.table.revertItem)
        pane = awaitDialog(robot)
        assertEquals("Revert Commit", (pane.scene.window as Stage).title)
        press(robot, pane, REVERT_BUTTON)
        eventually { assertEquals(listOf("revert -m 1 h6"), writes.calls) }
        assertEquals(listOf("Revert"), fileChanges)
    }

    @Test
    fun `a cherry-pick that would overwrite ignored files asks first, Cancel the default`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 1)
        writes.pickOverwritten = listOf(".env", "build/cache/data")

        fireLater(view.table.cherryPickItem)
        var pane = awaitDialog(robot)
        assertEquals("Cherry-Pick", (pane.scene.window as Stage).title)
        assertEquals(
            "2 ignored files will be overwritten or deleted by the cherry-pick of h2; git keeps no copy of them:",
            pane.headerText,
        )
        assertEquals(".env\nbuild/cache/data", pane.lookup(".git-confirmation-details").let { it as TextArea }.text)
        assertTrue((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, pane, ButtonType.CANCEL)
        assertEquals(emptyList(), writes.calls)

        fireLater(view.table.cherryPickItem)
        pane = awaitDialog(robot)
        press(robot, pane, CHERRY_PICK_BUTTON)
        eventually { assertEquals(listOf("cherry-pick h2"), writes.calls) }
        eventually { assertEquals("Cherry-picked h2 as 5e6f7a8b.", status(robot)) }
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

        fireLater(view.table.cherryPickItem)
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

        fireLater(view.table.cherryPickItem)
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
        assertTrue(view.table.cherryPickItem.isDisable, "not while one is in progress")
        assertTrue(view.table.revertItem.isDisable)
        assertFalse(view.table.resetItem.isDisable, "the way out")

        writes.gate = CountDownLatch(1)
        robot.interact { view.pendingBar.continueButton.fire() }
        eventually { assertEquals("Continuing the cherry-pick of h4…", status(robot)) }
        assertTrue(view.pendingBar.continueButton.isDisable, "while it runs")
        assertTrue(view.pendingBar.abortButton.isDisable)
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
        // Abort asks first, as it undoes the resolutions; Cancel, the default, aborts nothing.
        robot.interact { Platform.runLater { view.pendingBar.abortButton.fire() } }
        var question = awaitDialog(robot)
        assertEquals("Abort Revert", (question.scene.window as Stage).title)
        assertEquals(
            "Abort the revert of h5? Its changes are undone, also what was done to resolve its conflicts.",
            question.headerText,
        )
        assertNoBandBelowQuestion(question)
        assertTrue((question.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, question, ButtonType.CANCEL)
        assertEquals(listOf("cherry-pick --continue"), writes.calls)
        assertEquals(listOf("Continue"), fileChanges, "not asked about editors before confirming")

        robot.interact { Platform.runLater { view.pendingBar.abortButton.fire() } }
        question = awaitDialog(robot)
        press(robot, question, ABORT_REVERT_BUTTON)
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

        robot.interact { Platform.runLater { view.pendingBar.continueButton.fire() } }
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

        fireLater(view.table.resetItem)
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
        fireLater(view.table.resetItem)
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
        assertNoBandBelowQuestion(question)
        assertTrue((question.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        assertEquals(listOf("Reset"), fileChanges)
        press(robot, question, ButtonType.CANCEL)
        assertEquals(1, writes.calls.size, "nothing reset")

        fireLater(view.table.resetItem)
        pane = awaitDialog(robot)
        robot.interact { (pane.lookup(".git-reset-hard") as RadioButton).isSelected = true }
        press(robot, pane, RESET_BUTTON)
        press(robot, awaitDialog(robot), RESET_BUTTON)
        eventually { assertEquals("Reset main to h3 (hard); it was at h1.", status(robot)) }
        assertEquals("reset --hard h3", writes.calls.last())
        assertEquals(listOf("Reset", "Reset"), fileChanges, "asked once per reset, not again when confirmed")

        // Untracked or ignored files a hard reset overwrites are listed below the question.
        writes.unreachable = 0
        writes.localChanges = 0
        writes.overwritten = listOf(".env", "build/out.txt")
        fireLater(view.table.resetItem)
        pane = awaitDialog(robot)
        robot.interact { (pane.lookup(".git-reset-hard") as RadioButton).isSelected = true }
        press(robot, pane, RESET_BUTTON)
        val overwriting = awaitDialog(robot)
        assertEquals(
            "2 untracked or ignored files will be overwritten by the commit's version:",
            overwriting.headerText,
        )
        assertEquals(".env\nbuild/out.txt", (overwriting.lookup(".git-confirmation-details") as TextArea).text)
        press(robot, overwriting, ButtonType.CANCEL)
        assertEquals(2, writes.calls.size, "nothing reset")
        writes.overwritten = emptyList()

        // Soft asks nothing about editors; Cancel resets nothing.
        writes.unreachable = 0
        fireLater(view.table.resetItem)
        pane = awaitDialog(robot)
        robot.interact { (pane.lookup(".git-reset-soft") as RadioButton).isSelected = true }
        press(robot, pane, RESET_BUTTON)
        eventually { assertEquals("reset --soft h3", writes.calls.last()) }
        fireLater(view.table.resetItem)
        press(robot, awaitDialog(robot), ButtonType.CANCEL)
        assertEquals(3, writes.calls.size)
        assertEquals(listOf("Reset", "Reset", "Reset"), fileChanges, "the cancelled overwrite asked too")
    }

    @Test
    fun `a detached HEAD is reset as HEAD, and without a HEAD commit there is no reset`(robot: FxRobot) {
        currentRefs = GitRefs(refs.refs, currentBranch = null)
        openAndExpand(robot)
        selectRows(robot, 3)

        fireLater(view.table.resetItem)
        val pane = awaitDialog(robot)
        assertEquals("Reset HEAD", (pane.scene.window as Stage).title)
        assertEquals("Reset detached HEAD (at h1) to h4", (pane.lookup(".git-reset-description") as Label).text)
        press(robot, pane, RESET_BUTTON)
        eventually { assertEquals("Reset HEAD to h4 (mixed); it was at h1.", status(robot)) }

        currentRefs = GitRefs(refs.refs.filter { it.kind != GitRefKind.HEAD }, "refs/heads/main")
        robot.interact { view.refresh() }
        eventually { assertTrue(view.table.resetItem.isDisable) }
        assertFalse(view.table.cherryPickItem.isDisable)
    }

    @Test
    fun `a merge, rebase or sequence in progress is left to the terminal`(robot: FxRobot) {
        pendingPick = GitPendingOperation(GitOperation.CHERRY_PICK_SEQUENCE)
        openAndExpand(robot)

        eventually { assertTrue(view.pendingBar.isVisible) }
        val text = view.pendingBar.lookup(".git-log-pending-text") as Label
        assertEquals("A cherry-pick sequence is in progress; continue or abort it in the terminal.", text.text)
        assertFalse(view.pendingBar.continueButton.isVisible)
        assertFalse(view.pendingBar.abortButton.isVisible)
        selectRows(robot, 1)
        assertTrue(view.table.cherryPickItem.isDisable)
        assertTrue(view.table.revertItem.isDisable)
        assertFalse(view.table.resetItem.isDisable)

        pendingPick = null
        robot.interact { view.refresh() }
        eventually { assertFalse(view.pendingBar.isVisible) }
        assertFalse(view.table.cherryPickItem.isDisable)
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

        robot.interact { Platform.runLater { view.pendingBar.continueButton.fire() } }
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

        robot.interact { Platform.runLater { view.pendingBar.continueButton.fire() } }
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
