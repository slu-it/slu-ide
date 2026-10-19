package dev.sluide.gitlog

import dev.sluide.git.GitConflictReason
import dev.sluide.git.GitPendingRebase
import dev.sluide.git.GitRebaseResult
import dev.sluide.git.GitUncheckedConflict
import dev.sluide.gitlog.operations.ABORT_REBASE_BUTTON
import dev.sluide.gitlog.operations.COMMIT_AS_ON_DISK_BUTTON
import dev.sluide.gitlog.operations.RESOLVE_BUTTON
import dev.sluide.gitlog.operations.SKIP_REBASE_BUTTON
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
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

/** UI tests of a rebase in the pending bar of the git log (ADR 0063). Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesRebaseUiTest : GitLogWritesUiFixture() {
    private val onto = "1a2b3c4d5e6f"
    private val stopped = "5e6f7a8b9c0d"

    @Start
    fun start(stage: Stage) = startView(stage)

    private fun stopAgain() =
        GitRebaseResult.Stopped(GitPendingRebase("feature", onto, stopped, 3, 7, listOf("a.txt")), "CONFLICT in a.txt")

    private fun fire(
        robot: FxRobot,
        button: Button,
    ) = robot.interact { Platform.runLater { button.fire() } }

    @Test
    fun `a bar shows a rebase in progress with continue, skip and abort`(robot: FxRobot) {
        pending = GitPendingRebase("feature", onto, stopped, 3, 7, listOf("a.txt", "b.txt"))
        writes.inProgress = pending
        openAndExpand(robot)

        eventually { assertTrue(view.pendingBar.isVisible) }
        val text = view.pendingBar.lookup(".git-log-pending-text") as Label
        assertEquals(
            "Rebasing feature onto 1a2b3c4d: stopped at 5e6f7a8b (3 of 7); 2 files have conflicts.",
            text.text,
        )
        assertTrue(view.pendingBar.continueButton.isVisible)
        assertTrue(view.pendingBar.skipButton.isVisible)
        assertTrue(view.pendingBar.skipButton.isManaged)
        assertTrue(view.pendingBar.abortButton.isVisible)
        selectRows(robot, 2)
        assertTrue(view.table.cherryPickItem.isDisable, "not while a rebase is in progress")
        assertFalse(view.table.resetItem.isDisable)

        // Skip asks first, as it drops what was done to resolve the conflicts; Cancel, the default, skips nothing.
        fire(robot, view.pendingBar.skipButton)
        val question = awaitDialog(robot)
        assertEquals("Skip Commit", (question.scene.window as Stage).title)
        assertEquals(
            "Skip 5e6f7a8b and continue the rebase of 'feature'? Its changes are left out, and what was done to " +
                "resolve its conflicts is lost.",
            question.headerText,
        )
        assertTrue((question.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, question, ButtonType.CANCEL)
        assertTrue(writes.calls.isEmpty())
        assertTrue(fileChanges.isEmpty(), "not asked about editors before confirming")

        pending = null
        fire(robot, view.pendingBar.skipButton)
        press(robot, awaitDialog(robot), SKIP_REBASE_BUTTON)
        eventually { assertEquals("Skipped the commit; rebased feature.", status(robot)) }
        assertEquals(listOf("rebase --skip"), writes.calls)
        assertEquals(listOf("Skip"), fileChanges)
        eventually { assertFalse(view.pendingBar.isVisible) }
    }

    @Test
    fun `the buttons are disabled while a write runs`(robot: FxRobot) {
        pending = GitPendingRebase("feature", onto, stopped, 3, 7, listOf("a.txt"))
        writes.inProgress = pending
        openAndExpand(robot)
        eventually { assertTrue(view.pendingBar.isVisible) }

        writes.gate = CountDownLatch(1)
        fire(robot, view.pendingBar.continueButton)
        eventually { assertTrue(view.pendingBar.skipButton.isDisable) }
        assertTrue(view.pendingBar.continueButton.isDisable)
        assertTrue(view.pendingBar.abortButton.isDisable)
        writes.gate?.countDown()
        eventually { assertFalse(view.pendingBar.skipButton.isDisable) }
    }

    @Test
    fun `aborting a rebase asks first`(robot: FxRobot) {
        pending = GitPendingRebase("feature", onto, stopped, 3, 7, listOf("a.txt"))
        writes.inProgress = pending
        openAndExpand(robot)
        eventually { assertTrue(view.pendingBar.isVisible) }

        fire(robot, view.pendingBar.abortButton)
        var question = awaitDialog(robot)
        assertEquals("Abort Rebase", (question.scene.window as Stage).title)
        assertEquals(
            "Abort the rebase of 'feature'? The branch goes back to where it was; the commits rebased so far and " +
                "what was done to resolve conflicts are lost.",
            question.headerText,
        )
        assertTrue((question.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, question, ButtonType.CANCEL)
        assertTrue(writes.calls.isEmpty())

        fire(robot, view.pendingBar.abortButton)
        question = awaitDialog(robot)
        press(robot, question, ABORT_REBASE_BUTTON)
        eventually { assertEquals("Aborted the rebase of feature.", status(robot)) }
        assertEquals(listOf("rebase --abort"), writes.calls)
        assertEquals(listOf("Abort"), fileChanges)
    }

    @Test
    fun `continuing that stops again shows the stopped dialog again`(robot: FxRobot) {
        pending = GitPendingRebase("feature", onto, stopped, 3, 7, listOf("a.txt"))
        writes.inProgress = pending
        writes.rebaseResult = { line -> if (line == "rebase --abort") GitRebaseResult.Done(stopped) else stopAgain() }
        openAndExpand(robot)
        eventually { assertTrue(view.pendingBar.isVisible) }

        fire(robot, view.pendingBar.continueButton)
        var pane = awaitDialog(robot)
        assertEquals("Rebase Stopped", (pane.scene.window as Stage).title)
        assertEquals("Rebase of feature stopped at 5e6f7a8b with conflicts in:", pane.headerText)
        assertEquals("a.txt", (pane.lookup(".git-conflict-paths") as TextArea).text)
        assertEquals("CONFLICT in a.txt", (pane.expandableContent.lookup(".git-message") as TextArea).text)
        assertTrue((pane.lookupButton(RESOLVE_BUTTON) as Button).isDefaultButton)
        assertEquals("Abort Rebase", (pane.lookupButton(ABORT_REBASE_BUTTON) as Button).text)
        assertEquals("Rebase of feature stopped at 5e6f7a8b with conflicts.", status(robot))
        press(robot, pane, RESOLVE_BUTTON)
        assertNoDialog()

        fire(robot, view.pendingBar.skipButton)
        press(robot, awaitDialog(robot), SKIP_REBASE_BUTTON)
        pane = awaitDialog(robot)
        assertEquals("Rebase Stopped", (pane.scene.window as Stage).title)
        press(robot, pane, ABORT_REBASE_BUTTON)
        eventually { assertEquals("Aborted the rebase of feature.", status(robot)) }
        assertEquals(listOf("rebase --continue", "rebase --skip", "rebase --abort"), writes.calls.take(3))
    }

    @Test
    fun `continuing with markers left or unchecked files behaves as for a pick`(robot: FxRobot) {
        pending = GitPendingRebase("feature", onto, stopped, 3, 7, listOf("bin.dat"))
        writes.inProgress = pending
        writes.rebaseResult = { GitRebaseResult.MarkersLeft(listOf("a.txt", "b/c.txt")) }
        openAndExpand(robot)
        eventually { assertTrue(view.pendingBar.isVisible) }

        fire(robot, view.pendingBar.continueButton)
        var pane = awaitDialog(robot)
        assertEquals("Continue Failed", (pane.scene.window as Stage).title)
        assertEquals("The rebase could not be continued.", pane.headerText)
        assertEquals("Conflict markers are left in: a.txt, b/c.txt", (pane.lookup(".git-message") as TextArea).text)
        press(robot, pane, ButtonType.OK)

        writes.rebaseResult = { line ->
            if (line.endsWith("as on disk")) {
                GitRebaseResult.Done(stopped)
            } else {
                GitRebaseResult.Unchecked(listOf(GitUncheckedConflict("bin.dat", GitConflictReason.BINARY)))
            }
        }
        fire(robot, view.pendingBar.continueButton)
        pane = awaitDialog(robot)
        assertEquals("Continue Rebase", (pane.scene.window as Stage).title)
        assertEquals(
            "bin.dat — binary, the version on disk will be committed",
            (pane.lookup(".git-confirmation-details") as TextArea).text,
        )
        press(robot, pane, COMMIT_AS_ON_DISK_BUTTON)
        eventually { assertEquals("Rebased feature.", status(robot)) }
        assertEquals(
            listOf("rebase --continue", "rebase --continue", "rebase --continue as on disk"),
            writes.calls,
        )
    }
}
