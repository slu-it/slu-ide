package dev.sluide.gitlog

import javafx.application.Platform
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The header's Stash and Pop buttons (ADR 0182) with the real git CLI: the fixture's fake writes cannot show that
 * untracked files go along, what git's own message is, or what the collapsed log's and the pages' reads count. Needs
 * a display and git, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogHeaderStashUiTest : GitLogRealGitUiFixture() {
    @Start
    fun start(stage: Stage) = startRealGit(stage)

    /** A repository with `a.txt` committed. */
    private fun repository(): Path {
        val local = folder.resolve("local")
        local.toFile().mkdirs()
        git(local, "init", "-q", "-b", "main")
        commit(local, "a.txt")
        return local
    }

    private fun stashList(local: Path) = git(local, "stash", "list")

    @Test
    fun `Stash puts modified and untracked files aside with git's message, and needs local changes`(robot: FxRobot) {
        val local = repository()
        local.resolve("a.txt").writeText("changed")
        local.resolve("new.txt").writeText("untracked")
        robot.interact { view.open(local) }

        // The window's status says there is nothing: the only term that decides here.
        robot.interact { view.workingTreeChanged(false) }
        robot.interact { assertTrue(view.stashButton.isDisable) }
        robot.interact { view.workingTreeChanged(true) }
        robot.interact { assertFalse(view.stashButton.isDisable) }

        robot.interact { Platform.runLater { view.stashButton.fire() } }
        eventually { assertEquals("Stashed local changes as stash@{0}.", status(robot)) }
        assertTrue(stashList(local).contains("WIP on main"), stashList(local))
        assertEquals("a.txt", local.resolve("a.txt").readText())
        assertFalse(local.resolve("new.txt").exists(), "the untracked file went along")
        assertEquals("", git(local, "status", "--porcelain"))
    }

    @Test
    fun `with the log collapsed, Pop follows the stashes the view reads and pops the latest`(robot: FxRobot) {
        val local = repository()
        robot.interact { view.open(local) }
        robot.interact { assertTrue(view.popButton.isDisable) }

        local.resolve("a.txt").writeText("changed")
        git(local, "stash", "push", "-q")
        robot.interact { view.refresh() }
        eventually {
            assertFalse(view.popButton.isDisable)
            assertEquals("Pop Latest Stash (1 stash)", view.popButton.tooltip.text)
        }

        robot.interact { Platform.runLater { view.popButton.fire() } }
        eventually { assertTrue(status(robot).startsWith("Popped stash@{0}"), status(robot)) }
        assertEquals("", stashList(local))
        assertEquals("changed", local.resolve("a.txt").readText())
        eventually { assertTrue(view.popButton.isDisable) }
    }

    @Test
    fun `with the log expanded, Pop gets the count from the pages' read`(robot: FxRobot) {
        val local = repository()
        local.resolve("a.txt").writeText("changed")
        git(local, "stash", "push", "-q")
        local.resolve("a.txt").writeText("again")
        git(local, "stash", "push", "-q")

        robot.interact {
            view.expanded.set(true)
            view.open(local)
        }
        eventually {
            assertFalse(view.popButton.isDisable)
            assertEquals("Pop Latest Stash (2 stashes)", view.popButton.tooltip.text)
        }
    }
}
