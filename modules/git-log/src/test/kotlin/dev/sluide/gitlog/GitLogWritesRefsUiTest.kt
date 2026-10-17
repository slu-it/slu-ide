package dev.sluide.gitlog

import dev.sluide.git.GitResult
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
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

/**
 * UI tests of the git log's writes: branches and tags: pane actions, new, rename and delete. Needs a display, see the
 * `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesRefsUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

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
        assertNoBandBelowQuestion(question)
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
}
