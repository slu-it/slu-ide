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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    private val settings by lazy { SettingsStore(dir.resolve("settings.properties")) }

    private val histories by lazy {
        ArgumentHistories(ProjectDataStore(dir.resolve("projects"), dir.resolve("outside.json"), emptyList()), settings)
    }

    /** The terminals' environment: a shell that exists everywhere, and two variables for the runs to keep or override. */
    private val baseEnvironment = mapOf("SHELL" to "/bin/sh", "KEEP" to "base", "OVER" to "base")

    private val session by lazy {
        CommandRunSession(
            Terminals(
                FontSettings(settings, FontSettings.systemDefaults()),
                TerminalScrollSettings(settings),
                baseEnvironment,
            ),
            BackgroundTasks.forTests(),
            histories,
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
            runner.embed = { _, _, _ -> LateTerminal() }
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
    fun `a run starts with the terminals' environment plus its own variables, which override`(robot: FxRobot) {
        robot.interact {
            val pane = RunPane("", "")
            val runner = CommandRunner<Job>(session, pane, { dir }, { _, _ -> true })
            val seen = mutableListOf<Map<String, String>>()
            runner.embed = { _, _, environment -> LateTerminal().also { seen += environment } }
            val extra = mapOf("OVER" to "extra", "EXTRA_VAR" to "1")

            runner.start(Job("a"), command = { "a" })
            runner.start(Job("b"), command = { "b" }, environment = extra)

            assertEquals(baseEnvironment, seen[0])
            assertEquals(mapOf("SHELL" to "/bin/sh", "KEEP" to "base", "OVER" to "extra", "EXTRA_VAR" to "1"), seen[1])
            runner.dispose()
        }
    }

    @Test
    fun `the variables of a run reach the process of its real terminal`(robot: FxRobot) {
        val out = dir.resolve("seen.txt")
        val runner = CommandRunner<Job>(session, RunPane("", ""), { dir }, { _, _ -> true })
        robot.interact {
            val command = { "printf '%s' \"\$KEEP-\$OVER-\$EXTRA_VAR\" > '$out'" }
            runner.start(Job("real"), command = command, environment = mapOf("OVER" to "extra", "EXTRA_VAR" to "1"))
        }

        val deadline = System.nanoTime() + 20_000_000_000L
        while (!Files.exists(out) && System.nanoTime() < deadline) Thread.sleep(50)
        // The file is created by the shell's redirection, a moment before the text is in it.
        while (Files.exists(out) && Files.readString(out).isEmpty() && System.nanoTime() < deadline) Thread.sleep(50)

        assertEquals("base-extra-1", Files.readString(out))
        robot.interact { runner.dispose() }
    }

    @Test
    fun `only a run that started records its arguments, which the pane shows until the runner is disposed`(
        robot: FxRobot,
    ) {
        robot.interact {
            val history = ArgumentHistory(histories, { dir.resolve("run.sh") }, { dir })
            var stop = false
            val pane = RunPane("", "")
            val runner = CommandRunner<Job>(session, pane, { dir }, { _, _ -> stop }, history = history)
            runner.embed = { _, _, _ -> LateTerminal() }

            assertEquals(StartResult.STARTED, runner.start(Job("a"), command = { "a" }, arguments = "-x"))
            assertEquals(listOf("-x"), history.entries)
            assertEquals(StartResult.CANCELLED, runner.start(Job("b"), command = { "b" }, arguments = "-y"))
            stop = true
            val unclosed = { throw IllegalArgumentException("Unclosed quote") }
            assertEquals(StartResult.FAILED, runner.start(Job("c"), command = unclosed, arguments = "'-z"))

            assertEquals(listOf("-x"), histories.entries(dir.resolve("run.sh"), dir))
            assertEquals(listOf("-x"), pane.history.list.items)
            assertTrue(pane.history.node.isVisible)
            runner.dispose()

            assertFalse(pane.history.node.isVisible)
            // Let go of the global expanded state.
            histories.expanded.set(!histories.expanded.get())
            assertNotEquals(histories.expanded.get(), pane.history.header.isSelected)
        }
    }
}
