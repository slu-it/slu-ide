package dev.sluide.gitlog

import dev.sluide.git.GitPendingRebase
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.branches.BranchCommands
import dev.sluide.gitlog.branches.branchMenu
import dev.sluide.gitlog.operations.CheckoutRemote
import dev.sluide.gitlog.operations.DELETE_BUTTON
import dev.sluide.gitlog.operations.RebaseBranch
import dev.sluide.gitlog.operations.RefOperation
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.Clipboard
import javafx.stage.Stage
import javafx.stage.WindowEvent
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
import kotlin.test.assertTrue

/** UI tests of the branch submenus in the commit table's context menu (ADR 0063). Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesBranchMenuUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** Opens the table's menu as the user does and returns its submenus. */
    private fun menus(): List<Menu> =
        WaitForAsyncUtils
            .asyncFx<List<Menu>> {
                val menu = view.table.contextMenu
                Event.fireEvent(menu, WindowEvent(menu, WindowEvent.WINDOW_SHOWING))
                menu.items.filterIsInstance<Menu>()
            }.get()

    private fun Menu.item(id: String): MenuItem = items.single { it.id == id }

    /** Which of `branch-checkout`, rebase, compare and delete are enabled, as `C B P D` or `-`. */
    private fun Menu.enabled(): String =
        listOf("branch-checkout" to "C", "branch-rebase" to "B", "branch-compare" to "P", "branch-delete" to "D")
            .joinToString(" ") { (id, letter) -> if (item(id).isDisable) "-" else letter }

    private fun fire(item: MenuItem) {
        Platform.runLater { item.fire() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    @Test
    fun `a commit shows a submenu for each local and remote branch, not for HEAD or tags, once`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 0)

        assertEquals(listOf("main", "origin/main"), menus().map { it.text })
        assertTrue(menus().all { "git-branch-menu" in it.styleClass })
        val items = view.table.contextMenu.items
        assertTrue(items[2] is SeparatorMenuItem, "a separator after the submenus")
        assertEquals("Copy Revision Number", items[3].text)
        assertEquals(
            listOf(
                "branch-filter",
                null,
                "branch-copy-name",
                null,
                "branch-compare",
                "branch-working-tree-diff",
                null,
                "branch-checkout",
                "branch-rebase",
                "branch-new-branch",
                "branch-rename",
                "branch-delete",
            ),
            menus().first().items.map { it.id },
        )
        assertEquals("New Branch from 'origin/main'\u2026", menus()[1].item("branch-new-branch").text)
        val size = view.table.contextMenu.items.size
        menus()
        assertEquals(size, view.table.contextMenu.items.size, "not added again when it opens again")
        assertEquals(listOf("branch-fetch"), listOf(menus()[1].items.last().id))

        selectRows(robot, 1)
        assertTrue(menus().isEmpty(), "a tag only")
        assertEquals(
            "Copy Revision Number",
            view.table.contextMenu.items
                .first()
                .text,
            "no leading separator",
        )

        selectRows(robot, 0, 2)
        assertTrue(menus().isEmpty(), "several commits")
        selectRows(robot, 2)
        assertEquals(listOf("feature/a"), menus().map { it.text })
    }

    @Test
    fun `the current branch cannot be checked out, compared, deleted or rebased`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 0)

        val (main, remote) = menus()
        assertEquals("- - - -", main.enabled())
        assertFalse(main.item("branch-rename").isDisable)
        assertEquals("- - P -", remote.enabled(), "its local branch is the current one, it is at HEAD, protected")
        assertEquals("Rebase 'main' onto 'origin/main'", remote.item("branch-rebase").text)
    }

    @Test
    fun `rebase and checkout of another branch dispatch, and filter sets the branch filter`(robot: FxRobot) {
        writes.ancestry = { _, _ -> false }
        openAndExpand(robot)
        selectRows(robot, 2)

        val menu = menus().single()
        assertEquals("C B P D", menu.enabled())
        assertEquals("Rebase 'main' onto 'feature/a'", menu.item("branch-rebase").text)

        fire(menu.item("branch-filter"))
        eventually {
            assertTrue(
                view.filterBar.branch.label.text
                    .contains("feature/a"),
                view.filterBar.branch.label.text,
            )
        }

        fire(menu.item("branch-rebase"))
        eventually {
            assertEquals(listOf("rebase merges refs/heads/feature/a", "rebase main refs/heads/feature/a"), writes.calls)
        }
        eventually { assertFalse(view.pendingBar.busy.get()) }

        selectRows(robot, 4)
        val before = writes.calls.size
        fire(menus().single().item("branch-checkout"))
        eventually { assertEquals("switch feature/b", writes.calls.last()) }
        assertEquals(before + 1, writes.calls.size)
    }

    @Test
    fun `writes are disabled while one runs and the rebase while one is pending`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 2)
        writes.gate = CountDownLatch(1)
        fire(menus().single().item("branch-checkout"))
        eventually { assertTrue(view.actions.running.get()) }

        val running = menus().single()
        assertEquals("- - P -", running.enabled(), "compare still reads")
        assertTrue(running.item("branch-rename").isDisable)
        assertTrue(running.item("branch-new-branch").isDisable)
        assertFalse(running.item("branch-working-tree-diff").isDisable)
        writes.gate?.countDown()
        eventually { assertFalse(view.actions.running.get()) }

        pending = GitPendingRebase("main", "h3", "h9", 1, 2, listOf("a.txt"))
        writes.inProgress = pending
        robot.interact { view.refresh() }
        eventually { assertTrue(view.pendingBar.isVisible) }
        selectRows(robot, 2)
        val waiting = menus().single()
        assertTrue(waiting.item("branch-rebase").isDisable)
        assertFalse(waiting.item("branch-checkout").isDisable)
    }

    @Test
    fun `every item acts on the branch of its submenu and names with underscores keep them`(robot: FxRobot) {
        val ref = GitRef("refs/remotes/origin/JIRA_1", GitRefKind.REMOTE_BRANCH, "h4")
        val calls = mutableListOf<String>()
        val operations = mutableListOf<RefOperation>()
        val commands =
            BranchCommands().apply {
                filter = { calls += "filter $it" }
                checkout = {
                    calls += "checkout"
                    operations += it
                }
                newBranchFrom = { calls += "new ${it.name}" }
                rename = { calls += "rename ${it.name}" }
                delete = { calls += "delete $it" }
                deleteRemote =
                    { branches -> calls += "delete remote " + branches.joinToString { "${it.remote} ${it.ref.name}" } }
                fetch = { calls += "fetch $it" }
                compare = { calls += "compare ${it.name}" }
                showWorkingTreeDiff = { calls += "diff ${it.name}" }
                rebase = {
                    calls += "rebase"
                    operations += it
                }
            }
        val all = GitRefs(refs.refs + ref, "refs/heads/main")
        val menu =
            WaitForAsyncUtils.asyncFx<Menu> { branchMenu(ref, all, listOf("origin"), false, false, commands) }.get()

        assertEquals("origin/JIRA_1", menu.text)
        assertFalse(menu.isMnemonicParsing)
        assertTrue(menu.items.filter { it !is SeparatorMenuItem }.all { !it.isMnemonicParsing })
        assertEquals("New Branch from 'origin/JIRA_1'\u2026", menu.item("branch-new-branch").text)
        assertEquals("Rebase 'main' onto 'origin/JIRA_1'", menu.item("branch-rebase").text)

        listOf(
            "filter",
            "copy-name",
            "compare",
            "working-tree-diff",
            "checkout",
            "rebase",
            "new-branch",
            "fetch",
            "delete",
        ).forEach { fire(menu.item("branch-$it")) }
        assertTrue(menu.item("branch-rename").isDisable, "a remote branch is not renamed")
        assertEquals(
            listOf(
                "filter [refs/remotes/origin/JIRA_1]",
                "compare refs/remotes/origin/JIRA_1",
                "diff refs/remotes/origin/JIRA_1",
                "checkout",
                "rebase",
                "new refs/remotes/origin/JIRA_1",
                "fetch origin",
                "delete remote origin refs/remotes/origin/JIRA_1",
            ),
            calls,
        )
        assertEquals(listOf(CheckoutRemote(ref), RebaseBranch("main", ref.name, "origin/JIRA_1")), operations)
        assertEquals(
            "origin/JIRA_1",
            WaitForAsyncUtils.asyncFx<String?> { Clipboard.getSystemClipboard().string }.get(),
        )

        // Rename and Delete of a local branch.
        calls.clear()
        val local = refs.refs.single { it.name == "refs/heads/feature/b" }
        val localMenu =
            WaitForAsyncUtils.asyncFx<Menu> { branchMenu(local, refs, listOf("origin"), false, false, commands) }.get()
        fire(localMenu.item("branch-rename"))
        fire(localMenu.item("branch-delete"))
        assertEquals(listOf("rename refs/heads/feature/b", "delete [$local]"), calls)
    }

    @Test
    fun `Delete in the submenu of a remote branch deletes it on its remote after asking`(robot: FxRobot) {
        currentRefs =
            GitRefs(refs.refs + GitRef("refs/remotes/origin/fix", GitRefKind.REMOTE_BRANCH, "h1"), refs.currentBranch)
        openAndExpand(robot)
        selectRows(robot, 0)

        assertTrue(menus().single { it.text == "origin/main" }.item("branch-delete").isDisable, "protected")
        val remote = menus().single { it.text == "origin/fix" }
        fireLater(remote.item("branch-delete"))
        val pane = awaitDialog(robot)
        assertEquals("Delete remote branch 'origin/fix'?", pane.headerText)
        assertTrue((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, pane, DELETE_BUTTON)

        eventually { assertEquals("Deleted remote branch origin/fix.", status(robot)) }
        assertEquals(listOf("push --delete origin refs/heads/fix:h1"), writes.calls)
        assertEquals(setOf("sluide-git-write"), writes.threads.toSet())
    }
}
