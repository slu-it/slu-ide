package dev.sluide.gitlog

import dev.sluide.git.GitPendingConflicts
import dev.sluide.git.GitStash
import dev.sluide.git.GitStashResult
import dev.sluide.gitlog.operations.DROP_STASH_BUTTON
import dev.sluide.gitlog.operations.STASH_BUTTON
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI tests of stashes in the git log (ADR 0107). Needs a display, see the `uiTest` task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesStashUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private val stash = GitStash(0, "c0ffee", 1_700_000_000, "On main: work in progress")

    private fun menuTexts() =
        WaitForAsyncUtils
            .asyncFx<List<String?>> {
                branches.tree.contextMenu.items
                    .map { it.text }
            }.get()

    /** Expands the log with [stash] and selects it in the branches pane. */
    private fun selectStash(robot: FxRobot) {
        currentStashes = listOf(stash)
        openAndExpand(robot)
        eventually { branchItem("Stashes/c0ffee") }
        selectBranches(robot, "Stashes/c0ffee")
    }

    @Test
    fun `a stash's menu pops it, without navigating the log, and conflicts are told in a dialog`(robot: FxRobot) {
        selectStash(robot)
        val selectedRows =
            WaitForAsyncUtils.asyncFx<List<Int>> {
                view.table.selectionModel.selectedIndices
                    .toList()
            }

        assertEquals(
            listOf("Pop Stash", "Apply Stash", "Drop Stash…", null, "Copy Message", null, "Stash Changes…"),
            menuTexts(),
        )
        assertEquals(emptyList(), selectedRows.get(), "a stash is not in the log")
        val iconSizes =
            WaitForAsyncUtils.asyncFx<List<Int>> {
                branches.tree.lookupAll(".git-stash-icon").map { (it as FontIcon).iconSize }
            }
        assertEquals(listOf(14), iconSizes.get().distinct(), "sized like the other icons of the pane")
        fireLater(branches.menu.stash.popItem)

        eventually { assertEquals("Popped stash@{0} (On main: work in progress).", status(robot)) }
        assertEquals(listOf("stash pop stash@{0} c0ffee"), writes.stash.calls)
        assertEquals(listOf("Pop"), fileChanges)

        writes.stash.result = { GitStashResult.Conflicts(listOf("a.txt", "b.txt"), kept = true) }
        selectBranches(robot, "Stashes/c0ffee")
        fireLater(branches.menu.stash.applyItem)
        val pane = awaitDialog(robot)
        assertEquals("Stash Conflicts", (pane.scene.window as Stage).title)
        assertEquals("Applying stash@{0} gave conflicts in:", pane.headerText)
        assertEquals("a.txt\nb.txt", (pane.content.lookup(".git-conflict-paths") as TextArea).text)
        press(robot, pane, ButtonType.OK)
        eventually {
            assertEquals("Applying stash@{0} gave conflicts in 2 files; the stash stays in the list.", status(robot))
        }

        // Branches have their own items again.
        selectBranches(robot, "Local/main")
        assertEquals("Filter Log by Branch", menuTexts().first())
    }

    @Test
    fun `Pop Latest Stash reads the stashes, asks about unsaved editors, then pops the newest`(robot: FxRobot) {
        writes.stash.stashes = listOf(stash, stash.copy(index = 1, commit = "older"))
        openAndExpand(robot)

        // Cancelled at the question about unsaved editors: read, nothing popped.
        beforeFileChange += false
        robot.interact { view.popLatestStash() }
        eventually { assertEquals(listOf("Pop"), fileChanges) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(listOf("stash list"), writes.stash.calls)

        robot.interact { view.popLatestStash() }
        eventually { assertEquals("Popped stash@{0} (On main: work in progress).", status(robot)) }
        assertEquals(listOf("stash list", "stash list", "stash pop stash@{0} c0ffee"), writes.stash.calls)
        assertEquals(listOf("Pop", "Pop"), fileChanges)
    }

    @Test
    fun `dropping a stash asks first, Cancel the default`(robot: FxRobot) {
        selectStash(robot)

        fireLater(branches.menu.stash.dropItem)
        val question = awaitDialog(robot)
        assertEquals("Drop stash@{0} (On main: work in progress)? Its changes are lost.", question.headerText)
        assertTrue((question.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, question, ButtonType.CANCEL)
        assertTrue(writes.stash.calls.isEmpty())

        selectBranches(robot, "Stashes/c0ffee")
        fireLater(branches.menu.stash.dropItem)
        press(robot, awaitDialog(robot), DROP_STASH_BUTTON)
        eventually { assertEquals("Dropped stash@{0}.", status(robot)) }
        assertEquals(listOf("stash drop stash@{0} c0ffee"), writes.stash.calls)
        assertEquals(emptyList(), fileChanges, "no files change")
    }

    @Test
    fun `Stash Changes on the Stashes group asks for a message and untracked files, then stashes`(robot: FxRobot) {
        selectStash(robot)
        selectBranches(robot, "Stashes")
        assertTrue(branches.menu.stash.popItem.isDisable)

        fireLater(branches.menu.stash.stashChangesItem)
        val dialog = awaitDialog(robot)
        assertEquals("Stash Changes", (dialog.scene.window as Stage).title)
        assertEquals("Stash local changes of 'main'", dialog.headerText)
        val message = dialog.lookup(".git-stash-message") as TextField
        val untracked = dialog.lookup(".git-stash-untracked") as CheckBox
        assertEquals("Message (optional)", message.promptText)
        assertTrue(untracked.isVisible)
        assertFalse(untracked.isSelected)
        assertTrue((dialog.lookupButton(STASH_BUTTON) as Button).isDefaultButton)
        robot.interact {
            message.text = " Half done "
            untracked.isSelected = true
        }
        press(robot, dialog, STASH_BUTTON)

        eventually { assertEquals("Stashed local changes as stash@{0}.", status(robot)) }
        assertEquals(listOf("stash sources", "stash push -u -m Half done"), writes.stash.calls)
        assertEquals(listOf("Stash"), fileChanges)
    }

    @Test
    fun `files with conflicts outside an operation are marked resolved from the bar`(robot: FxRobot) {
        pending = GitPendingConflicts(listOf("a.txt", "b.txt"))
        writes.inProgress = pending
        openAndExpand(robot)

        eventually { assertTrue(view.pendingBar.isVisible) }
        assertEquals("2 files have conflicts.", (view.pendingBar.lookup(".git-log-pending-text") as Label).text)
        assertTrue(view.pendingBar.markResolvedButton.isVisible)
        assertFalse(view.pendingBar.continueButton.isVisible)
        assertFalse(view.pendingBar.abortButton.isVisible)
        // Busy: nothing that would be refused is offered.
        selectBranches(robot, "Local/feature/a")
        assertTrue(branches.menu.mergeItem.isDisable)

        pending = null
        robot.interact { Platform.runLater { view.pendingBar.markResolvedButton.fire() } }
        eventually { assertEquals("Marked 1 file resolved.", status(robot)) }
        assertEquals(listOf("mark resolved"), writes.stash.calls)
        assertEquals(listOf("Mark Resolved"), fileChanges)
        eventually { assertFalse(view.pendingBar.isVisible) }
    }
}
