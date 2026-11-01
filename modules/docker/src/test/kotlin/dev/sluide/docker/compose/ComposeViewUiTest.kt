package dev.sluide.docker.compose

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.commandruns.ArgumentHistories
import dev.sluide.commandruns.CommandRunSession
import dev.sluide.docker.Docker
import dev.sluide.docker.DockerSession
import dev.sluide.docker.cli.DockerConnection
import dev.sluide.docker.cli.DockerException
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.cli.ResolvedDocker
import dev.sluide.docker.events.ManualScheduler
import dev.sluide.docker.model.container
import dev.sluide.docker.settings.DockerSettings
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.SplitMode
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import dev.sluide.terminal.Terminals
import dev.sluide.terminal.settings.TerminalScrollSettings
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.TextField
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Compose view's connections (ADR 0119): its modes, the toolbar, a row, the gutter's ▶ and the Environment section
 * to the model, and the model's commands to the Output's terminal. The runs are real, in terminals whose login shell
 * is bash with a home of its own, of a fake docker executable that logs its folder, arguments and `GREETING`
 * (the runner's terminal seam is internal to command-runs). The model's rules are ComposePaneModelTest's. Needs a
 * display: the view, its pane and the terminals are controls.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ComposeViewUiTest {
    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var support: Path

    private lateinit var root: StackPane

    private val settingsStore by lazy { SettingsStore(support.resolve("settings.properties")) }
    private val terminals by lazy {
        Terminals(
            FontSettings(settingsStore, FontSettings.systemDefaults()),
            TerminalScrollSettings(settingsStore),
            // bash with a home of its own, so the user's startup files do not interfere (as in the script view).
            System.getenv() + mapOf("SHELL" to "/bin/bash", "HOME" to support.toString()),
        )
    }
    private val projectData by lazy {
        ProjectDataStore(support.resolve("projects"), support.resolve("shared.json"), emptyList())
    }
    private val runSession by lazy {
        CommandRunSession(terminals, BackgroundTasks.forTests(), ArgumentHistories(projectData, settingsStore))
    }
    private val cli = FakeDockerCli().apply { composeConfigOutput = shopConfig() }

    /** Every run of the fake docker appends its folder, arguments and `GREETING` here, then `end`. */
    private val calls: Path get() = support.resolve("calls.log")

    private val fakeDocker by lazy {
        support.resolve("fake-docker").apply {
            writeText(
                """
                |#!/bin/sh
                |{ echo "pwd=${'$'}PWD"; for a in "${'$'}@"; do echo "arg=${'$'}a"; done
                |  echo "GREETING=${'$'}{GREETING-unset}"; echo end; } >> '$calls'
                """.trimMargin(),
            )
            toFile().setExecutable(true)
        }
    }

    private val session by lazy {
        DockerSession(
            executable = SimpleStringProperty("docker"),
            connect = { DockerConnection.Ready(ResolvedDocker(fakeDocker, emptyMap()), cli) },
            worker = { it.run() },
            scheduler = ManualScheduler(),
            fx = Platform::runLater,
        )
    }

    /** How often the Docker tab was asked for. */
    private var tabShown = 0

    private val docker by lazy {
        Docker(session, BackgroundTasks.forTests(), { _, _, _, _ -> error("no terminal tabs here") }, support).apply {
            showTab = { tabShown++ }
        }
    }

    private val folder by lazy { dir.resolve("shop").createDirectories() }
    private val file by lazy { folder.resolve("compose.yaml").apply { writeText(SHOP_COMPOSE) } }
    private val views = mutableListOf<ComposeView>()

    private val host =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override val projectFolder: Path get() = dir

            override fun <T : Any> windowService(type: Class<T>): T? =
                when (type) {
                    CommandRunSession::class.java -> type.cast(runSession)
                    DockerSession::class.java -> type.cast(session)
                    Docker::class.java -> type.cast(docker)
                    else -> null
                }
        }

    @Start
    fun start(stage: Stage) {
        root = StackPane()
        stage.scene = Scene(root, 1200.0, 800.0)
        stage.show()
    }

    @AfterEach
    fun close(robot: FxRobot) {
        robot.interact { views.forEach(ComposeView::dispose) }
        session.close()
        runSession.close()
        terminals.close()
    }

    private fun open(robot: FxRobot): ComposeView {
        lateinit var view: ComposeView
        robot.interact {
            val viewer =
                ComposeFileViewer(ComposeStore(projectData), DockerSettings(settingsStore))
            view = viewer.open(file) as ComposeView
            // A dialog would block the test; a test that expects a question replaces its answer.
            view.confirmStop = { _, _ -> error("unexpected stop question") }
            view.askUnsaved = { error("unexpected question: $it") }
            view.confirm = { error("unexpected question: $it") }
            views += view
            root.children.setAll(view.node)
            view.host = host
        }
        return view
    }

    /** Waits until [assertion] holds on the FX thread, up to 20 s. */
    private fun eventually(
        robot: FxRobot,
        assertion: () -> Unit,
    ) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            var failure: Throwable? = null
            robot.interact { failure = runCatching(assertion).exceptionOrNull() }
            val error = failure ?: return
            if (System.nanoTime() > deadline) throw error
            Thread.sleep(50) // poll interval
        }
    }

    private fun ready(
        robot: FxRobot,
        view: ComposeView,
    ) = eventually(robot) { assertTrue(!view.pane.toolbar.up.isDisable, "Up enabled") }

    /** The lines the fake docker logged for its one run, once it ended. */
    private fun loggedRun(robot: FxRobot): List<String> {
        eventually(robot) { assertTrue(calls.exists() && calls.readLines().lastOrNull() == "end") }
        return calls.readLines()
    }

    private fun args(lines: List<String>) = lines.filter { it.startsWith("arg=") }.map { it.removePrefix("arg=") }

    @Test
    fun `opens in Split, and Up runs in the file's folder with the variables of the Environment section`(
        robot: FxRobot,
    ) {
        val view = open(robot)
        assertEquals(SplitMode.SPLIT, view.split.mode.get())
        assertEquals(listOf("Source", "Split", "Compose"), view.split.modeButtons.map { it.text })
        ready(robot, view)

        robot.interact {
            view.pane.environment.addVariable
                .fire()
            val row =
                view.pane.environment.node
                    .lookupAll(".compose-variable-name")
                    .single() as TextField
            row.text = "GREETING"
            (
                view.pane.environment.node
                    .lookupAll(".compose-variable-value")
                    .single() as TextField
            ).text = "hello there"
            view.pane.toolbar.up
                .fire()
        }
        val lines = loggedRun(robot)

        assertEquals("pwd=$folder", lines.first())
        val options = ComposeOptions(variables = listOf(ComposeVariable("GREETING", "hello there")))
        assertEquals(ComposeCommands.baseArgs(file, null, options) + listOf("up", "-d"), args(lines))
        assertTrue("GREETING=hello there" in lines, "the variable in the environment, not in the command line")
    }

    @Test
    fun `the gutter's run marker of a service runs up -d for it, showing the pane beside the source`(robot: FxRobot) {
        val view = open(robot)
        ready(robot, view)
        robot.interact { view.split.mode.set(SplitMode.SOURCE) }

        lateinit var icon: Node
        eventually(robot) { icon = view.node.lookupAll(".compose-run-icon").single { it.userData == "worker" } }
        robot.interact { Event.fireEvent(icon, click(icon)) }
        val lines = loggedRun(robot)

        assertEquals(listOf("up", "-d", "worker"), args(lines).takeLast(3))
        assertEquals(SplitMode.SPLIT, view.split.mode.get())
    }

    @Test
    fun `explorer Run is Up`(robot: FxRobot) {
        val view = open(robot)
        ready(robot, view)

        robot.interact { view.run() }

        assertEquals(listOf("up", "-d"), args(loggedRun(robot)).takeLast(2))
    }

    @Test
    fun `Rerun goes through the model, asking again`(robot: FxRobot) {
        val view = open(robot)
        ready(robot, view)
        robot.interact { view.run() }
        loggedRun(robot)
        eventually(robot) { assertNull(view.runner?.running) }
        val asked = mutableListOf<String>()

        robot.interact {
            view.replaceText(SHOP_COMPOSE + "# changed\n")
            view.askUnsaved = { question -> UnsavedChoice.CANCEL.also { asked += question } }
            view.pane.runPane.rerun
                .fire()
        }

        assertEquals(listOf(unsavedQuestion("compose.yaml")), asked)
        assertNull(view.runner?.running, "cancelled")
    }

    @Test
    fun `Down and Remove Volumes asks first, and nothing runs when the answer is no`(robot: FxRobot) {
        val view = open(robot)
        ready(robot, view)
        val asked = mutableListOf<String>()

        robot.interact {
            view.confirm = { question -> false.also { asked += question } }
            view.pane.toolbar.downVolumes
                .fire()
        }

        assertEquals(listOf(downVolumesQuestion("shop")), asked)
        assertNull(view.runner?.running, "not started")
        assertTrue(!calls.exists(), "no process ran")
    }

    @Test
    fun `a configuration that cannot be read shows its error and disables Up`(robot: FxRobot) {
        cli.failing = {
            if (it.startsWith(
                    "composeConfig",
                )
            ) {
                DockerException("yaml: line 3: mapping values are not allowed")
            } else {
                null
            }
        }
        val view = open(robot)

        eventually(robot) {
            assertEquals(
                "✖ The compose file cannot be used: yaml: line 3: mapping values are not allowed",
                view.pane.services.note.text,
            )
        }
        assertTrue(view.pane.toolbar.up.isDisable)
    }

    @Test
    fun `unresolved variables are named and asked about before Up`(robot: FxRobot) {
        cli.composeConfigOutput = shopConfig(unresolved = true)
        val view = open(robot)
        ready(robot, view)
        val asked = mutableListOf<String>()

        robot.interact {
            view.confirm = { question -> false.also { asked += question } }
            view.pane.toolbar.up
                .fire()
        }

        assertEquals(listOf(unresolvedQuestion(listOf("GREETING"))), asked)
        assertNull(view.runner?.running, "not started")
        assertEquals("⚠ Not set, default to a blank string: GREETING", view.pane.environment.unresolved.text)
    }

    @Test
    fun `a service row's Logs shows its container in the Docker tab`(robot: FxRobot) {
        cli.containers = listOf(container("shop-web-1", project = "shop", service = "web"))
        val view = open(robot)
        lateinit var logs: Button
        eventually(robot) {
            val row =
                view.pane.services.node
                    .lookupAll(".compose-service-row")
                    .single { it.userData == "web" }
            logs = row.lookup(".compose-service-logs") as Button
            assertTrue(!logs.isDisable)
        }

        robot.interact { logs.fire() }

        assertEquals(1, tabShown)
    }

    private fun click(node: Node) =
        MouseEvent(
            MouseEvent.MOUSE_CLICKED,
            0.0,
            0.0,
            0.0,
            0.0,
            MouseButton.PRIMARY,
            1,
            false,
            false,
            false,
            false,
            true,
            false,
            false,
            true,
            false,
            true,
            null,
        ).copyFor(node, node)
}
