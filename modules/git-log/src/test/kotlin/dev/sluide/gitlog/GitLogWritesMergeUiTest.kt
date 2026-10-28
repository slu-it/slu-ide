package dev.sluide.gitlog

import dev.sluide.git.GitMergeResult
import dev.sluide.git.GitPendingMerge
import dev.sluide.gitlog.operations.ABORT_MERGE_BUTTON
import dev.sluide.gitlog.operations.ABORT_MERGE_QUESTION
import dev.sluide.gitlog.operations.MERGE_BUTTON
import dev.sluide.gitlog.operations.RESOLVE_BUTTON
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.TextArea
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI tests of merging in the git log (ADR 0105). Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesMergeUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private fun fire(
        robot: FxRobot,
        button: Button,
    ) = robot.interact { Platform.runLater { button.fire() } }

    private fun DialogPane.radio(mode: String) = lookup(".git-merge-$mode") as RadioButton

    private val DialogPane.message get() = lookup(".git-merge-message") as TextArea

    /** Opens the Merge dialog for feature/a from the branches pane. */
    private fun openDialog(robot: FxRobot): DialogPane {
        openAndExpand(robot)
        selectBranches(robot, "Local/feature/a")
        assertEquals("Merge 'feature/a' into 'main'…", branches.mergeItem.text)
        fireLater(branches.mergeItem)
        return awaitDialog(robot)
    }

    @Test
    fun `merges the selected branch as chosen in the dialog and tells how`(robot: FxRobot) {
        val dialog = openDialog(robot)

        assertEquals("Merge", (dialog.scene.window as Stage).title)
        assertEquals("Merge 'feature/a' into 'main'", dialog.headerText)
        assertTrue(dialog.radio("fast-forward").isSelected)
        assertEquals("Merge branch 'feature/a'", dialog.message.text)
        assertTrue((dialog.lookup(".git-merge-autostash") as CheckBox).isSelected)
        assertTrue((dialog.lookupButton(MERGE_BUTTON) as Button).isDefaultButton)
        // The message is disabled where the mode commits nothing of its own, the stash for a squash.
        val stash = dialog.lookup(".git-merge-autostash") as CheckBox
        val modes = listOf("squash" to "message stash", "fast-forward-only" to "message", "merge-commit" to "")
        for ((mode, disabled) in modes) {
            robot.interact { dialog.radio(mode).isSelected = true }
            val shown = listOf("message" to dialog.message.isDisable, "stash" to stash.isDisable)
            assertEquals(disabled, shown.filter { it.second }.joinToString(" ") { it.first }, mode)
            // A squash never stashes, so its box is shown unchecked; the next mode has the choice again.
            assertEquals(mode != "squash", stash.isSelected, mode)
        }
        robot.interact {
            stash.isSelected = false
            dialog.radio("squash").isSelected = true
            dialog.radio("merge-commit").isSelected = true
        }
        assertFalse(stash.isSelected, "an unchecked box stays unchecked after a squash")
        robot.interact {
            dialog.message.text = "Merge feature/a for the release"
            (dialog.lookup(".git-merge-autostash") as CheckBox).isSelected = false
        }
        press(robot, dialog, MERGE_BUTTON)

        eventually { assertEquals("Merged feature/a into main.", status(robot)) }
        assertEquals(
            listOf("merge main refs/heads/feature/a merge_commit -m Merge feature/a for the release"),
            writes.calls,
        )
        assertEquals(listOf("Merge"), fileChanges)
    }

    @Test
    fun `a merge that stops shows its conflicts, and Abort Merge there aborts without asking`(robot: FxRobot) {
        val stopped = GitPendingMerge(listOf("h3"), "Merge branch 'feature/a'", listOf("a.txt", "b.txt"))
        writes.mergeResult = { line ->
            if (line == "merge --abort") GitMergeResult.Done("h1") else GitMergeResult.Stopped(stopped, "CONFLICT")
        }
        press(robot, openDialog(robot), MERGE_BUTTON)

        val pane = awaitDialog(robot)
        assertEquals("Merge Stopped", (pane.scene.window as Stage).title)
        assertEquals("Merge of feature/a into main stopped with conflicts in:", pane.headerText)
        assertEquals("a.txt\nb.txt", (pane.content.lookup(".git-conflict-paths") as TextArea).text)
        assertTrue((pane.lookupButton(RESOLVE_BUTTON) as Button).isDefaultButton)
        press(robot, pane, ABORT_MERGE_BUTTON)

        eventually { assertEquals("Aborted the merge.", status(robot)) }
        assertEquals("merge --abort", writes.calls.last())
        assertEquals(listOf("Merge", "Abort"), fileChanges)
    }

    @Test
    fun `a bar shows a merge in progress with continue and abort, an octopus one without`(robot: FxRobot) {
        pending = GitPendingMerge(listOf("h3"), "Merge branch 'feature/a'", listOf("a.txt", "b.txt"))
        writes.inProgress = pending
        openAndExpand(robot)

        eventually { assertTrue(view.pendingBar.isVisible) }
        val text = view.pendingBar.lookup(".git-log-pending-text") as Label
        assertEquals("Merge branch 'feature/a': 2 files have conflicts.", text.text)
        assertTrue(view.pendingBar.continueButton.isVisible)
        assertTrue(view.pendingBar.abortButton.isVisible)
        assertFalse(view.pendingBar.skipButton.isVisible)

        // Abort asks first; Cancel, the default, aborts nothing.
        fire(robot, view.pendingBar.abortButton)
        val question = awaitDialog(robot)
        assertEquals("Abort Merge", (question.scene.window as Stage).title)
        assertEquals(ABORT_MERGE_QUESTION, question.headerText)
        assertTrue((question.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, question, ButtonType.CANCEL)
        assertTrue(writes.calls.isEmpty())
        fire(robot, view.pendingBar.abortButton)
        press(robot, awaitDialog(robot), ABORT_MERGE_BUTTON)
        eventually { assertEquals("Aborted the merge.", status(robot)) }
        assertEquals(listOf("merge --abort"), writes.calls)
        writes.calls.clear()
        fileChanges.clear()

        pending = null
        fire(robot, view.pendingBar.continueButton)
        eventually { assertEquals("Merged feature/a into main.", status(robot)) }
        assertEquals(listOf("merge --continue"), writes.calls)
        assertEquals(listOf("Continue"), fileChanges)
        eventually { assertFalse(view.pendingBar.isVisible) }

        pending = GitPendingMerge(listOf("h3", "h5"), "Merge branches 'feature/a' and 'feature/b'", emptyList())
        robot.interact { view.refresh() }
        eventually { assertTrue(view.pendingBar.isVisible) }
        assertEquals("An octopus merge is in progress; finish or abort it in the terminal.", text.text)
        assertFalse(view.pendingBar.continueButton.isVisible)
        assertFalse(view.pendingBar.abortButton.isVisible)
    }
}
