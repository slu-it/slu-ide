package dev.sluide.gitlog

import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitTreePath
import dev.sluide.gitlog.ui.GIT_LOG_CSS
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the git log view: the branch, user and path popups of the filter bar. Needs a display, see the `uiTest`
 * Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogViewFilterPopupsUiTest : GitLogViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `the branch popup lists the refs, checking one filters the log, the button tells and clears`(robot: FxRobot) {
        refs =
            GitRefs(
                someRefs.refs + ref("refs/heads/feature", GitRefKind.LOCAL_BRANCH, "h5"),
                "refs/heads/main",
            )
        reachable = mapOf("refs/heads/feature" to setOf("h5", "h6", "h7"), "HEAD" to setOf("h1", "h2"))
        openAndExpand(robot)
        val branch = bar.branch
        assertEquals("Branch", branch.label.text)
        assertFalse(branch.clear.isVisible || branch.clear.isManaged)

        robot.interact { branch.button.fire() }
        val popup = branch.popup
        assertTrue(popup.popup.isShowing)
        assertSame<Node?>(popup.search, popup.popup.scene.focusOwner)
        assertEquals(
            listOf("HEAD", "feature", "main", "origin/main", "origin/old", "v0.9", "v1.0"),
            items(popup),
        )
        assertTrue(GIT_LOG_CSS in popup.content.stylesheets)

        // Clicked and checked in one step, as popups close when another test JVM takes the window's focus.
        var staysOpen = false
        robot.interact {
            if (!popup.popup.isShowing) branch.button.fire()
            fireClick(listCell(popup, "feature"))
            staysOpen = popup.popup.isShowing
        }
        eventually { assertEquals(listOf("refs/heads/feature"), filters.last().revisions) }
        eventually { assertEquals(listOf("h5", "h6", "h7"), view.table.items.map { it.commit.hash }) }
        assertEquals("Branch: feature", branch.label.text)
        assertTrue(branch.clear.isVisible && branch.clear.isManaged)
        assertTrue(staysOpen, "stays open")

        robot.interact { popup.list.selectionModel.select(0) }
        press(robot, popup.list, KeyCode.SPACE)
        eventually { assertEquals(listOf("HEAD", "refs/heads/feature"), filters.last().revisions) }
        assertEquals("Branch: HEAD +1", branch.label.text)

        // Typing narrows the list; Enter toggles the one item shown.
        robot.interact { popup.search.text = "FEAT" }
        assertEquals(listOf("feature"), items(popup))
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("HEAD"), filters.last().revisions) }
        assertEquals("Branch: HEAD", branch.label.text)

        // Several shown, one named exactly: Enter toggles it, both ways.
        robot.interact { popup.search.text = "main" }
        assertEquals(listOf("main", "origin/main"), items(popup))
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("HEAD", "refs/heads/main"), filters.last().revisions) }
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("HEAD"), filters.last().revisions) }

        // A double click toggles once.
        robot.interact { popup.search.text = "" }
        clickItem(robot, popup, "v0.9", clickCount = 2)
        eventually { assertEquals(listOf("HEAD", "refs/tags/v0.9"), filters.last().revisions) }
        clickItem(robot, popup, "v0.9")
        eventually { assertEquals(listOf("HEAD"), filters.last().revisions) }

        press(robot, popup.search, KeyCode.ESCAPE)
        assertFalse(popup.popup.isShowing)
        robot.interact { branch.button.fire() }
        assertEquals("HEAD", items(popup).first(), "checked first")
        assertEquals("", popup.search.text)
        robot.interact { popup.hide() }

        click(robot, branch.clear)
        eventually { assertEquals(GitLogFilter(), filters.last()) }
        assertEquals("Branch", branch.label.text)
        assertFalse(branch.clear.isVisible || branch.clear.isManaged)
    }

    @Test
    fun `Down in a popup's search field moves into its list`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        robot.interact { bar.branch.button.fire() }
        val popup = bar.branch.popup

        press(robot, popup.search, KeyCode.DOWN)

        assertSame<Node?>(popup.list, popup.popup.scene.focusOwner)
        assertEquals(0, popup.list.selectionModel.selectedIndex)
        robot.interact { popup.hide() }
    }

    @Test
    fun `the user popup lists me and the loaded authors, and takes a typed name with Enter`(robot: FxRobot) {
        userName = "Me Myself"
        history = commits(3) + commits(1, "x").map { it.copy(author = "alice") }
        openAndExpand(robot)
        val user = bar.user

        robot.interact { user.button.fire() }
        val popup = user.popup
        eventuallyLists(user, listOf("me (Me Myself)", "alice", "Author 1", "Author 2", "Author 3"))
        assertEquals(listOf(repo), userNameLoads)

        clickItem(robot, popup, "me (Me Myself)")
        eventually { assertEquals(listOf("Me Myself"), filters.last().users) }
        assertEquals("User: me", user.label.text)
        eventually { assertEquals("No commits match the filters.", placeholder(robot)) }

        robot.interact { popup.search.text = "Author 2" }
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("Me Myself", "Author 2"), filters.last().users) }

        robot.interact { popup.search.text = " Somebody Else " }
        assertEquals(emptyList(), items(popup))
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("Me Myself", "Author 2", "Somebody Else"), filters.last().users) }
        assertEquals("User: me +2", user.label.text)
        assertEquals("", popup.search.text)
        assertEquals("Somebody Else", items(popup)[2], "checked first")
        eventually { assertEquals(listOf("Subject 2"), shownSubjects(robot)) }

        robot.interact { popup.hide() }
        robot.interact { user.button.fire() }
        assertTrue("alice" in items(popup), "the user filter hides authors, the list keeps them")
        assertEquals(1, userNameLoads.size, "read once per folder")
        robot.interact { popup.hide() }
    }

    @Test
    fun `the path popup lists HEAD's tree and takes a typed path`(robot: FxRobot) {
        treePaths = listOf(GitTreePath("src", folder = true), GitTreePath("src/a.txt", folder = false))
        openAndExpand(robot)
        val path = bar.path

        robot.interact { path.button.fire() }
        val popup = path.popup
        eventuallyLists(path, listOf("src", "src/a.txt"))
        assertEquals(listOf(repo), pathLoads)
        assertTrue(popup.list.items.all { it.icon != null })

        robot.interact { popup.search.text = "./docs/" }
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("docs"), filters.last().paths) }
        assertEquals("Path: docs", path.label.text)

        robot.interact { popup.search.text = "src" }
        assertEquals(listOf("src", "src/a.txt"), items(popup))
        press(robot, popup.search, KeyCode.ENTER)
        eventually { assertEquals(listOf("src", "docs"), filters.last().paths) }
        assertEquals("Path: src +1", path.label.text)

        robot.interact { popup.hide() }
        robot.interact { path.button.fire() }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(1, pathLoads.size, "not read again for a filter change")
        robot.interact { popup.hide() }

        // A refresh marks the tree stale, opening another folder forgets it.
        robot.interact { view.refresh() }
        robot.interact { path.button.fire() }
        eventually { assertEquals(2, pathLoads.size) }
        robot.interact { popup.hide() }

        otherHistory = commits(3, "o")
        robot.interact { view.open(Path.of("/other")) }
        robot.interact { path.button.fire() }
        eventually { assertEquals(listOf(repo, repo, Path.of("/other")), pathLoads.toList()) }
        robot.interact { popup.hide() }
    }
}
