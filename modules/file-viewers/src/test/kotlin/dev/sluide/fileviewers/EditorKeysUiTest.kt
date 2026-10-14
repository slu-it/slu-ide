package dev.sluide.fileviewers

import dev.sluide.textediting.IndentStyle
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** The editing keys of a real [CodeView], fired as key events at its area. Needs a display (`uiTest`). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorKeysUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    private fun open(
        name: String,
        text: String,
        robot: FxRobot,
    ): CodeView {
        val file = dir.resolve(name).apply { writeText(text) }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(file, KotlinFileViewer().highlighter) }.get()
        robot.interact { container.children.setAll(view.node) }
        return view
    }

    private fun CodeView.select(
        anchor: Int,
        caret: Int = anchor,
        robot: FxRobot,
    ) = robot.interact { area.selectRange(anchor, caret) }

    @Test
    fun `enter keeps the indentation of the line`(robot: FxRobot) {
        val view = open("Main.kt", "    val x = 1", robot)
        view.select(13, robot = robot)

        robot.press(view.area, KeyCode.ENTER)

        assertEquals("    val x = 1\n    ", view.area.text)
        assertEquals(18, view.area.caretPosition)
    }

    @Test
    fun `enter between brackets indents in the style of the editorconfig`(robot: FxRobot) {
        dir.resolve(".editorconfig").writeText("root = true\n\n[*.kt]\nindent_size = 2\n")
        val view = open("Main.kt", "fun f() {}", robot)
        assertEquals(IndentStyle(useTabs = false, size = 2), view.indentStyle)
        view.select(9, robot = robot)

        robot.press(view.area, KeyCode.ENTER)

        assertEquals("fun f() {\n  \n}", view.area.text)
        assertEquals(12, view.area.caretPosition)
    }

    @Test
    fun `tab indents selected lines, shift+tab outdents them, each one undo step`(robot: FxRobot) {
        val view = open("Main.kt", "a\nb\n", robot)
        view.select(0, 3, robot)

        robot.press(view.area, KeyCode.TAB)
        assertEquals("    a\n    b\n", view.area.text)
        assertEquals(0 to 11, view.area.anchor to view.area.caretPosition, "the lines stay selected")

        robot.press(view.area, KeyCode.TAB, shift = true)
        assertEquals("a\nb\n", view.area.text)

        robot.interact { view.area.undo() }
        assertEquals("    a\n    b\n", view.area.text)
        robot.interact { view.area.undo() }
        assertEquals("a\nb\n", view.area.text)
    }

    @Test
    fun `shift+enter is enter`(robot: FxRobot) {
        val view = open("Main.kt", "    f()", robot)
        view.select(7, robot = robot)

        robot.press(view.area, KeyCode.ENTER, shift = true)

        assertEquals("    f()\n    ", view.area.text)
    }

    @Test
    fun `tab over several lines is an undo step of its own, also right after typing`(robot: FxRobot) {
        val view = open("Main.kt", "a\nb\n", robot)
        robot.interact {
            view.area.insertText(1, "x")
            view.area.selectRange(0, 4)
        }

        robot.press(view.area, KeyCode.TAB)
        assertEquals("    ax\n    b\n", view.area.text)

        robot.interact { view.area.undo() }
        assertEquals("ax\nb\n", view.area.text)
    }

    @Test
    fun `tab in a line inserts spaces up to the next indentation stop`(robot: FxRobot) {
        val view = open("Main.kt", "ab", robot)
        view.select(1, robot = robot)

        robot.press(view.area, KeyCode.TAB)

        assertEquals("a   b", view.area.text)
        assertEquals(4, view.area.caretPosition)
    }

    @Test
    fun `shortcut+d duplicates the line as an undo step of its own`(robot: FxRobot) {
        val view = open("Main.kt", "a\nb", robot)
        // Typing right before, which the undo manager would otherwise merge with the copy.
        robot.interact { view.area.insertText(1, "x") }
        view.select(2, robot = robot)

        robot.press(view.area, KeyCode.D, shortcut = true)
        assertEquals("ax\nax\nb", view.area.text)
        assertEquals(5, view.area.caretPosition, "the caret moves along to the copy")

        robot.interact { view.area.undo() }
        assertEquals("ax\nb", view.area.text)
    }

    @Test
    fun `shortcut+d duplicates the selection and selects the copy`(robot: FxRobot) {
        val view = open("Main.kt", "abc", robot)
        view.select(0, 2, robot)

        robot.press(view.area, KeyCode.D, shortcut = true)

        assertEquals("ababc", view.area.text)
        assertEquals("ab", view.area.selectedText)
        assertEquals(2, view.area.selection.start)
    }

    @Test
    fun `shortcut+shift+k deletes the touched lines`(robot: FxRobot) {
        val view = open("Main.kt", "a\nb\nc\nd", robot)
        view.select(2, 5, robot)

        robot.press(view.area, KeyCode.K, shortcut = true, shift = true)

        assertEquals("a\nd", view.area.text)
        robot.interact { view.area.undo() }
        assertEquals("a\nb\nc\nd", view.area.text)
    }

    @Test
    fun `alt+shift+up and down move the lines with the caret`(robot: FxRobot) {
        val view = open("Main.kt", "a\nb\nc", robot)
        view.select(2, robot = robot)

        robot.press(view.area, KeyCode.DOWN, alt = true, shift = true)
        assertEquals("a\nc\nb", view.area.text)
        assertEquals(4, view.area.caretPosition)

        robot.press(view.area, KeyCode.UP, alt = true, shift = true)
        robot.press(view.area, KeyCode.UP, alt = true, shift = true)
        assertEquals("b\na\nc", view.area.text)
        assertEquals(0, view.area.caretPosition)

        robot.press(view.area, KeyCode.UP, alt = true, shift = true)
        assertEquals("b\na\nc", view.area.text, "the first line does not move up")
    }

    @Test
    fun `keys change nothing in a read-only view`(robot: FxRobot) {
        val file = dir.resolve("latin1.txt").apply { writeBytes(ByteArray(9000) { 'a'.code.toByte() } + 0xE9.toByte()) }
        val view = WaitForAsyncUtils.asyncFx<CodeView> { CodeView(file, null) }.get()
        robot.interact { container.children.setAll(view.node) }
        val text = view.area.text

        robot.press(view.area, KeyCode.ENTER)
        robot.press(view.area, KeyCode.TAB)
        robot.press(view.area, KeyCode.TAB, shift = true)
        robot.press(view.area, KeyCode.D, shortcut = true)
        robot.press(view.area, KeyCode.K, shortcut = true, shift = true)
        robot.press(view.area, KeyCode.DOWN, alt = true, shift = true)

        assertEquals(text, view.area.text)
    }
}
