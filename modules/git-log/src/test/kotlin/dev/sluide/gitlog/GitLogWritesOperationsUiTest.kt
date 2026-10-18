package dev.sluide.gitlog

import dev.sluide.git.GitFetchChanges
import dev.sluide.git.GitFetchResult
import dev.sluide.git.GitResult
import javafx.scene.control.ButtonType
import javafx.scene.control.TextArea
import javafx.scene.input.KeyCode
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UI tests of the git log's writes: checkout, fetch and how failures and cancelling show. Needs a display, see the
 * `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesOperationsUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `the table's write actions need exactly one commit and check out a revision on the write thread`(
        robot: FxRobot,
    ) {
        openAndExpand(robot)
        val items =
            listOf(
                view.table.checkoutRevisionItem,
                view.table.newBranchItem,
                view.table.newTagItem,
                view.table.cherryPickItem,
                view.table.revertItem,
                view.table.resetItem,
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
                null,
                "Go to Hash/Branch/Tag\u2026",
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
        fireLater(view.table.checkoutRevisionItem)

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

        fireLater(view.table.checkoutRevisionItem)

        eventually { assertEquals("Checking out h3\u2026", status(robot)) }
        assertTrue(view.table.checkoutRevisionItem.isDisable)
        assertTrue(view.table.newBranchItem.isDisable)
        assertTrue(view.table.newTagItem.isDisable)
        assertTrue(view.table.cherryPickItem.isDisable)
        assertTrue(view.table.revertItem.isDisable)
        assertTrue(view.table.resetItem.isDisable)
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
        assertFalse(view.table.checkoutRevisionItem.isDisable)
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
        assertTrue(view.table.checkoutRevisionItem.isDisable)

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
}
