package dev.sluide.ide

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitTrustCheck
import dev.sluide.ide.trust.KEEP_OFF_BUTTON
import dev.sluide.ide.trust.TRUST_BUTTON
import dev.sluide.ide.trust.trustConfirmation
import dev.sluide.ide.trust.trustDetails
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.ProjectTrustState
import dev.sluide.projecttrust.TrustCheckResult
import dev.sluide.projecttrust.TrustFinding
import dev.sluide.projecttrust.TrustedProjects
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
        // Remembered for this folder: opened again, it is trusted without asking.
        val again =
            ProjectTrust(
                listOf(GitTrustCheck(git)),
                TrustedProjects(settingsDir.resolve("project-trust.properties")),
                BackgroundTasks.forTests(),
            ) {
                it.run()
            }
        again.open(project)
        assertTrue(again.awaitAllows(GitTrustCheck.ID))
        again.close()
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
    fun `the dialog lists the findings by tool, with when they run, and keeps git off by default`(robot: FxRobot) {
        val state =
            ProjectTrustState.Ask(
                listOf(
                    TrustCheckResult(
                        "git",
                        "Git",
                        listOf(
                            TrustFinding("something.new = x (.git/config)"),
                            TrustFinding("core.fsmonitor = ./watch.sh (.git/config)", "runs on every git status"),
                        ),
                    ),
                    TrustCheckResult("build", "Build", emptyList()),
                ),
                changed = false,
            )
        robot.interact {
            val dialog = trustConfirmation(stage, project, state)
            assertEquals("Trust Folder?", dialog.title)
            assertEquals("${project.fileName} defines commands that Git would run.", dialog.headerText)
            assertEquals(
                """
                Git
                  core.fsmonitor = ./watch.sh (.git/config)
                      runs on every git status
                  something.new = x (.git/config)
                      not known to Thoreau
                """.trimIndent(),
                trustDetails(state),
            )
            val keepOff = dialog.dialogPane.lookupButton(KEEP_OFF_BUTTON) as Button
            val trust = dialog.dialogPane.lookupButton(TRUST_BUTTON) as Button
            assertTrue(keepOff.isDefaultButton)
            assertFalse(trust.isDefaultButton)
            // Esc and closing the window answer with the cancel button: Keep Off.
            dialog.show()
            dialog.close()
            assertEquals(KEEP_OFF_BUTTON, dialog.result)
        }
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
    fun `a folder git's check could not check says so`(robot: FxRobot) {
        val state =
            ProjectTrustState.Untrusted(listOf(TrustCheckResult("git", "Git", null)))
        robot.interact {
            assertEquals(
                "What ${project.fileName} would make Git run could not be checked.",
                trustConfirmation(stage, project, state).headerText,
            )
        }
    }
}
