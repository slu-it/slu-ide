package dev.sluide.gitlog

import dev.sluide.gitlog.operations.ADD_BUTTON
import javafx.application.Platform
import javafx.scene.control.TextField
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Add Remote with the real git CLI (ADR 0183): checks the real `GitLogCliWrites` delegation (add, then a fetch of a
 * local bare repository) and the branches pane after the refresh. Needs a display and git, see the `uiTest` Gradle
 * task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogRemotesRealGitUiTest : GitLogRealGitUiFixture() {
    @Start
    fun start(stage: Stage) = startRealGit(stage)

    @Test
    fun `adding a remote with a fetch brings its branches into the branches pane`(robot: FxRobot) {
        val local = folder.resolve("local").createDirectories()
        val bare = folder.resolve("bare.git").createDirectories()
        val other = folder.resolve("other").createDirectories()
        git(local, "init", "-q", "-b", "main")
        commit(local, "local.txt")
        git(bare, "init", "-q", "--bare", "-b", "main")
        git(other, "init", "-q", "-b", "main")
        commit(other, "other.txt")
        git(other, "push", "-q", bare.toString(), "main")
        robot.interact {
            view.expanded.set(true)
            view.open(local)
        }
        eventually { assertTrue(view.table.items.isNotEmpty()) }

        Platform.runLater { view.branchCommands.addRemote() }
        WaitForAsyncUtils.waitForFxEvents()
        val pane = awaitDialog(robot)
        robot.interact { (pane.lookup(".git-remote-url") as TextField).text = bare.toString() }
        press(robot, pane, ADD_BUTTON)

        eventually { assertEquals("Added remote origin. Fetched origin: 1 new branch.", status(robot)) }
        assertEquals(bare.toString(), git(local, "remote", "get-url", "origin"))
        eventually { branchItem("Remote/origin/main") }
    }
}
