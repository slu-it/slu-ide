package dev.sluide.gitlog

import dev.sluide.git.GitPendingRebase
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitResult
import dev.sluide.gitlog.operations.CHECKOUT_BUTTON
import dev.sluide.gitlog.operations.CREATE_BUTTON
import dev.sluide.gitlog.operations.DELETE_BUTTON
import dev.sluide.gitlog.operations.FORCE_DELETE_BUTTON
import dev.sluide.gitlog.operations.OVERWRITE_BUTTON
import dev.sluide.gitlog.operations.RENAME_BUTTON
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
import java.util.concurrent.CountDownLatch
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
                "Rebase Current Branch onto\u2026",
                "Merge into Current Branch\u2026",
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
        assertEquals("C B N R D", paneActions())
        assertEquals("New Branch from 'feature/a'\u2026", branches.newBranchItem.text)
        assertEquals("Rebase 'main' onto 'feature/a'", branches.rebaseItem.text)
        // The rest of the matrix is RefOperationsTest's and BranchMenuTest's.
        selectBranches(robot, "Local/feature")
        assertEquals("- - - - -", paneActions(), "a folder")
        assertEquals("New Branch\u2026", branches.newBranchItem.text)
        selectBranches(robot, "Local/feature/a", "Tags/v0.9")
        assertEquals("- - - - D", paneActions())
    }

    @Test
    fun `rebases the current branch onto the selected one, not while a rebase is pending or a write runs`(
        robot: FxRobot,
    ) {
        writes.ancestry = { _, _ -> false }
        openAndExpand(robot)
        selectBranches(robot, "Local/feature/a")

        fireLater(branches.rebaseItem)

        eventually {
            assertEquals(listOf("rebase merges refs/heads/feature/a", "rebase main refs/heads/feature/a"), writes.calls)
        }
        eventually { assertEquals("B", paneActions().split(" ")[1]) }

        // Not while a rebase waits for its next step.
        pending = GitPendingRebase("main", "h3", "h9", 1, 2, listOf("a.txt"))
        writes.inProgress = pending
        robot.interact { view.refresh() }
        eventually { assertTrue(view.pendingBar.isVisible) }
        eventually { assertEquals("C - N R D", paneActions()) }
        assertEquals("Rebase 'main' onto 'feature/a'", branches.rebaseItem.text, "the target stays named")

        pending = null
        writes.inProgress = null
        robot.interact { view.refresh() }
        eventually { assertEquals("C B N R D", paneActions()) }

        // Not while a write runs.
        writes.gate = CountDownLatch(1)
        fireLater(branches.checkoutItem)
        eventually { assertEquals("- - - - -", paneActions()) }
        writes.gate?.countDown()
        eventually { assertEquals("C B N R D", paneActions()) }
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
    fun `checking out a remote branch whose local branch is behind asks to overwrite it, Cancel the default`(
        robot: FxRobot,
    ) {
        openAndExpand(robot)
        writes.localBranches = setOf("old")
        writes.tips = mapOf("old" to "h-old")
        writes.ancestry = { ancestor, _ -> ancestor == "h-old" }

        selectBranches(robot, "Remote/origin/old")
        fireLater(branches.checkoutItem)
        var question = awaitDialog(robot)
        assertEquals("Overwrite Branch", (question.scene.window as Stage).title)
        assertEquals(
            "Local branch 'old' is behind origin/old. Overwrite it with origin/old and check it out?",
            question.headerText,
        )
        assertTrue((question.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, question, ButtonType.CANCEL)
        assertEquals(emptyList(), writes.calls, "Cancel checks out nothing")

        writes.leftBehind = 1
        fireLater(branches.checkoutItem)
        question = awaitDialog(robot)
        press(robot, question, OVERWRITE_BUTTON)
        question = awaitDialog(robot)
        assertEquals("Check Out", (question.scene.window as Stage).title)
        assertTrue(question.headerText.endsWith("Check out old anyway?"))
        press(robot, question, CHECKOUT_BUTTON)
        eventually { assertEquals(listOf("switch -C old refs/remotes/origin/old"), writes.calls) }
        eventually { assertEquals("Overwrote old with origin/old and checked it out.", status(robot)) }
        assertEquals(2, beforeCheckoutCalls, "unsaved editors asked once per checkout, not again on confirming")
    }

    @Test
    fun `a checkout that leaves commits of a detached HEAD behind asks first, Cancel the default`(robot: FxRobot) {
        openAndExpand(robot)
        writes.leftBehind = 2

        selectBranches(robot, "Tags/v0.9")
        fireLater(branches.checkoutItem)
        var question = awaitDialog(robot)
        assertEquals("Check Out", (question.scene.window as Stage).title)
        assertEquals(
            "2 commits of the detached HEAD will no longer be on any branch or tag; they stay reachable through the " +
                "reflog for a while.\nTo keep them, cancel and create a branch at HEAD first. Check out v0.9 anyway?",
            question.headerText,
        )
        assertNoBandBelowQuestion(question)
        assertTrue((question.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, question, ButtonType.CANCEL)
        assertEquals(emptyList(), writes.calls, "Cancel checks out nothing")

        fireLater(branches.checkoutItem)
        question = awaitDialog(robot)
        press(robot, question, CHECKOUT_BUTTON)
        eventually { assertEquals(listOf("switch --detach refs/tags/v0.9"), writes.calls) }
        eventually { assertEquals("Checked out v0.9.", status(robot)) }
        assertEquals(2, beforeCheckoutCalls, "unsaved editors asked once per checkout, not again on confirming")
    }

    @Test
    fun `the new branch dialog checks the name and creates the branch at the commit, checked out or not`(
        robot: FxRobot,
    ) {
        openAndExpand(robot)
        selectRows(robot, 1)

        fireLater(view.table.newBranchItem)
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
        // The other texts of name problems are RefOperationsTest's.
        type("x")
        assertFalse(create.isDisable)
        robot.interact { checkout.isSelected = false }
        press(robot, pane, CREATE_BUTTON)

        eventually { assertEquals("Created branch x.", status(robot)) }
        assertEquals(listOf("branch x h2"), writes.calls)
        assertEquals(0, beforeCheckoutCalls, "no checkout")

        fireLater(view.table.newBranchItem)
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
        fireLater(view.table.newBranchItem)
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

        fireLater(view.table.newTagItem)
        var pane = awaitDialog(robot)
        val dialog = NameControls(pane)
        assertEquals("New Tag", (pane.scene.window as Stage).title)
        robot.interact { dialog.field.text = "v1.0" }
        assertEquals("Tag 'v1.0' already exists", dialog.problem.text)
        assertTrue(pane.lookupButton(CREATE_BUTTON).isDisable)
        robot.interact { dialog.field.text = "v2" }
        press(robot, pane, CREATE_BUTTON)
        eventually { assertEquals("Created tag v2.", status(robot)) }

        fireLater(view.table.newTagItem)
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
    fun `Delete of remote branches asks first, Cancel the default, then deletes them on their remote`(robot: FxRobot) {
        currentRefs =
            GitRefs(refs.refs + GitRef("refs/remotes/origin/fix", GitRefKind.REMOTE_BRANCH, "h3"), refs.currentBranch)
        openAndExpand(robot)
        selectBranches(robot, "Remote/origin/old")

        pressInTree(KeyCode.DELETE)
        var pane = awaitDialog(robot)
        assertEquals("Delete", (pane.scene.window as Stage).title)
        assertEquals("Delete remote branch 'origin/old'?", pane.headerText)
        assertEquals("remote branch origin/old", (pane.lookup(".git-delete-names") as TextArea).text)
        assertTrue(pane.lookupAll(".git-delete-remote").isEmpty(), "no tag checkboxes")
        assertTrue((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        assertFalse((pane.lookupButton(DELETE_BUTTON) as Button).isDefaultButton)
        press(robot, pane, ButtonType.CANCEL)
        assertEquals(emptyList(), writes.calls)

        selectBranches(robot, "Remote/origin/main", "Remote/origin/old")
        assertEquals("- - - - -", paneActions(), "origin/main is protected")
        selectBranches(robot, "Remote/origin/fix", "Remote/origin/old")
        fireLater(branches.deleteItem)
        pane = awaitDialog(robot)
        assertEquals("Delete 2 remote branches?", pane.headerText)
        assertEquals(
            "remote branch origin/fix\nremote branch origin/old",
            (pane.lookup(".git-delete-names") as TextArea).text,
        )
        press(robot, pane, DELETE_BUTTON)

        eventually { assertEquals("Deleted 2 remote branches.", status(robot)) }
        assertEquals(listOf("push --delete origin refs/heads/fix:h3 refs/heads/old:h4"), writes.calls)
        assertEquals(setOf("sluide-git-write"), writes.threads.toSet(), "the mapping and the push off the FX thread")
        assertEquals(0, beforeCheckoutCalls, "no question about unsaved editors")

        // Not with local branches or tags in the selection.
        selectBranches(robot, "Remote/origin/old", "Local/feature/a")
        assertEquals("- - - - -", paneActions())
        pressInTree(KeyCode.DELETE)
        assertNoDialog()
    }

    @Test
    fun `tags get an unchecked checkbox per remote, and a checked one deletes them there first`(robot: FxRobot) {
        currentRemotes = listOf("origin", "upstream")
        openAndExpand(robot)
        selectBranches(robot, "Tags/v0.9")

        fireLater(branches.deleteItem)
        val pane = awaitDialog(robot)
        assertEquals("Delete tag 'v0.9'?", pane.headerText)
        val boxes = pane.lookupAll(".git-delete-remote").map { it as CheckBox }
        assertEquals(listOf("Also delete the tag on origin", "Also delete the tag on upstream"), boxes.map { it.text })
        assertTrue(boxes.none { it.isSelected }, "unchecked by default")
        assertTrue((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        robot.interact { boxes.first().isSelected = true }
        press(robot, pane, DELETE_BUTTON)

        eventually { assertEquals("Deleted tag v0.9. Also deleted on origin.", status(robot)) }
        assertEquals(listOf("push --delete origin refs/tags/v0.9", "tag -d v0.9"), writes.calls)
        assertEquals(setOf("sluide-git-write"), writes.threads.toSet())

        // Several tags: "the tags".
        selectBranches(robot, "Tags/v0.9", "Tags/v1.0")
        fireLater(branches.deleteItem)
        val tagsPane = awaitDialog(robot)
        assertEquals(
            listOf("Also delete the tags on origin", "Also delete the tags on upstream"),
            tagsPane.lookupAll(".git-delete-remote").map { (it as CheckBox).text },
        )
        press(robot, tagsPane, ButtonType.CANCEL)

        // Only branches: no checkboxes.
        selectBranches(robot, "Local/feature/a")
        fireLater(branches.deleteItem)
        val branchPane = awaitDialog(robot)
        assertTrue(branchPane.lookupAll(".git-delete-remote").isEmpty())
        press(robot, branchPane, ButtonType.CANCEL)
    }

    @Test
    fun `Cancel in the header stops a remote delete, which may have reached the remote`(robot: FxRobot) {
        openAndExpand(robot)
        selectBranches(robot, "Remote/origin/old")
        writes.gate = CountDownLatch(1)

        fireLater(branches.deleteItem)
        press(robot, awaitDialog(robot), DELETE_BUTTON)
        eventually { assertTrue(cancelShown()) }
        robot.interact { view.cancelButton.fire() }

        eventually { assertEquals("Delete cancelled; it may have reached origin. Fetch to check.", status(robot)) }
        assertEquals(listOf("push --delete origin refs/heads/old:h4"), writes.calls)
        assertEquals(setOf("sluide-git-write"), writes.threads.toSet())
        assertFalse(cancelShown())
        assertNoDialog()
    }
}
