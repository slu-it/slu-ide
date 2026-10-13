package dev.sluide.terminal

import dev.sluide.settings.FontArea
import dev.sluide.settings.FontSettings
import dev.sluide.settings.FontSpec
import dev.sluide.settings.SettingsStore
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.image.WritableImage
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
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
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Runs the user's shell in a real terminal view. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TerminalUiTest {
    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var settingsDir: Path

    private val fontSettings by lazy {
        FontSettings(SettingsStore(settingsDir.resolve("settings.properties")), FontSettings.systemDefaults())
    }
    private val terminals by lazy { Terminals(fontSettings) }
    private lateinit var stage: Stage
    private lateinit var root: StackPane

    @AfterEach
    fun closeTerminals() = terminals.close()

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        root = StackPane()
        stage.scene = Scene(root, 800.0, 400.0)
        stage.show()
    }

    private fun open(robot: FxRobot): TerminalTab {
        lateinit var tab: TerminalTab
        robot.interact {
            tab = terminals.open(dir) as TerminalTab
            root.children.setAll(tab.content)
        }
        // Input typed before the prompt would share a line with it.
        eventually { assertTrue(tab.view.screenText().isNotBlank(), "a prompt") }
        return tab
    }

    /** Types [text] and presses Enter by firing key events at the view, which is what its handlers see. */
    private fun type(
        robot: FxRobot,
        view: TerminalView,
        text: String,
    ) = robot.interact {
        for (char in text) {
            val s = char.toString()
            Event.fireEvent(view, KeyEvent(KeyEvent.KEY_TYPED, s, "", KeyCode.UNDEFINED, false, false, false, false))
        }
        Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
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
    fun `a terminal tab runs the shell in the folder and shows its output`(robot: FxRobot) {
        val tab = open(robot)
        assertEquals("Terminal 1", tab.title.value)
        assertSame(tab.view, tab.view.scene.focusOwner, "focused when shown")

        type(robot, tab.view, "cd . && pwd && echo answer-\$((40 + 2))")

        eventually {
            val lines = tab.view.screenText().lines()
            assertTrue(dir.toString() in lines, tab.view.screenText())
            assertTrue("answer-42" in lines, tab.view.screenText())
        }
    }

    @Test
    fun `output is drawn on the canvas`(robot: FxRobot) {
        val tab = open(robot)
        type(robot, tab.view, "printf '\\033[42m%80s\\033[0m\\n' ''")

        eventually {
            lateinit var image: WritableImage
            robot.interact { image = tab.view.snapshot(null, null) }
            val green =
                (0 until image.height.toInt() step 4).any { y ->
                    (0 until image.width.toInt() step 8).any { x ->
                        image.pixelReader.getColor(x, y).let { it.green > 0.6 && it.red < 0.2 && it.blue < 0.2 }
                    }
                }
            assertTrue(green, "a green background line is painted")
        }
    }

    @Test
    fun `dragging selects text that the copy shortcut puts on the clipboard`(robot: FxRobot) {
        val tab = open(robot)
        val view = tab.view
        type(robot, view, "clear; echo copy-\$((6 * 7))-please")
        eventually { assertTrue("copy-42-please" in view.screenText().lines()) }
        val row = view.screenText().lines().indexOf("copy-42-please")
        val cell = view.cell
        val y = (row + 0.5) * cell.height

        robot.interact {
            Clipboard.getSystemClipboard().clear()
            mouse(view, MouseEvent.MOUSE_PRESSED, 0.2 * cell.width, y)
            // Past the middle of the 7th character: the selection includes it.
            mouse(view, MouseEvent.MOUSE_DRAGGED, 6.6 * cell.width, y)
            val shortcut = !System.getProperty("os.name").lowercase().contains("mac")
            Event.fireEvent(
                view,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, true, shortcut, false, !shortcut),
            )
        }

        robot.interact { assertEquals("copy-42", Clipboard.getSystemClipboard().string) }
    }

    private fun mouse(
        view: TerminalView,
        type: javafx.event.EventType<MouseEvent>,
        x: Double,
        y: Double,
    ) {
        val scene = view.localToScene(x, y)
        Event.fireEvent(
            view,
            MouseEvent(
                type,
                scene.x,
                scene.y,
                scene.x,
                scene.y,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                null,
            ),
        )
    }

    @Test
    fun `the mouse wheel is reported to full-screen programs that ask for it, not used for scrollback`(
        robot: FxRobot,
    ) {
        val tab = open(robot)
        val view = tab.view
        // Like Claude Code's fullscreen TUI: alternate screen, mouse reports in SGR format.
        type(robot, view, "printf '\\033[?1049h\\033[?1000h\\033[?1006h'; echo ready; cat -v")
        eventually { assertTrue("ready" in view.screenText().lines(), view.screenText()) }

        robot.interact {
            scroll(view, 2.5 * view.cell.width, 1.5 * view.cell.height, deltaY = 40.0)
            Event.fireEvent(view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
        }

        eventually { assertTrue("^[[<64;3;2M" in view.screenText().lines(), view.screenText()) }
        assertEquals(0, view.scrollOrigin)
    }

    private fun scroll(
        view: TerminalView,
        x: Double,
        y: Double,
        deltaY: Double,
    ) {
        val scene = view.localToScene(x, y)
        Event.fireEvent(
            view,
            ScrollEvent(
                ScrollEvent.SCROLL,
                scene.x,
                scene.y,
                scene.x,
                scene.y,
                false,
                false,
                false,
                false,
                false,
                false,
                0.0,
                deltaY,
                0.0,
                deltaY,
                ScrollEvent.HorizontalTextScrollUnits.NONE,
                0.0,
                ScrollEvent.VerticalTextScrollUnits.NONE,
                0.0,
                0,
                null,
            ),
        )
    }

    @Test
    fun `the tab warns against closing while a program runs`(robot: FxRobot) {
        val tab = open(robot)
        assertEquals(null, tab.closeWarning)

        type(robot, tab.view, "sleep 300")
        eventually { assertEquals("Terminal 1 is still running sleep.", tab.closeWarning) }

        robot.interact {
            Event.fireEvent(tab.view, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C, false, true, false, false))
        }
        eventually { assertEquals(null, tab.closeWarning) }
    }

    @Test
    fun `a running terminal follows font changes and tells the shell its new size`(robot: FxRobot) {
        val tab = open(robot)
        val before = tab.view.cell

        fun shellSize(): String {
            type(robot, tab.view, "clear; echo size=\$(stty size | tr ' ' x)")
            lateinit var line: String
            eventually {
                line =
                    tab.view
                        .screenText()
                        .lines()
                        .single { it.startsWith("size=") }
            }
            return line
        }
        val sizeBefore = shellSize()

        robot.interact { fontSettings.apply(mapOf(FontArea.TERMINAL to FontSpec("Monospaced", 26.0))) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(26.0, tab.view.font.size)
        assertTrue(tab.view.cell.width > before.width * 1.5, "cells grow: ${before.width} -> ${tab.view.cell.width}")
        eventually { assertTrue(shellSize() != sizeBefore, "the shell sees fewer rows and columns than $sizeBefore") }

        robot.interact { tab.dispose() }
        robot.interact { fontSettings.apply(mapOf(FontArea.TERMINAL to FontSpec("Monospaced", 10.0))) }
        assertEquals(26.0, tab.view.font.size, "closed terminals no longer follow the settings")
    }

    @Test
    fun `the tab shows when the shell exits, closing it ends the shell`(robot: FxRobot) {
        val first = open(robot)
        type(robot, first.view, "exit")
        eventually { assertEquals("Terminal 1 (exited)", first.title.value) }

        val second = open(robot)
        assertEquals("Terminal 2", second.title.value)
        assertEquals(2, terminals.openCount)

        robot.interact { second.dispose() }

        assertTrue(
            second.view.session.process
                .waitFor(5, TimeUnit.SECONDS),
        )
        assertEquals(1, terminals.openCount)
    }
}
