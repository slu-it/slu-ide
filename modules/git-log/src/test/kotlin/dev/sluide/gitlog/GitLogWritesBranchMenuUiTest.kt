package dev.sluide.gitlog

import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPendingRebase
import dev.sluide.git.GitPick
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.operations.DELETE_BUTTON
import dev.sluide.gitlog.table.CommitMenuEntry
import dev.sluide.gitlog.ui.GitLogIcons
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.effect.DropShadow
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.PickResult
import javafx.scene.paint.Color
import javafx.scene.paint.Paint
import javafx.stage.Stage
import javafx.stage.WindowEvent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignT
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UI tests of the branch submenus in the commit table's context menu (ADR 0063). Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogWritesBranchMenuUiTest : GitLogWritesUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** Opens the table's menu as the user does and returns its top-level submenus. */
    private fun topMenus(): List<Menu> =
        WaitForAsyncUtils
            .asyncFx<List<Menu>> {
                val menu = view.table.contextMenu
                Event.fireEvent(menu, WindowEvent(menu, WindowEvent.WINDOW_SHOWING))
                menu.items.filterIsInstance<Menu>()
            }.get()

    /** The submenus of the opened menu with the group each sits in: `Branches`, `Tags` or none (it stands alone). */
    private fun grouped(): List<Pair<String?, Menu>> =
        topMenus().flatMap { top ->
            if (top.text in setOf("Branches", "Tags")) {
                top.items.filterIsInstance<Menu>().map { top.text to it }
            } else {
                listOf(null to top)
            }
        }

    /** The ref submenus of the opened menu, also those a "Branches" or "Tags" menu holds. */
    private fun refMenus(): List<Menu> = grouped().map { it.second }

    /** The branch submenus of the opened menu; a tag is told by the kind style of its ref icon, not by name. */
    private fun menus(): List<Menu> = refMenus().filter { !it.isTag() }

    /** The tag submenus of the opened menu. */
    private fun tagMenus(): List<Menu> = refMenus().filter { it.isTag() }

    private fun Menu.isTag(): Boolean = "git-ref-tag" in refIcon().styleClass

    /** The ref icon of a ref submenu. */
    private fun Menu.refIcon(): FontIcon = (graphic as Parent).childrenUnmodifiable.single() as FontIcon

    private fun Menu.item(id: String): MenuItem = items.single { it.id == id }

    /** The item with [id], `null` where the entry never applies to the branch and is left out. */
    private fun Menu.find(id: String): MenuItem? = items.singleOrNull { it.id == id }

    /** Which of `branch-checkout`, rebase, compare and delete are enabled, as `C B P D` or `-`. */
    private fun Menu.enabled(): String =
        listOf("branch-checkout" to "C", "branch-rebase" to "B", "branch-compare" to "P", "branch-delete" to "D")
            .joinToString(" ") { (id, letter) -> if (find(id)?.isDisable == false) letter else "-" }

    private fun fire(item: MenuItem) {
        Platform.runLater { item.fire() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    @Test
    fun `a commit shows a submenu for each local and remote branch and tag, not for HEAD, once`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 0)

        assertEquals(listOf("main", "origin/main"), menus().map { it.text })
        assertEquals(listOf("v1.0"), tagMenus().map { it.text }, "a tag: its own submenu")
        assertTrue(refMenus().all { "git-branch-menu" in it.styleClass })
        val items = view.table.contextMenu.items
        assertEquals("Branches", items[0].text, "two branches: one menu holding both")
        assertEquals("v1.0", items[1].text, "the tag after the branches")
        assertTrue(items[2] is SeparatorMenuItem, "a separator after the submenus")
        assertEquals("Review Commit", items[3].text)
        assertEquals(
            listOf("branch-new-branch", "branch-review", null, "branch-rename", null, "branch-working-tree-diff"),
            menus().first().items.map { it.id },
            "the current branch",
        )
        assertEquals(
            listOf(
                "branch-new-branch",
                "branch-review",
                null,
                "branch-pull",
                null,
                "branch-compare",
                "branch-working-tree-diff",
            ),
            menus()[1].items.map { it.id },
            "a remote branch at HEAD: no checkout of the current branch, no rebase, merge, delete or fetch",
        )
        assertTrue(menus().flatMap { it.items }.all { it is SeparatorMenuItem || it.graphic is FontIcon })
        assertEquals("New Branch from 'origin/main'\u2026", menus()[1].item("branch-new-branch").text)
        val size = view.table.contextMenu.items.size
        menus()
        assertEquals(size, view.table.contextMenu.items.size, "not added again when it opens again")

        selectRows(robot, 1)
        assertTrue(menus().isEmpty(), "a tag only: no branch")
        assertEquals(listOf("v0.9"), tagMenus().map { it.text })
        assertTrue(
            tagMenus().single().items.any { it.id == "branch-push-tag" },
            "a tag's submenu pushes it, to the remotes the log read",
        )
        val tagOnly = view.table.contextMenu.items
        assertEquals("v0.9", tagOnly[0].text)
        assertTrue(tagOnly[1] is SeparatorMenuItem, "a separator after the submenu")
        assertEquals("Review Commit", tagOnly[2].text)

        selectRows(robot, 0, 2)
        assertTrue(refMenus().isEmpty(), "several commits")
        selectRows(robot, 2)
        assertEquals(listOf("feature/a"), menus().map { it.text })
    }

    @Test
    fun `several branches are grouped under Branches, one stands alone, and the icons keep their font`(
        robot: FxRobot,
    ) {
        openAndExpand(robot)
        selectRows(robot, 0)

        val top = topMenus()
        assertEquals(listOf("Branches", "v1.0"), top.map { it.text }, "two branches: one menu, then the tag")
        assertEquals(listOf("main", "origin/main"), top[0].items.map { it.text })
        val branches = top[0].graphic as FontIcon
        assertTrue(FontIcon(GitLogIcons.branches).style in branches.style, "the glyph font stays")
        assertEquals(GitLogIcons.branches, branches.iconCode, "the Branches glyph")
        val refIcons = top[0].items.map { (it as Menu).refIcon() }
        assertTrue(refIcons.all { FontIcon(MaterialDesignT.TAG_OUTLINE).style in it.style }, "the glyph font stays")
        assertTrue(refIcons.all { it.iconCode == MaterialDesignT.TAG_OUTLINE })
        val tagIcon = top[1].refIcon()
        assertEquals(MaterialDesignT.TAG_OUTLINE, tagIcon.iconCode, "the tag's ref icon")
        assertTrue("git-ref-tag" in tagIcon.styleClass)

        selectRows(robot, 2)
        assertEquals(listOf("feature/a"), topMenus().map { it.text }, "one branch: its own submenu, not grouped")

        val pick = view.table.item(CommitMenuEntry.CHERRY_PICK)
        val icon = pick.graphic as FontIcon
        assertTrue(pick in view.table.contextMenu.items)
        assertTrue(FontIcon(GitLogIcons.cherryPick).style in icon.style, "the glyph font stays")
        assertTrue("-fx-icon-color: -fx-text-background-color" in icon.style, "follows the highlighted item")
    }

    @Test
    fun `several tags are grouped under Tags after the branch group, one tag stands alone`(robot: FxRobot) {
        currentRefs = GitRefs(refs.refs + GitRef("refs/tags/v0.8", GitRefKind.TAG, "h1"), refs.currentBranch)
        openAndExpand(robot)
        selectRows(robot, 0)

        val both = topMenus()
        assertEquals(listOf("Branches", "Tags"), both.map { it.text }, "branch group, then tag group")
        assertEquals(listOf("v0.8", "v1.0"), both[1].items.map { it.text })
        val tags = both[1].graphic as FontIcon
        assertEquals(GitLogIcons.tags, tags.iconCode, "the Tags glyph")
        assertTrue(FontIcon(GitLogIcons.tags).style in tags.style, "the glyph font stays")
        assertEquals(listOf("v0.8", "v1.0"), tagMenus().map { it.text }, "a new tag counts as a tag")
        assertEquals(listOf("main", "origin/main"), menus().map { it.text })
        selectRows(robot, 1)
        assertEquals(listOf("v0.9"), topMenus().map { it.text }, "one tag and no branch: its own submenu")
    }

    @Test
    fun `the menu opens on a request only with a row selected, also after one without`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 2)
        requestMenu(robot)
        eventually { assertTrue(view.table.contextMenu.isShowing, "the first request opens it") }
        robot.interact { view.table.contextMenu.hide() }

        selectRows(robot)
        requestMenu(robot)
        assertFalse(view.table.contextMenu.isShowing, "nothing is selected: no empty popup")

        selectRows(robot, 2)
        requestMenu(robot)
        eventually { assertTrue(view.table.contextMenu.isShowing, "and again after the empty one") }
        robot.interact { view.table.contextMenu.hide() }
    }

    @Test
    fun `the ref icons in the shown menu take the colours of the light and the dark theme`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 2)
        robot.interact { view.scene.preferences.colorScheme = ColorScheme.LIGHT }
        requestMenu(robot)
        eventually { assertTrue(view.table.contextMenu.isShowing) }
        val icon =
            view.table.contextMenu.items
                .filterIsInstance<Menu>()
                .single()
                .let { (it.graphic as Parent).childrenUnmodifiable.single() as FontIcon }

        fun colour(scheme: ColorScheme): Paint {
            robot.interact {
                // ThemedWindows sets the scheme of every window's scene, popups included; the test does it itself.
                icon.scene.preferences.colorScheme = scheme
                icon.scene.root.applyCss()
            }
            return icon.iconColor
        }
        assertEquals(Color.web("#3f9a4a"), colour(ColorScheme.LIGHT), "a local branch, light")
        assertEquals(Color.web("#6cc070"), colour(ColorScheme.DARK), "a local branch, dark")
        // On the highlighted item the icon gets the outline of a selected row, so that it stands out on the bar.
        val item = generateSequence(icon as Node) { it.parent }.first { "menu-item" in it.styleClass }
        val other =
            icon.scene.root
                .lookupAll(".menu-item")
                .first { it !== item }

        fun highlight(node: Node) =
            robot.interact {
                node.requestFocus()
                icon.scene.root.applyCss()
            }
        highlight(other)
        assertNull(icon.effect, "not highlighted")
        highlight(item)
        assertTrue(icon.effect is DropShadow, "highlighted")
        robot.interact { view.table.contextMenu.hide() }
    }

    @Test
    fun `an open menu follows a pick that begins while it is shown`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 2)
        requestMenu(robot)
        eventually { assertTrue(view.table.contextMenu.isShowing) }
        val pick = view.table.item(CommitMenuEntry.CHERRY_PICK)
        val shown =
            view.table.contextMenu.items
                .toList()
        assertFalse(pick.isDisable)

        pending = GitPendingPick(GitPick.CHERRY_PICK, "h4", listOf("a.txt"))
        robot.interact { view.refresh() }

        eventually { assertTrue(pick.isDisable, "git is busy now") }
        assertTrue(view.table.item(CommitMenuEntry.RESET).let { !it.isDisable }, "the way out")
        assertEquals(
            shown,
            view.table.contextMenu.items
                .toList(),
            "the popup is not rebuilt",
        )
        robot.interact { view.table.contextMenu.hide() }
    }

    @Test
    fun `an open menu follows an operation that begins while it is shown`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 2)
        requestMenu(robot)
        eventually { assertTrue(view.table.contextMenu.isShowing) }
        val writing =
            listOf(
                CommitMenuEntry.CHECKOUT_REVISION,
                CommitMenuEntry.NEW_BRANCH,
                CommitMenuEntry.NEW_TAG,
                CommitMenuEntry.RESET,
            ).map { view.table.item(it) }
        val shown =
            view.table.contextMenu.items
                .toList()
        assertTrue(writing.none { it.isDisable })
        writes.gate = CountDownLatch(1)

        fireLater(view.table.item(CommitMenuEntry.CHECKOUT_REVISION))

        eventually { assertTrue(writing.all { it.isDisable }, "an operation runs now") }
        assertEquals(
            shown,
            view.table.contextMenu.items
                .toList(),
            "the popup is not rebuilt",
        )
        writes.gate?.countDown()
        eventually { assertTrue(writing.none { it.isDisable }, "and is over") }
        robot.interact { view.table.contextMenu.hide() }
    }

    /** What the OS sends for a right-click on the table, without moving the real mouse. */
    private fun requestMenu(robot: FxRobot) {
        robot.interact {
            val bounds = view.table.localToScreen(view.table.boundsInLocal)
            Event.fireEvent(
                view.table,
                ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    5.0,
                    5.0,
                    bounds.minX + 5,
                    bounds.minY + 5,
                    false,
                    PickResult(view.table, 5.0, 5.0),
                ),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    @Test
    fun `the current branch cannot be checked out, compared, deleted or rebased`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 0)

        val (main, remote) = menus()
        assertEquals("- - - -", main.enabled())
        assertFalse(main.item("branch-rename").isDisable)
        assertEquals("- - P -", remote.enabled(), "its local branch is the current one, it is at HEAD, protected")
        assertNull(remote.find("branch-rebase"), "at HEAD's commit")
        assertNull(main.find("branch-merge"))
        assertNull(remote.find("branch-merge"), "at HEAD's commit")
        assertNull(main.find("branch-pull"), "a local branch")
        assertFalse(remote.item("branch-pull").isDisable, "the fetch may bring more")
        assertEquals("Pull 'origin/main' into 'main'\u2026", remote.item("branch-pull").text)
    }

    @Test
    fun `merge, rebase and checkout of another branch dispatch`(robot: FxRobot) {
        writes.ancestry = { _, _ -> false }
        openAndExpand(robot)
        selectRows(robot, 2)

        val menu = menus().single()
        assertEquals("C B P D", menu.enabled())
        assertEquals("Rebase 'main' onto 'feature/a'", menu.item("branch-rebase").text)
        assertEquals("Merge 'feature/a' into 'main'\u2026", menu.item("branch-merge").text)

        // Merge asks how to merge first; the rest is GitLogWritesMergeUiTest's.
        fire(menu.item("branch-merge"))
        val dialog = awaitDialog(robot)
        assertEquals("Merge 'feature/a' into 'main'", dialog.headerText)
        press(robot, dialog, ButtonType.CANCEL)
        assertTrue(writes.calls.isEmpty())

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
        assertTrue(running.item("branch-merge").isDisable)
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
        assertTrue(waiting.item("branch-merge").isDisable)
        assertFalse(waiting.item("branch-checkout").isDisable)
    }

    @Test
    fun `Delete in the submenu of a remote branch deletes it on its remote after asking`(robot: FxRobot) {
        currentRefs =
            GitRefs(refs.refs + GitRef("refs/remotes/origin/fix", GitRefKind.REMOTE_BRANCH, "h1"), refs.currentBranch)
        openAndExpand(robot)
        selectRows(robot, 0)

        assertNull(menus().single { it.text == "origin/main" }.find("branch-delete"), "protected")
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

    @Test
    fun `Delete in the submenu of a tag asks to delete the tag`(robot: FxRobot) {
        openAndExpand(robot)
        selectRows(robot, 1)

        fireLater(tagMenus().single().item("branch-delete"))
        val pane = awaitDialog(robot)
        assertEquals("Delete tag 'v0.9'?", pane.headerText)
        assertTrue((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        press(robot, pane, DELETE_BUTTON)

        eventually { assertEquals(listOf("tag -d v0.9"), writes.calls) }
    }

    @Test
    fun `Review Branch in the submenu asks for the full refname of its branch`(robot: FxRobot) {
        val requests = CopyOnWriteArrayList<ReviewRequest>()
        robot.interact { view.onReview = { requests += it } }
        openAndExpand(robot)
        selectRows(robot, 0)

        // The current branch and a remote branch: both can be reviewed, also while the checkout is off.
        assertEquals(listOf("main", "origin/main"), menus().map { it.text })
        menus().forEach { assertFalse(it.item("branch-review").isDisable, it.text) }
        fire(menus()[1].item("branch-review"))
        assertEquals(listOf<ReviewRequest>(ReviewRequest.Branch("refs/remotes/origin/main")), requests)
        fire(menus()[0].item("branch-review"))
        assertEquals(ReviewRequest.Branch("refs/heads/main"), requests.last())
    }
}
