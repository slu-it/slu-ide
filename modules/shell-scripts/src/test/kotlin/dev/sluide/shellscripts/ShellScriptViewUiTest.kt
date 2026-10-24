package dev.sluide.shellscripts

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.commandruns.CommandRunSession
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.SplitMode
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.settings.FontSettings
import dev.sluide.settings.SettingsStore
import dev.sluide.terminal.Terminals
import dev.sluide.terminal.settings.TerminalScrollSettings
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.moveTo
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The script view's connections to its run flow and runner (ADR 0089), with real scripts in terminals whose login shell
 * is bash with a home of its own. The flow's questions and their order are ScriptRunFlowTest's, the texts
 * ScriptCommandTest's. Needs a display: the view, its run pane and the terminals are controls.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ShellScriptViewUiTest {
    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var support: Path

    private lateinit var root: StackPane

    private val store by lazy { SettingsStore(support.resolve("settings.properties")) }
    private val terminals by lazy {
        Terminals(
            FontSettings(store, FontSettings.systemDefaults()),
            TerminalScrollSettings(store),
            // bash with a home of its own, so the user's startup files do not interfere (as in the Gradle view).
            System.getenv() + mapOf("SHELL" to "/bin/bash", "HOME" to support.toString()),
        )
    }
    private val runSession by lazy { CommandRunSession(terminals, BackgroundTasks.forTests()) }

    /** The files the host saved. */
    private val saved = mutableListOf<Path>()
    private val views = mutableListOf<ShellScriptView>()

    private val host =
        object : FileViewHost {
            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> windowService(type: Class<T>): T? =
                if (type == CommandRunSession::class.java) runSession as T else null

            override fun save(file: Path): Boolean {
                saved.add(file)
                views.single().save()
                return true
            }
        }

    /** Every run of the scripts appends its folder and arguments here. */
    private val runs: Path get() = support.resolve("runs.log")

    /** A run of the script with `wait` writes its pid here, then ends once [go] exists. */
    private val pid: Path get() = support.resolve("pid")
    private val go: Path get() = support.resolve("go")

    private val shortcutIsMeta = System.getProperty("os.name").startsWith("Mac")

    private fun shortcut(code: KeyCode) =
        KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, !shortcutIsMeta, false, shortcutIsMeta)

    @Start
    fun start(stage: Stage) {
        root = StackPane()
        stage.scene = Scene(root, 1000.0, 700.0)
        stage.show()
    }

    @AfterEach
    fun close(robot: FxRobot) {
        robot.interact { views.forEach(ShellScriptView::dispose) }
        runSession.close()
        terminals.close()
    }

    /**
     * A script in a folder with a space that logs its folder and arguments; with `wait` it writes its pid and waits for
     * [go], with `fail` it fails with exit code 3.
     */
    private fun script(
        executable: Boolean = true,
        log: String = "\"\$PWD\" \"\$@\"",
    ): Path =
        Files.createDirectories(dir.resolve("my scripts")).resolve("deploy.sh").apply {
            writeText(
                """
                |#!/bin/bash
                |printf '%s|' $log >> '$runs'
                |echo >> '$runs'
                |if [ "$1" = wait ]; then echo $$ > '$pid'; while [ ! -e '$go' ]; do sleep 0.05; done; fi
                |if [ "$1" = fail ]; then exit 3; fi
                |echo done
                """.trimMargin(),
            )
            toFile().setExecutable(executable)
        }

    private fun open(
        robot: FxRobot,
        file: Path,
    ): ShellScriptView {
        lateinit var view: ShellScriptView
        robot.interact {
            view = ShellScriptFileViewer().open(file) as ShellScriptView
            // A dialog would block the test; a test that expects a question replaces its answer.
            view.confirmStop = { _, _ -> error("unexpected stop question") }
            view.askUnsaved = { error("unexpected question: $it") }
            view.askMakeExecutable = { error("unexpected question: $it") }
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
            Thread.sleep(50)
        }
    }

    private fun ended(
        robot: FxRobot,
        view: ShellScriptView,
        status: String,
    ) = eventually(robot) {
        val shown = view.runPane.status.text
        assertTrue(shown.startsWith(status), shown)
    }

    /** The process of the script's run with `wait`, once it wrote its pid. */
    private fun waiting(robot: FxRobot): ProcessHandle {
        eventually(robot) { assertTrue(pid.exists() && pid.toFile().length() > 0) }
        return ProcessHandle.of(pid.readText().trim().toLong()).orElseThrow()
    }

    @Test
    fun `run shows the output beside the source and runs the script in its folder, asking before it stops a run`(
        robot: FxRobot,
    ) {
        val script = script()
        val view = open(robot, script)
        val asked = mutableListOf<Pair<ScriptJob, ScriptJob>>()
        view.confirmStop = { running, next ->
            asked += running to next
            false
        }
        lateinit var placeholder: Node
        robot.interact {
            assertEquals(SplitMode.SOURCE, view.split.mode.get())
            placeholder = view.runPane.shownOutput
            view.runPane.arguments.text = "wait 'two words'"
            view.run()
            assertEquals(SplitMode.SPLIT, view.split.mode.get())
        }
        waiting(robot)
        robot.interact {
            assertEquals(ViewActivity.RUNNING, view.activity.value)
            assertEquals("deploy.sh is still running.", view.closeWarning)
            assertNotSame(placeholder, view.runPane.shownOutput)
        }
        assertEquals(listOf("${script.parent}|wait|two words|"), runs.readLines())

        // Kept running: the question was asked, nothing else ran.
        robot.interact {
            view.runPane.arguments.text = "again"
            view.run()
            assertEquals(ViewActivity.RUNNING, view.activity.value)
        }
        val running = ScriptJob("deploy.sh", listOf("wait", "two words"))
        assertEquals(listOf(running to ScriptJob("deploy.sh", listOf("again"))), asked)
        assertEquals(1, runs.readLines().size)

        go.createFile()
        ended(robot, view, "✔ deploy.sh wait two words — finished in ")
        robot.interact {
            assertEquals(ViewActivity.NONE, view.activity.value)
            assertNull(view.closeWarning)
        }

        // Closing the view ends a run.
        go.deleteExisting()
        pid.deleteExisting()
        robot.interact {
            view.runPane.arguments.text = "wait"
            view.run()
        }
        val process = waiting(robot)
        robot.interact {
            views -= view
            view.dispose()
        }
        eventually(robot) { assertFalse(process.isAlive, "the run of a closed view still runs") }
    }

    @Test
    fun `the Run button, Shortcut+Enter in the source, Enter in the arguments and Rerun run the script, also moved`(
        robot: FxRobot,
    ) {
        val script = script(log = "\"\$@\"")
        val view = open(robot, script)
        val original = view.text
        val area = robot.from(view.node).lookup(".code-area").query<Node>()
        val triggers =
            listOf<Pair<String, () -> Unit>>(
                "button" to { view.runButton.fire() },
                "source" to { Event.fireEvent(area, shortcut(KeyCode.ENTER)) },
                "arguments" to {
                    val press = KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)
                    Event.fireEvent(view.runPane.arguments, press)
                },
                // Again, with the arguments as they are now.
                "rerun" to { view.runPane.rerun.fire() },
            )
        for ((index, trigger) in triggers.withIndex()) {
            val (name, fire) = trigger
            robot.interact {
                view.runPane.arguments.text = name
                fire()
            }
            ended(robot, view, "✔ deploy.sh $name — finished in ")
            assertEquals("$name|", runs.readLines()[index])
        }
        robot.interact {
            assertEquals(original, view.text, "Shortcut+Enter left the source alone")
            assertFalse(view.dirty.get())
        }

        // Shortcut+Enter in the find bar, a text field inside the source, does not run.
        robot.interact { Event.fireEvent(area, shortcut(KeyCode.F)) }
        val query = robot.from(view.node).lookup(".find-query").query<Node>()
        robot.interact {
            Event.fireEvent(query, shortcut(KeyCode.ENTER))
            // A run starts at once, within the key press.
            assertNull(view.runner?.running)
        }

        // Renamed without its extension, it is still the same view, and runs under its new name.
        val renamed = script.moveTo(script.resolveSibling("deploy"))
        robot.interact {
            assertTrue(view.moved(renamed))
            view.runPane.arguments.text = "moved"
            view.runButton.fire()
        }
        ended(robot, view, "✔ deploy moved — finished in ")
        assertEquals(listOf("button|", "source|", "arguments|", "rerun|", "moved|"), runs.readLines())
    }

    @Test
    fun `unsaved changes are saved through the host, a script made executable runs, a failure marks the tab`(
        robot: FxRobot,
    ) {
        val script = script(executable = false)
        val view = open(robot, script)
        val questions = mutableListOf<String>()
        view.askUnsaved = {
            questions += it
            UnsavedChoice.SAVE_AND_RUN
        }
        view.askMakeExecutable = {
            questions += it
            true
        }
        robot.interact {
            view.replaceText(view.text.replace("printf '%s|'", "printf 'edited %s|'"))
            view.runPane.arguments.text = "fail"
            view.run()
        }

        ended(robot, view, "✖ deploy.sh fail — failed (exit 3) after ")
        assertEquals(listOf(script.toAbsolutePath().normalize()), saved)
        assertEquals(
            listOf("deploy.sh has unsaved changes.", "deploy.sh is not executable. Make it executable and run?"),
            questions,
        )
        assertTrue(Files.isExecutable(script))
        assertEquals(listOf("edited ${script.parent}|edited fail|"), runs.readLines())
        robot.interact {
            assertFalse(view.dirty.get())
            assertEquals(ViewActivity.FAILED, view.activity.value)
            // Shown again: the failure no longer marks the tab.
            view.focus()
            assertEquals(ViewActivity.NONE, view.activity.value)
        }
    }
}
