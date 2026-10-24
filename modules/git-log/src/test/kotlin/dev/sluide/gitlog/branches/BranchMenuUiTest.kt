package dev.sluide.gitlog.branches

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.operations.CheckoutRemote
import dev.sluide.gitlog.operations.RebaseBranch
import dev.sluide.gitlog.operations.RefOperation
import javafx.application.Platform
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.input.Clipboard
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI test of [branchMenu] on its own, without a log view. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class BranchMenuUiTest {
    private val main = GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1")
    private val local = GitRef("refs/heads/feature/b", GitRefKind.LOCAL_BRANCH, "h2")
    private val remote = GitRef("refs/remotes/origin/JIRA_1", GitRefKind.REMOTE_BRANCH, "h4")
    private val refs = GitRefs(listOf(main, local, remote), "refs/heads/main")

    private val calls = mutableListOf<String>()
    private val operations = mutableListOf<RefOperation>()
    private val commands =
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

    private fun menuOf(ref: GitRef): Menu =
        WaitForAsyncUtils.asyncFx<Menu> { branchMenu(ref, refs, listOf("origin"), false, false, commands) }.get()

    private fun Menu.item(id: String): MenuItem = items.single { it.id == id }

    private fun fire(item: MenuItem) {
        Platform.runLater { item.fire() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    @Test
    fun `every item acts on the branch of its submenu and names with underscores keep them`() {
        val menu = menuOf(remote)

        assertEquals("origin/JIRA_1", menu.text)
        assertFalse(menu.isMnemonicParsing)
        assertTrue(menu.items.filter { it !is SeparatorMenuItem }.all { !it.isMnemonicParsing })
        assertEquals("New Branch from 'origin/JIRA_1'…", menu.item("branch-new-branch").text)
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
        assertEquals(listOf(CheckoutRemote(remote), RebaseBranch("main", remote.name, "origin/JIRA_1")), operations)
        assertEquals(
            "origin/JIRA_1",
            WaitForAsyncUtils.asyncFx<String?> { Clipboard.getSystemClipboard().string }.get(),
        )

        // Rename and Delete of a local branch.
        calls.clear()
        val localMenu = menuOf(local)
        fire(localMenu.item("branch-rename"))
        fire(localMenu.item("branch-delete"))
        assertEquals(listOf("rename refs/heads/feature/b", "delete [$local]"), calls)
    }
}
