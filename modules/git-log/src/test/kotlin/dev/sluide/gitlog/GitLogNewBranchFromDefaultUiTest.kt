package dev.sluide.gitlog

import dev.sluide.gitlog.operations.CREATE_BUTTON
import javafx.application.Platform
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The header's Fetch origin and Create new Branch from its Default Branch (ADR 0181) with the real git CLI: the
 * fixture's fake writes cannot show that the branch really gets no upstream and that the local branches reach the
 * dialog. Needs a display and git, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogNewBranchFromDefaultUiTest : GitLogRealGitUiFixture() {
    @Start
    fun start(stage: Stage) = startRealGit(stage)

    @Test
    fun `the quick action fetches origin, asks for a name and creates a branch at its default, untracked`(
        robot: FxRobot,
    ) {
        val local = folder.resolve("local").createDirectories()
        val origin = folder.resolve("origin.git").createDirectories()
        val other = folder.resolve("other").createDirectories()
        git(local, "init", "-q", "-b", "main")
        commit(local, "local.txt")
        // Even with this, git would make a branch from a remote-tracking ref track it.
        git(local, "config", "branch.autoSetupMerge", "always")
        git(origin, "init", "-q", "--bare", "-b", "main")
        git(other, "init", "-q", "-b", "main")
        val newTip = commit(other, "origin.txt")
        git(other, "push", "-q", origin.toString(), "main")

        robot.interact { view.open(local) }
        // Nothing is expanded: the header's own read decides, and there is no origin yet.
        robot.interact { assertTrue(view.newBranchDefaultButton.isDisable) }
        git(local, "remote", "add", "origin", origin.toString())
        robot.interact { view.refresh() }
        eventually { assertFalse(view.newBranchDefaultButton.isDisable) }

        robot.interact { Platform.runLater { view.newBranchDefaultButton.fire() } }
        // Collapsed: the dialog validates against the branches the operation read, not the pages'.
        val pane = awaitDialog(robot)
        robot.interact { assertEquals("New Branch from origin/main", (pane.scene.window as Stage).title) }
        robot.interact {
            val field = pane.lookup(".git-ref-name") as TextField
            val problem = pane.lookup(".git-ref-name-problem") as Label
            val overwrite = pane.lookup(".git-ref-overwrite") as CheckBox
            val create = pane.lookupButton(CREATE_BUTTON)
            field.text = "main"
            assertEquals("Branch 'main' already exists", problem.text)
            assertTrue(create.isDisable)
            overwrite.isSelected = true
            assertEquals("Branch 'main' is checked out.", problem.text, "the current branch is never overwritten")
            assertTrue(create.isDisable)
            overwrite.isSelected = false
            field.text = "topic"
        }
        press(robot, pane, CREATE_BUTTON)

        eventually { assertEquals("Created and checked out branch topic.", status(robot)) }
        assertEquals(newTip, git(local, "rev-parse", "HEAD"))
        assertEquals("topic", git(local, "branch", "--show-current"))
        assertEquals("", git(local, "config", "--get", "branch.topic.merge"))
        assertEquals("", git(local, "config", "--get", "branch.topic.remote"))

        // Expanded, the header's own read is skipped: the pages' read of the repository turns it on again.
        robot.interact {
            view.expanded.set(true)
            view.open(local)
        }
        eventually { assertFalse(view.newBranchDefaultButton.isDisable) }
    }
}
