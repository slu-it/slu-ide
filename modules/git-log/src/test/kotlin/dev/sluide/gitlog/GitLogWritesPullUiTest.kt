package dev.sluide.gitlog

import dev.sluide.git.GitPullSources
import dev.sluide.gitlog.operations.CheckoutBranch
import dev.sluide.gitlog.operations.PULL_BUTTON
import dev.sluide.gitlog.operations.PULL_WAITS
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.DialogPane
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

/** UI tests of pulling in the git log (ADR 0106). Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesPullUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private val main = "refs/remotes/origin/main"
    private val old = "refs/remotes/origin/old"

    private fun DialogPane.radio(name: String) = lookup(".git-pull-$name") as RadioButton

    @Suppress("UNCHECKED_CAST")
    private val DialogPane.from get() = lookup(".git-pull-from") as ComboBox<String>

    private val DialogPane.stash get() = lookup(".git-pull-autostash") as CheckBox

    private fun DialogPane.merge(mode: String) = lookup(".git-merge-$mode") as RadioButton

    private fun pullDisabled(pane: DialogPane) = (pane.lookupButton(PULL_BUTTON) as Button).isDisabled

    private fun sources(upstream: String?) {
        writes.pullSources = GitPullSources("main", true, listOf(old, main), listOf("origin"), upstream, null)
        // Neither contained in HEAD nor HEAD behind: a real merge or rebase.
        writes.ancestry = { _, _ -> false }
    }

    @Test
    fun `the header's Pull opens the dialog, Merge enables its options, and Pull rebases by default`(robot: FxRobot) {
        sources(upstream = main)
        openAndExpand(robot)
        robot.interact { Platform.runLater { view.pullButton.fire() } }

        val dialog = awaitDialog(robot)
        assertEquals("Pull", (dialog.scene.window as Stage).title)
        assertEquals("Pull into 'main'", dialog.headerText)
        assertEquals(main, dialog.from.value, "the upstream")
        assertEquals(listOf("origin/main", "origin/old"), dialog.from.items.map(dialog.from.converter::toString))
        assertTrue(dialog.radio("rebase").isSelected)
        val options = dialog.lookup(".git-merge-options")
        assertTrue(options.isDisabled, "the merge options while Rebase is chosen")
        assertTrue(dialog.stash.isSelected)

        robot.interact { dialog.radio("merge").isSelected = true }
        assertFalse(options.isDisabled)
        val message = dialog.lookup(".git-merge-message") as TextArea
        assertEquals("Merge remote-tracking branch 'origin/main'", message.text)
        robot.interact { dialog.from.value = old }
        assertEquals("Merge remote-tracking branch 'origin/old'", message.text, "follows the branch")
        robot.interact { dialog.merge("squash").isSelected = true }
        assertTrue(dialog.stash.isDisabled)
        assertFalse(dialog.stash.isSelected)
        robot.interact { dialog.radio("rebase").isSelected = true }
        assertTrue(options.isDisabled)
        assertFalse(dialog.stash.isDisabled, "a rebase stashes")
        assertTrue(dialog.stash.isSelected)
        assertFalse(pullDisabled(dialog))
        press(robot, dialog, PULL_BUTTON)

        eventually { assertEquals("Pulled origin/old into main (rebased 1 commit).", status(robot)) }
        assertEquals(
            listOf("fetch origin", "rebase merges $old", "rebase --fork-point main $old autostash"),
            writes.calls,
        )
        assertEquals(listOf("Pull"), fileChanges)
    }

    @Test
    fun `Pull on a remote branch in the branches pane opens the dialog with it chosen and merges it`(robot: FxRobot) {
        sources(upstream = null)
        openAndExpand(robot)
        selectBranches(robot, "Remote/origin/old")
        assertEquals("Pull 'origin/old' into 'main'…", branches.pullItem.text)
        assertFalse(branches.pullItem.isDisable)
        fireLater(branches.pullItem)

        val dialog = awaitDialog(robot)
        assertEquals(old, dialog.from.value)
        robot.interact { dialog.radio("merge").isSelected = true }
        press(robot, dialog, PULL_BUTTON)

        eventually { assertEquals("Pulled origin/old into main (merge commit).", status(robot)) }
        assertEquals(listOf("fetch origin", "merge main $old fast_forward autostash"), writes.calls)
    }

    @Test
    fun `without an upstream nothing is chosen and Pull waits for a branch`(robot: FxRobot) {
        sources(upstream = null)
        openAndExpand(robot)
        robot.interact { Platform.runLater { view.showPull() } }

        val dialog = awaitDialog(robot)
        assertEquals(null, dialog.from.value)
        assertTrue(pullDisabled(dialog))
        robot.interact { dialog.from.value = main }
        assertFalse(pullDisabled(dialog))
        robot.interact { (dialog.scene.window as Stage).close() }
        assertTrue(writes.calls.isEmpty())
    }

    @Test
    fun `Pull while another git operation runs says to wait`(robot: FxRobot) {
        sources(upstream = main)
        openAndExpand(robot)
        writes.gate = CountDownLatch(1)
        robot.interact { view.actions.start(CheckoutBranch("feature/a")) }

        robot.interact { view.showPull() }

        assertEquals(PULL_WAITS, status(robot))
        writes.gate?.countDown()
        eventually { assertEquals("Checked out feature/a.", status(robot)) }
        assertNoDialog()
        assertTrue(writes.reads.none { it == "pull sources" })
    }
}
