package dev.sluide.fileviewers

import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.util.Subscription
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The public [CodeEditor] that other modules embed. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CodeEditorUiTest {
    @TempDir
    lateinit var dir: Path

    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 500.0, 300.0)
        stage.show()
    }

    private fun open(
        file: Path,
        robot: FxRobot,
    ): CodeEditor {
        val editor =
            WaitForAsyncUtils.asyncFx<CodeEditor> { CodeEditors.open(file, Highlighters.forLanguage("json")) }.get()
        robot.interact { container.children.setAll(editor.node) }
        return editor
    }

    private fun FxRobot.area() = lookup(".code-area").queryAs(CodeArea::class.java)

    @Test
    fun `gives its text and caret line, moves the caret and saves`(robot: FxRobot) {
        val file = dir.resolve("a.json").apply { writeText("{\r\n  \"a\": 1\r\n}\r\n") }
        val editor = open(file, robot)
        val texts = mutableListOf<String>()
        robot.interact { editor.textProperty.addListener { _, _, text -> texts += text } }

        robot.interact {
            editor.moveCaretTo(1)
            robot.area().insertText(robot.area().caretPosition, "  \"b\": 2,\n")
        }

        assertEquals("{\n  \"b\": 2,\n  \"a\": 1\n}\n", editor.text)
        assertEquals(listOf(editor.text), texts)
        assertEquals(2, editor.caretLine)
        assertTrue(editor.dirty.get())
        robot.interact { editor.save() }
        assertEquals("{\r\n  \"b\": 2,\r\n  \"a\": 1\r\n}\r\n", file.readText())
    }

    @Test
    fun `gives the saved text, which changes when saved`(robot: FxRobot) {
        val file = dir.resolve("a.json").apply { writeText("{\r\n}\r\n") }
        val editor = open(file, robot)
        val saved = mutableListOf<String>()
        robot.interact { editor.savedTextProperty.addListener { _, _, text -> saved += text } }

        robot.interact { robot.area().insertText(1, " ") }

        assertEquals("{\n}\n", editor.savedText)
        assertEquals("{\n}\n", editor.savedTextProperty.value)
        assertEquals(emptyList(), saved)
        robot.interact { editor.save() }
        assertEquals("{ \n}\n", editor.savedText)
        assertEquals(listOf("{ \n}\n"), saved)
    }

    @Test
    fun `tells every change of the text until unsubscribed`(robot: FxRobot) {
        val editor = open(dir.resolve("a.json").apply { writeText("{}\n") }, robot)
        var changes = 0
        val subscription = WaitForAsyncUtils.asyncFx<Subscription> { editor.onTextChanged { changes++ } }.get()

        robot.interact {
            robot.area().insertText(1, " ")
            robot.area().insertText(1, " ")
        }
        robot.interact { editor.replaceText("{\"a\": 1}\n") }
        assertEquals(3, changes)

        robot.interact { subscription.unsubscribe() }
        robot.interact { robot.area().insertText(1, " ") }
        assertEquals(3, changes)
    }

    @Test
    fun `tells when the caret line changes`(robot: FxRobot) {
        val editor = open(dir.resolve("a.json").apply { writeText("{\n  \"a\": 1\n}\n") }, robot)
        val lines = mutableListOf<Int>()
        robot.interact { editor.caretLineProperty.addListener { _, _, line -> lines += line } }

        robot.interact { editor.moveCaretTo(2) }
        robot.interact { robot.area().moveTo(0) }

        assertEquals(listOf(2, 0), lines)
        assertEquals(0, editor.caretLineProperty.value)
    }

    @Test
    fun `shows a line at the top, with the caret at its start`(robot: FxRobot) {
        val file = dir.resolve("a.json").apply { writeText((0 until 100).joinToString("\n") { "$it," }) }
        val editor = open(file, robot)
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(0, editor.topLine)

        robot.interact { editor.showLineAtTop(40) }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(40, editor.topLine)
        assertEquals(40, editor.caretLine)
        assertEquals(robot.area().getAbsolutePosition(40, 0), robot.area().caretPosition)
        robot.interact { editor.showLineAtTop(500) }
        assertEquals(99, editor.caretLine, "kept within the text")
        robot.interact { editor.showLineAtTop(-3) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(0, editor.topLine)
    }

    @Test
    fun `tells when the shown lines change until unsubscribed`(robot: FxRobot) {
        val file = dir.resolve("a.json").apply { writeText((0 until 100).joinToString("\n") { "$it," }) }
        val editor = open(file, robot)
        WaitForAsyncUtils.waitForFxEvents()
        var changes = 0
        val subscription = WaitForAsyncUtils.asyncFx<Subscription> { editor.onShownLinesChanged { changes++ } }.get()

        robot.interact { editor.showLineAtTop(40) }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(changes > 0, "scrolled")
        val scrolled = changes
        // The editor, not the stage, which the next tests share.
        robot.interact { (editor.node as Region).maxHeight = 100.0 }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(changes > scrolled, "resized")

        robot.interact { subscription.unsubscribe() }
        val before = changes
        robot.interact { editor.showLineAtTop(10) }
        WaitForAsyncUtils.waitForFxEvents()
        assertEquals(before, changes)
    }

    @Test
    fun `comments lines with the given syntax`(robot: FxRobot) {
        val file = dir.resolve("a.req").apply { writeText("GET x\n") }
        val editor = WaitForAsyncUtils.asyncFx<CodeEditor> { CodeEditors.open(file, null, lineComment = "#") }.get()
        robot.interact { container.children.setAll(editor.node) }

        robot.press(robot.area(), KeyCode.DIGIT7, shortcut = true, shift = true)

        assertEquals("# GET x\n", editor.text)
    }

    @Test
    fun `shows added gutter columns`(robot: FxRobot) {
        val editor = open(dir.resolve("a.json").apply { writeText("{\n}\n") }, robot)

        robot.interact { editor.addGutterColumn { line -> if (line == 1) Label("run") else null } }
        WaitForAsyncUtils.waitForFxEvents()

        val labels = robot.lookup(".gutter .label").queryAllAs(Label::class.java)
        assertEquals(listOf("run"), labels.filterNot { "lineno" in it.styleClass }.map { it.text })
    }

    @Test
    fun `replaces its text as one undo step that marks it dirty`(robot: FxRobot) {
        val file = dir.resolve("a.json").apply { writeText("{\n  \"a\": 1,\n  \"b\": 2\n}\n") }
        val editor = open(file, robot)
        robot.interact { editor.moveCaretTo(2) }

        robot.interact { editor.replaceText("{\n  \"a\": 10,\n  \"b\": 2\n}\n") }

        assertEquals("{\n  \"a\": 10,\n  \"b\": 2\n}\n", editor.text)
        assertTrue(editor.dirty.get())
        assertEquals(2, editor.caretLine)
        robot.interact { robot.area().undo() }
        assertEquals("{\n  \"a\": 1,\n  \"b\": 2\n}\n", editor.text)
        assertFalse(editor.dirty.get())
        assertFalse(robot.area().isUndoAvailable)
    }

    @Test
    fun `typing after a replaced text is an undo step of its own`(robot: FxRobot) {
        val editor = open(dir.resolve("a.json").apply { writeText("{\"a\": 1}\n") }, robot)

        robot.interact {
            robot.area().insertText(1, " ")
            editor.replaceText("{ \"a\": 12}\n")
            robot.area().insertText(9, "3")
        }

        assertEquals("{ \"a\": 123}\n", editor.text)
        robot.interact { robot.area().undo() }
        assertEquals("{ \"a\": 12}\n", editor.text)
        robot.interact { robot.area().undo() }
        assertEquals("{ \"a\": 1}\n", editor.text)
        robot.interact { robot.area().undo() }
        assertEquals("{\"a\": 1}\n", editor.text)
    }

    @Test
    fun `replacing its text by an equal one changes nothing`(robot: FxRobot) {
        val editor = open(dir.resolve("a.json").apply { writeText("{}\n") }, robot)

        var replaced = false
        robot.interact { replaced = editor.replaceText("{}\r\n") }

        assertTrue(replaced)
        assertFalse(editor.dirty.get())
        assertFalse(robot.area().isUndoAvailable)
    }

    @Test
    fun `a read-only editor does not replace its text`(robot: FxRobot) {
        // Valid in the sample that decides whether a file is text, invalid after it: shown read-only.
        val file = dir.resolve("latin1.txt").apply { writeBytes("a".repeat(9000).toByteArray() + 0xE9.toByte()) }
        val editor = open(file, robot)
        val before = editor.text

        var replaced = true
        robot.interact { replaced = editor.replaceText("b") }

        assertFalse(replaced)
        assertFalse(editor.canReplaceText)
        assertEquals(before, editor.text)
        assertFalse(editor.dirty.get())
    }
}
