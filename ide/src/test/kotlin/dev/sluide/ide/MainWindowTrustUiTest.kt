package dev.sluide.ide

import dev.sluide.git.GitTrustCheck
import dev.sluide.ide.git.GIT_OFF
import dev.sluide.projecttrust.ProjectTrustState
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.stage.Stage
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** UI tests of the main window: trusting what a folder makes git run (ADR 0053). Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MainWindowTrustUiTest : MainWindowUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    /** The states the window asked about, answered with [answers] in turn. */
    private val asked = mutableListOf<ProjectTrustState>()
    private val answers = ArrayDeque<Boolean>()

    private fun answerTrust(vararg trusted: Boolean) {
        answers += trusted.toList()
        mainWindow.trust.confirmTrust = { _, state ->
            asked += state
            answers.removeFirst()
        }
    }

    private fun repository() {
        assumeTrue(git.available, "git is not installed")
        runGit("init", "-q")
        project.resolve("tracked.md").writeText("one")
        runGit("add", ".")
        runGit("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", "Initial commit")
    }

    private val tracked: Path get() = project.resolve("tracked.md")

    private fun trustMessage() =
        mainWindow.trust.bar.children
            .filterIsInstance<Label>()
            .single()
            .text

    @Test
    fun `a repository whose configuration runs nothing of its own is not asked about`(robot: FxRobot) {
        repository()
        answerTrust()

        robot.interact { projectService.open(project) }

        eventually(robot) { assertTrue(fileExplorer.canShowHistory(tracked), "the git log has the repository") }
        eventually(robot) { assertEquals(project.toAbsolutePath().normalize(), gitStatus.snapshot.get().root) }
        assertEquals(emptyList(), asked)
        assertFalse(mainWindow.trust.bar.isVisible)
    }

    @Test
    fun `a repository whose configuration runs commands is asked about, and git stays off until trusted`(
        robot: FxRobot,
    ) {
        repository()
        runGit("config", "core.fsmonitor", "./watch.sh")
        answerTrust(false, true)

        robot.interact { projectService.open(project) }

        eventually(robot) { assertEquals(1, asked.size) }
        val state = assertIs<ProjectTrustState.Ask>(asked.single())
        assertEquals("Git", state.flagged.single().name)
        eventually(robot) {
            assertTrue(mainWindow.trust.bar.isVisible)
            assertEquals("Git is off: this folder defines commands it would run.", trustMessage())
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(fileExplorer.canShowHistory(tracked), "no git log")
        assertEquals(null, gitStatus.snapshot.get().root, "no git status")

        robot.interact {
            mainWindow.trust.bar.children
                .filterIsInstance<Button>()
                .single()
                .fire()
        }

        eventually(robot) {
            assertFalse(mainWindow.trust.bar.isVisible)
            assertTrue(robot.lookup(".git-log-status").queryAll<Label>().none { it.text == GIT_OFF })
            assertTrue(fileExplorer.canShowHistory(tracked))
            assertEquals(project.toAbsolutePath().normalize(), gitStatus.snapshot.get().root)
        }
        assertIs<ProjectTrustState.Untrusted>(asked[1])
        // Remembered for this folder; that it applies when opened again is ProjectTrustTest's.
        assertTrue(settingsDir.resolve("project-trust.properties").let { it.exists() && it.fileSize() > 0 })
    }

    @Test
    fun `what a change makes git run turns git off and shows the bar without asking`(robot: FxRobot) {
        repository()
        answerTrust()
        robot.interact { projectService.open(project) }
        eventually(robot) { assertTrue(fileExplorer.canShowHistory(tracked)) }

        runGit("config", "filter.crypt.clean", "./clean.sh")

        eventually(robot) {
            assertTrue(mainWindow.trust.bar.isVisible)
            assertEquals("Git is off: what this folder makes it run changed.", trustMessage())
            assertFalse(fileExplorer.canShowHistory(tracked))
        }
        assertEquals(emptyList(), asked)
    }

    @Test
    fun `while git is off the git log says so and Commit and Push are disabled`(robot: FxRobot) {
        repository()
        runGit("config", "core.fsmonitor", "./watch.sh")
        answerTrust(false)

        robot.interact { projectService.open(project) }

        eventually(robot) {
            assertTrue(mainWindow.trust.bar.isVisible)
            assertTrue(menu(robot, "Git").items.all { it.isDisable }, "Commit… and Push…")
            assertTrue(robot.lookup(".git-log-status").queryAll<Label>().any { it.text == GIT_OFF })
        }
    }

    @Test
    fun `findings that change while the dialog is open drop its answer, and the new findings are asked about`(
        robot: FxRobot,
    ) {
        repository()
        runGit("config", "core.fsmonitor", "./watch.sh")
        val states = mutableListOf<ProjectTrustState>()
        val replies = ArrayDeque(listOf(true, false))
        mainWindow.trust.confirmTrust = { _, state ->
            states += state
            if (states.size == 1) {
                // The folder changes what git runs while the dialog is open.
                runGit("config", "filter.x.clean", "./x.sh")
                val recheck = Thread { projectTrust.recheck(GitTrustCheck.ID) }
                recheck.start()
                recheck.join(10_000)
                assertFalse(recheck.isAlive, "the recheck ended")
            }
            replies.removeFirst()
        }

        robot.interact { projectService.open(project) }

        eventually(robot) { assertEquals(2, states.size, states.toString()) }
        val first = assertIs<ProjectTrustState.Ask>(states[0])
        val second = assertIs<ProjectTrustState.Ask>(states[1])
        assertEquals(
            1,
            first.flagged
                .single()
                .findings
                ?.size,
        )
        assertEquals(
            2,
            second.flagged
                .single()
                .findings
                ?.size,
        )
        eventually(robot) { assertIs<ProjectTrustState.Untrusted>(projectTrust.state.get()) }
    }
}
