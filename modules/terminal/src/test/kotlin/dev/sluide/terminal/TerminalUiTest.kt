package dev.sluide.terminal

import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.image.WritableImage
import javafx.scene.input.Clipboard
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.scene.text.Font
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

    private val terminals = Terminals()
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
        val cell = CellSize.of(Font.font("Monospaced", 13.0))
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
