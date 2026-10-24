package dev.sluide.terminal

import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.settings.SettingsStore
import dev.sluide.terminal.settings.TerminalScrollSettings
import dev.sluide.terminal.view.TerminalView
import javafx.scene.Scene
import javafx.scene.control.TextField
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Terminals embedded in another view ([Terminals.embed]): a real login shell in a pseudo terminal, whose exit the
 * view reports on the FX thread. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EmbeddedTerminalUiTest {
    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var settingsDir: Path

    private val fontSettings by lazy {
        FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
    }
    private val scrollSettings by lazy {
        TerminalScrollSettings(
            SettingsStore(settingsDir.resolve("settings.properties")),
        )
    }

    /** `/bin/sh`, so the user's login profile does not interfere. */
    private val environment = System.getenv() + ("SHELL" to "/bin/sh")
    private val terminalsHolder = lazy { Terminals(fontSettings, scrollSettings, environment) }
    private val terminals by terminalsHolder
    private lateinit var root: StackPane

    @AfterEach
    fun closeTerminals() {
        if (terminalsHolder.isInitialized()) terminals.close()
    }

    @Start
    fun start(stage: Stage) {
        root = StackPane()
        stage.scene = Scene(root, 800.0, 400.0)
        stage.show()
    }

    /** The exit codes the embedded terminal reported at the moment its `exited` turned true. */
    private val exitCodesWhenExited = CopyOnWriteArrayList<Int?>()

    private fun embed(
        robot: FxRobot,
        command: String,
    ): EmbeddedTerminal {
        lateinit var terminal: EmbeddedTerminal
        robot.interact {
            terminal = terminals.embed(dir, command)
            terminal.exited.subscribe { ended -> if (ended) exitCodesWhenExited += terminal.exitCode }
            root.children.setAll(terminal.node)
        }
        return terminal
    }

    private val EmbeddedTerminal.view get() = node as TerminalView

    private fun fontSize(
        robot: FxRobot,
        terminal: EmbeddedTerminal,
    ): Double {
        var size = 0.0
        robot.interact { size = terminal.view.font.size }
        return size
    }

    private fun eventually(assertion: () -> Unit) {
        val deadline = System.nanoTime() + 20.seconds.inWholeNanoseconds
        while (true) {
            WaitForAsyncUtils.waitForFxEvents()
            try {
                return assertion()
            } catch (e: AssertionError) {
                if (System.nanoTime() > deadline) throw e
                Thread.sleep(50)
            }
        }
    }

    @Test
    fun `an embedded command reports its exit code and keeps its output`(robot: FxRobot) {
        val terminal = embed(robot, "pwd; echo ran-\$((40 + 2)); exit 3")

        eventually { assertTrue(terminal.exited.get()) }
        assertEquals(listOf<Int?>(3), exitCodesWhenExited, "known as soon as exited turns true")
        assertEquals(3, terminal.exitCode)
        assertFalse(terminal.isAlive)
        val lines = terminal.view.screenText().lines()
        assertTrue(dir.toString() in lines && "ran-42" in lines, terminal.view.screenText())

        robot.interact { terminal.interrupt() }
        assertTrue(terminal.exited.get())
        assertEquals(3, terminal.exitCode, "interrupting an ended command changes nothing")
    }

    @Test
    fun `an embedded terminal leaves the focus where it was when shown, unlike a tab's`(robot: FxRobot) {
        val field = TextField()
        lateinit var terminal: EmbeddedTerminal
        robot.interact {
            root.children.setAll(VBox(field))
            field.requestFocus()
            terminal = terminals.embed(dir, "sleep 30")
            (root.children.single() as VBox).children += terminal.node
        }
        // The focus a tab's terminal takes when shown is requested later on the FX thread.
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact { assertSame(field, root.scene.focusOwner) }

        robot.interact { terminal.node.requestFocus() }
        robot.interact { assertSame(terminal.node, root.scene.focusOwner) }
    }

    @Test
    fun `interrupting sends Ctrl+C, which ends the command`(robot: FxRobot) {
        val terminal = embed(robot, "echo started; sleep 30")
        // Waits until the shell runs the command, so the interrupt reaches sleep rather than the starting shell.
        eventually { assertTrue("started" in terminal.view.screenText().lines()) }
        assertTrue(terminal.isAlive)
        assertFalse(terminal.exited.get())
        assertNull(terminal.exitCode)

        robot.interact { terminal.interrupt() }

        eventually { assertTrue(terminal.exited.get()) }
        assertEquals(listOf<Int?>(130), exitCodesWhenExited, "128 + SIGINT, known as soon as exited turns true")
        assertEquals(130, terminal.exitCode)
    }

    @Test
    fun `closing ends the command at once, without an exit code, and stops following the settings`(robot: FxRobot) {
        val terminal = embed(robot, "sleep 30")
        robot.interact { fontSettings.apply(mapOf(FontArea.TERMINAL to FontSpec("Monospaced", 26.0))) }
        assertEquals(26.0, fontSize(robot, terminal))

        robot.interact { terminal.close() }

        assertTrue(terminal.exited.get())
        eventually { assertFalse(terminal.isAlive) }
        assertNull(terminal.exitCode, "the hangup's exit code is not the command's")
        robot.interact { fontSettings.apply(mapOf(FontArea.TERMINAL to FontSpec("Monospaced", 10.0))) }
        assertEquals(26.0, fontSize(robot, terminal), "closed terminals no longer follow the settings")
        robot.interact {
            terminal.interrupt()
            terminal.close()
        }
        assertTrue(terminal.exited.get())
        assertNull(terminal.exitCode, "interrupting or closing again changes nothing")
    }

    @Test
    fun `embedded terminals do not count as open tabs, closing the terminals ends them`(robot: FxRobot) {
        val terminal = embed(robot, "sleep 30")
        robot.interact { terminals.open(dir) }
        assertEquals(1, terminals.openCount)

        robot.interact { terminals.close() }

        assertEquals(0, terminals.openCount)
        assertTrue(terminal.exited.get())
        eventually { assertFalse(terminal.isAlive) }
    }
}
