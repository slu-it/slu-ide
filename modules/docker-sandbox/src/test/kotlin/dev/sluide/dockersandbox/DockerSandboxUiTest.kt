package dev.sluide.dockersandbox

import dev.sluide.editor.EditorTab
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import dev.sluide.terminal.TerminalStartException
import dev.sluide.terminal.Terminals
import javafx.scene.Scene
import javafx.scene.control.Label
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
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.readLines
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Starts sandboxes in real terminals with a stub `sbx`, put on the `PATH` by the `.profile` of a test home folder, like
 * a user's login shell finds the real one. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DockerSandboxUiTest {
    @TempDir
    lateinit var root: Path

    private val home by lazy { root.resolve("home").createDirectories() }
    private val project by lazy { home.resolve("project").createDirectories() }
    private val terminals by lazy {
        Terminals(FontSettings(SettingsStore(root.resolve("settings.properties")), FontSettings.systemDefaults()))
    }
    private lateinit var pane: StackPane

    @AfterEach
    fun close() = terminals.close()

    @Start
    fun start(stage: Stage) {
        pane = StackPane(Label())
        stage.scene = Scene(pane, 800.0, 400.0)
        stage.show()
    }

    private val calls by lazy { root.resolve("sbx-calls.txt") }

    /** The environment of the sandboxes' shells; tests may change it. */
    private val environment = System.getenv().toMutableMap()

    /** A sandbox whose shells find a stub `sbx` that records its folder and arguments in [calls]. */
    private fun sandbox(): DockerSandbox {
        val bin = root.resolve("bin").createDirectories()
        bin.resolve("sbx").apply {
            writeText("#!/bin/sh\necho \"$(pwd -P): $*\" >> '$calls'\n")
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        home.resolve(".profile").writeText("PATH=\"$bin:\$PATH\"\nexport PATH\n")
        environment += mapOf("HOME" to home.toString(), "SHELL" to "/bin/sh")
        return DockerSandbox(terminals, home, environment)
    }

    private fun run(
        robot: FxRobot,
        action: SandboxAction,
        sandbox: DockerSandbox = sandbox(),
    ): EditorTab {
        lateinit var tab: EditorTab
        robot.interact {
            tab = sandbox.open(action, project)
            pane.children.setAll(tab.content)
        }
        val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
        while (!tab.title.value.endsWith("(exited)")) {
            check(System.nanoTime() < deadline) { "${tab.title.value} did not end" }
            Thread.sleep(50)
            WaitForAsyncUtils.waitForFxEvents()
        }
        return tab
    }

    @Test
    fun `Claude Code runs sbx run claude in the project folder`(robot: FxRobot) {
        val tab = run(robot, SandboxAction.CLAUDE_CODE)

        assertEquals("Claude Code 1 (exited)", tab.title.value)
        assertEquals(listOf("${project.toRealPath()}: run claude ."), calls.readLines())
    }

    @Test
    fun `Environment runs sbx env run in the project folder`(robot: FxRobot) {
        project.resolve("sbxenv.yaml").createFile()

        val tab = run(robot, SandboxAction.ENVIRONMENT)

        assertEquals("Environment 1 (exited)", tab.title.value)
        assertEquals(listOf("${project.toRealPath()}: env run"), calls.readLines())
    }

    @Test
    fun `tabs are numbered per action`(robot: FxRobot) {
        project.resolve("sbxenv.yaml").createFile()
        val sandbox = sandbox()

        val titles =
            listOf(SandboxAction.CLAUDE_CODE, SandboxAction.CLAUDE_CODE, SandboxAction.ENVIRONMENT)
                .map { run(robot, it, sandbox).title.value }

        assertEquals(listOf("Claude Code 1 (exited)", "Claude Code 2 (exited)", "Environment 1 (exited)"), titles)
    }

    @Test
    fun `a sandbox whose shell cannot be started opens no tab and uses up no number`(robot: FxRobot) {
        val sandbox = sandbox()
        environment["SHELL"] = root.resolve("no-shell").toString()

        robot.interact {
            assertFailsWith<TerminalStartException> { sandbox.open(SandboxAction.CLAUDE_CODE, project) }
        }
        assertEquals(0, terminals.openCount)

        environment["SHELL"] = "/bin/sh"
        assertEquals("Claude Code 1 (exited)", run(robot, SandboxAction.CLAUDE_CODE, sandbox).title.value)
    }
}
