package dev.sluide.commandruns

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import dev.sluide.terminal.EmbeddedTerminal
import dev.sluide.terminal.Terminals
import dev.sluide.terminal.settings.TerminalScrollSettings
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.scene.layout.Region
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The runner with fake terminals. A UI test: the runner drives a [RunPane] (controls) and a `Timeline`, which need the
 * JavaFX toolkit; real runs are tested through the Gradle view.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CommandRunnerUiTest {
    @TempDir
    lateinit var dir: Path

    private data class Job(
        override val label: String,
    ) : RunJob {
        override val backgroundName = "Job: $label"
    }

    /** A terminal that, like the contract allows, reports its end only later, never during [close]. */
    private class LateTerminal : EmbeddedTerminal {
        override val node = Region()
        private val exitedWrapper = ReadOnlyBooleanWrapper()
        override val exited: ReadOnlyBooleanProperty = exitedWrapper.readOnlyProperty
        override val exitCode: Int? = null
        override val isAlive = true

        override fun interrupt() {}

        override fun close() {}
    }

    private val session by lazy {
        val store = SettingsStore(dir.resolve("settings.properties"))
        CommandRunSession(
            Terminals(FontSettings(store, FontSettings.systemDefaults()), TerminalScrollSettings(store)),
            BackgroundTasks.forTests(),
        )
    }

    @AfterEach
    fun close() = session.close()

    @Test
    fun `a confirmed replacement ends the running job at once, so the next start does not ask again`(robot: FxRobot) {
        robot.interact {
            var questions = 0
            val pane = RunPane("Arguments", "Output")
            val runner = CommandRunner<Job>(session, pane, { dir }, { _, _ -> true.also { questions++ } })
            runner.embed = { _, _ -> LateTerminal() }
            val ended = mutableListOf<RunState>()
            runner.start(Job("test"), command = { "test" }, ended = { ended += it })

            assertTrue(runner.replaceRunning(Job("build")))
            assertEquals(listOf<RunState>(RunState.Stopped), ended)
            assertEquals("■ test — stopped", pane.status.text)
            assertEquals(StartResult.STARTED, runner.start(Job("build"), command = { "build" }))
            assertEquals(1, questions)
            assertEquals(Job("build"), runner.running)
            runner.dispose()
        }
    }

    @Test
    fun `only a run that started records its arguments`(robot: FxRobot) {
        robot.interact {
            val histories =
                ArgumentHistories(
                    ProjectDataStore(dir.resolve("projects"), dir.resolve("outside.json"), emptyList()),
                    SettingsStore(dir.resolve("settings.properties")),
                )
            val history = ArgumentHistory(histories, { dir.resolve("run.sh") }, { dir })
            var stop = false
            val runner = CommandRunner<Job>(session, RunPane("", ""), { dir }, { _, _ -> stop }, history = history)
            runner.embed = { _, _ -> LateTerminal() }

            assertEquals(StartResult.STARTED, runner.start(Job("a"), command = { "a" }, arguments = "-x"))
            assertEquals(listOf("-x"), history.entries)
            assertEquals(StartResult.CANCELLED, runner.start(Job("b"), command = { "b" }, arguments = "-y"))
            stop = true
            val unclosed = { throw IllegalArgumentException("Unclosed quote") }
            assertEquals(StartResult.FAILED, runner.start(Job("c"), command = unclosed, arguments = "'-z"))

            assertEquals(listOf("-x"), histories.entries(dir.resolve("run.sh"), dir))
            runner.dispose()
        }
    }
}
