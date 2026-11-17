package dev.sluide.gitlog.branches

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.data.BranchNode
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI tests of the remotes in the branches pane on its own: their nodes, tooltips and the keys that rename and remove
 * one. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class BranchRemotesUiTest {
    private val main = GitRef("refs/heads/main", GitRefKind.LOCAL_BRANCH, "h1")
    private val refs =
        GitRefs(
            listOf(
                GitRef("HEAD", GitRefKind.HEAD, "h1"),
                main,
                GitRef("refs/remotes/origin/main", GitRefKind.REMOTE_BRANCH, "h1"),
                GitRef("refs/remotes/origin/fix/a", GitRefKind.REMOTE_BRANCH, "h1"),
                GitRef("refs/remotes/gone/main", GitRefKind.REMOTE_BRANCH, "h1"),
            ),
            "refs/heads/main",
        )
    private val locations =
        mapOf(
            "origin" to "https://github.com",
            "empty" to "ssh github.com",
            "fix" to "Local repository",
        )

    private val calls = mutableListOf<String>()
    private lateinit var pane: BranchesPane

    @Start
    fun start(stage: Stage) {
        pane = BranchesPane()
        pane.commands =
            BranchCommands().apply {
                rename = { calls += "rename ${it.name}" }
                renameRemote = { calls += "rename remote $it" }
                removeRemote = { calls += "remove remote $it" }
                delete = { calls += "delete $it" }
            }
        pane.show(refs, null, listOf("origin", "empty", "fix"), locations)
        stage.scene = Scene(pane, 300.0, 400.0)
        stage.show()
    }

    private fun items(): List<TreeItem<BranchNode>> =
        generateSequence(0) { it + 1 }
            .take(pane.tree.expandedItemCount)
            .mapNotNull { pane.tree.getTreeItem(it) }
            .toList()

    private fun select(path: String) {
        WaitForAsyncUtils
            .asyncFx {
                val row = items().indexOfFirst { it.value?.path == path }
                check(row >= 0) { "no node $path in ${items().map { it.value?.path }}" }
                pane.tree.selectionModel.clearAndSelect(row)
            }.get()
    }

    private fun press(
        code: KeyCode,
        shortcut: Boolean = false,
    ) {
        val mac = System.getProperty("os.name").startsWith("Mac")
        WaitForAsyncUtils
            .asyncFx {
                Event.fireEvent(
                    pane.tree,
                    KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !mac, false, shortcut && mac),
                )
            }.get()
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun tooltipOf(path: String): String? =
        WaitForAsyncUtils
            .asyncFx<String?> {
                pane.tree
                    .lookupAll(".tree-cell")
                    .filterIsInstance<TreeCell<BranchNode>>()
                    .single { it.item?.path == path }
                    .tooltip
                    ?.text
            }.get()

    private fun showAgain(
        remotes: List<String>,
        locations: Map<String, String>,
        shown: GitRefs = refs,
    ) {
        WaitForAsyncUtils.asyncFx { pane.show(shown, null, remotes, locations) }.get()
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun paths(): List<String?> =
        WaitForAsyncUtils.asyncFx<List<String?>> { items().map { it.value?.path } }.get()

    @Test
    fun `showing the same refs again with another remote adds its node`() {
        assertTrue("Remote/upstream" !in paths())

        showAgain(listOf("origin", "empty", "fix", "upstream"), locations)

        assertTrue("Remote/upstream" in paths(), "${paths()}")
    }

    @Test
    fun `showing the same refs and remotes again with another location changes the tooltip`() {
        assertEquals("https://github.com", tooltipOf("Remote/origin"))

        showAgain(listOf("origin", "empty", "fix"), locations + ("origin" to "ssh example.org"))

        assertEquals("ssh example.org", tooltipOf("Remote/origin"))
    }

    @Test
    fun `a selected remote stays selected when other refs are shown`() {
        select("Remote/origin")

        showAgain(listOf("origin", "empty", "fix"), locations, GitRefs(refs.refs.drop(2), "refs/heads/main"))

        val selected =
            WaitForAsyncUtils.asyncFx<List<String?>> {
                pane.tree.selectionModel.selectedItems
                    .map { it.value?.path }
            }
        assertEquals(listOf("Remote/origin"), selected.get())
    }

    @Test
    fun `the tooltip of a configured remote says where it is, no other node has one`() {
        assertEquals("https://github.com", tooltipOf("Remote/origin"))
        assertEquals("ssh github.com", tooltipOf("Remote/empty"))
        assertNull(tooltipOf("Remote/gone"), "a leftover remote that is not configured")
        // A folder named like the remote "fix".
        assertNull(tooltipOf("Remote/origin/fix"), "a folder")
        assertEquals("Local repository", tooltipOf("Remote/fix"))
        assertNull(tooltipOf("Remote"), "a group")
    }

    @Test
    fun `F2 renames the selected remote`() {
        select("Remote/origin")

        press(KeyCode.F2)

        assertEquals(listOf("rename remote origin"), calls)
    }

    @Test
    fun `Delete and Shortcut+Backspace remove the selected remote`() {
        select("Remote/empty")

        press(KeyCode.DELETE)
        press(KeyCode.BACK_SPACE, shortcut = true)

        assertEquals(listOf("remove remote empty", "remove remote empty"), calls)
    }

    @Test
    fun `F2 and Delete do nothing on a remote while a write runs`() {
        select("Remote/origin")
        WaitForAsyncUtils.asyncFx { pane.writing.set(true) }.get()

        press(KeyCode.F2)
        press(KeyCode.DELETE)

        assertEquals(emptyList(), calls)
    }
}
