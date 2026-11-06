package dev.sluide.docker.dockerfile

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.commandruns.ArgumentHistories
import dev.sluide.commandruns.CommandRunSession
import dev.sluide.docker.DockerSession
import dev.sluide.docker.cli.DockerConnection
import dev.sluide.docker.cli.FakeDockerCli
import dev.sluide.docker.cli.ResolvedDocker
import dev.sluide.docker.events.ManualScheduler
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
import javafx.scene.Scene
import javafx.scene.control.ListCell
import javafx.scene.control.ToggleButton
import javafx.scene.control.skin.ComboBoxListViewSkin
import javafx.scene.layout.StackPane
import javafx.scene.text.Text
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Dockerfile view's connections: the Build button and explorer Run to the model, the model's build to the Output's
 * terminal, the editor's text to the Target combo after the reparse pause, and Run… to the Run dialog. The runs are
 * real, in terminals whose login shell is bash with a home of its own, of a fake docker executable that logs its
 * folder, arguments and `TOKEN` (which stays unset: the value reaches docker through the command line's
 * expansion of a private variable; the runner's terminal seam is internal to command-runs). The model's rules are
 * BuildPaneModelTest's. Needs a display: the view, its pane and the terminals are controls.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DockerfileViewUiTest {
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

    /** Every run of the fake docker appends its folder, arguments and `TOKEN` here, then `end`. */
    private val calls: Path get() = support.resolve("calls.log")

    private val fakeDocker by lazy {
        support.resolve("fake-docker").apply {
            writeText(
                """
                |#!/bin/sh
                |{ echo "pwd=${'$'}PWD"; for a in "${'$'}@"; do echo "arg=${'$'}a"; done
                |  echo "TOKEN=${'$'}{TOKEN-unset}"; echo end; } >> '$calls'
                """.trimMargin(),
            )
            toFile().setExecutable(true)
        }
    }

    private val session by lazy {
        DockerSession(
            executable = SimpleStringProperty("docker"),
            connect = { DockerConnection.Ready(ResolvedDocker(fakeDocker, emptyMap()), FakeDockerCli()) },
            worker = { it.run() },
            scheduler = ManualScheduler(),
            fx = Platform::runLater,
        )
    }

    private val folder by lazy { dir.resolve("app").createDirectories() }
    private val file by lazy { folder.resolve("Dockerfile").apply { writeText(DOCKERFILE) } }
    private val views = mutableListOf<DockerfileView>()

    private val host =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override val projectFolder: Path get() = dir

            override fun <T : Any> windowService(type: Class<T>): T? =
                when (type) {
                    CommandRunSession::class.java -> type.cast(runSession)
                    DockerSession::class.java -> type.cast(session)
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
        robot.interact { views.forEach(DockerfileView::dispose) }
        session.close()
        runSession.close()
        terminals.close()
    }

    private fun open(robot: FxRobot): DockerfileView {
        lateinit var view: DockerfileView
        robot.interact {
            val viewer = DockerfileBuildViewer(DockerBuildStore(projectData), DockerSettings(settingsStore))
            view = viewer.open(file) as DockerfileView
            // A dialog would block the test; a test that expects a question replaces its answer.
            view.confirmStop = { _, _ -> error("unexpected stop question") }
            view.askUnsaved = { error("unexpected question: $it") }
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
        view: DockerfileView,
    ) = eventually(robot) { assertTrue(!view.pane.build.isDisable, "Build enabled") }

    /** The lines the fake docker logged for its one run, once it ended. */
    private fun loggedRun(robot: FxRobot): List<String> {
        eventually(robot) { assertTrue(calls.exists() && calls.readLines().lastOrNull() == "end") }
        return calls.readLines()
    }

    private fun args(lines: List<String>) = lines.filter { it.startsWith("arg=") }.map { it.removePrefix("arg=") }

    @Test
    fun `opens in Split, and Build runs the command with the build arg values in the environment`(robot: FxRobot) {
        val view = open(robot)
        assertEquals(SplitMode.SPLIT, view.split.mode.get())
        assertEquals(listOf("Source", "Split", "Build"), view.split.modeButtons.map { it.text })
        ready(robot, view)

        robot.interact {
            view.pane.form.tags.editor.text = "myapp:dev"
            view.pane.form.argFields
                .getValue("TOKEN")
                .text = "it's secret"
            view.pane.build.fire()
        }
        val lines = loggedRun(robot)

        assertEquals("pwd=$folder", lines.first())
        assertEquals(
            listOf("build", "-f", "$file", "-t", "myapp:dev", "--build-arg", "TOKEN=it's secret", "$folder"),
            args(lines),
            "the shell put the value in",
        )
        assertEquals("TOKEN=unset", lines[lines.size - 2], "the ARG's own name is not set in the environment")
        assertTrue("it's secret" !in view.pane.preview.text, "the value is not in the preview")
    }

    @Test
    fun `a new stage in the source shows in the Target combo after the reparse pause`(robot: FxRobot) {
        val view = open(robot)
        ready(robot, view)
        val target = view.pane.form.target
        assertEquals(listOf(BuildForm.LAST_STAGE, "base"), target.items)

        robot.interact { view.replaceText(DOCKERFILE + "FROM base AS test\n") }

        eventually(robot) { assertEquals(listOf(BuildForm.LAST_STAGE, "base", "test"), target.items) }

        // A chosen stage renamed in the source stays chosen and shown, marked missing, not the stage now in its place.
        robot.interact { target.value = "base" }
        robot.interact { view.replaceText((DOCKERFILE + "FROM base AS test\n").replace("AS base", "AS basex")) }

        eventually(robot) { assertEquals(listOf(BuildForm.LAST_STAGE, "basex", "test", "base"), target.items) }
        robot.interact {
            assertEquals("base", target.selectionModel.selectedItem)
            // The drawn text follows only in a layout pass.
            target.applyCss()
            target.layout()
            val display = (target.skin as ComboBoxListViewSkin<*>).displayNode as ListCell<*>
            // The drawn text: the cell's text property can be right while the text it shows is still the old one.
            val shown = (display.lookup(".text") as Text).text
            assertEquals("base", shown, "the combo shows the chosen stage")
            assertTrue("docker-build-missing" in target.styleClass, "marked missing")
        }
    }

    @Test
    fun `Run after a successful build with a tag opens the Run dialog for the tag`(robot: FxRobot) {
        val view = open(robot)
        val runs = mutableListOf<String>()
        robot.interact { view.runImage = { runs += it } }
        ready(robot, view)
        assertTrue(view.pane.runImage.isDisable)

        robot.interact {
            view.pane.form.tags.editor.text = "myapp:dev other"
            view.pane.build.fire()
        }
        loggedRun(robot)
        eventually(robot) { assertTrue(!view.pane.runImage.isDisable, "Run… enabled") }
        robot.interact { view.pane.runImage.fire() }

        assertEquals(listOf("myapp:dev"), runs)
    }

    @Test
    fun `explorer Run builds, showing the pane beside the source`(robot: FxRobot) {
        val view = open(robot)
        ready(robot, view)
        robot.interact { view.split.mode.set(SplitMode.SOURCE) }

        robot.interact { view.run() }

        assertEquals(listOf("build", "-f", file.toString()), args(loggedRun(robot)).take(3))
        assertEquals(SplitMode.SPLIT, view.split.mode.get())
    }

    @Test
    fun `Soft Wrap is the first toolbar button, toggles, and hides while only the second side shows`(robot: FxRobot) {
        val view = open(robot)
        robot.interact {
            val button = view.toolbar.leading.first() as ToggleButton
            assertEquals("soft-wrap-toggle", button.styleClass.last())
            assertTrue(button.isVisible && button.isManaged)
            button.fire()
            assertTrue(button.isSelected)
            view.split.mode.set(SplitMode.OTHER)
            assertFalse(button.isVisible)
            assertFalse(button.isManaged)
            view.split.mode.set(SplitMode.SPLIT)
            assertTrue(button.isVisible)
        }
    }

    private companion object {
        const val DOCKERFILE = "ARG TOKEN\nFROM alpine AS base\nARG LEVEL=3\nRUN true\n"
    }
}
