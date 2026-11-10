package dev.sluide.fileviewers

import dev.sluide.projectdata.ProjectDataStore
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
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
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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

        assertEquals(40, editor.topLine)
        assertEquals(40, editor.caretLine)
        assertEquals(robot.area().getAbsolutePosition(40, 0), robot.area().caretPosition)
        robot.interact { editor.showLineAtTop(500) }
        assertEquals(99, editor.caretLine, "kept within the text")
        robot.interact { editor.showLineAtTop(-3) }
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
        assertTrue(changes > 0, "scrolled")
        val scrolled = changes
        // The editor, not the stage, which the next tests share.
        robot.interact { (editor.node as Region).maxHeight = 100.0 }
        assertTrue(changes > scrolled, "resized")

        robot.interact { subscription.unsubscribe() }
        val before = changes
        robot.interact { editor.showLineAtTop(10) }
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

        robot.interact { editor.addGutterColumn(16.0) { line -> if (line == 1) Label("run") else null } }

        val labels = robot.lookup(".gutter .label").queryAllAs(Label::class.java)
        assertEquals(listOf("run"), labels.filterNot { "lineno" in it.styleClass }.map { it.text })
    }

    @Test
    fun `the soft wrap button wraps, the editors of hosts sharing one switch follow each other until disposed`(
        robot: FxRobot,
    ) {
        val first = open(dir.resolve("a.json").apply { writeText("{}\n") }, robot)
        val button = first.softWrapButton as ToggleButton
        assertFalse(button.isFocusTraversable, "a click leaves the focus where it was")
        // Shortcut+Alt+Z as the platform writes it (Ctrl+Alt+Z, or with the macOS symbols).
        val key = KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN)
        assertEquals("Soft Wrap (${key.displayText})", button.tooltip.text)
        robot.interact { button.fire() }
        assertTrue(first.area().isWrapText, "without a host, it wraps on its own")
        robot.interact { button.fire() }
        assertFalse(first.area().isWrapText)

        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        // The writes are not this test's: they are dropped.
        val softWrap = SoftWrap(store, Duration.ZERO) {}
        val second = WaitForAsyncUtils.asyncFx<CodeEditor> { CodeEditors.open(dir.resolve("a.json"), null) }.get()
        robot.interact {
            first.host = host(softWrap)
            second.host = host(softWrap)
            button.fire()
        }
        assertTrue(softWrap.enabled.get())
        assertTrue(second.area().isWrapText, "the other editor follows")
        assertTrue((second.softWrapButton as ToggleButton).isSelected)

        robot.interact {
            second.dispose()
            button.fire()
        }
        assertFalse(first.area().isWrapText)
        assertTrue(second.area().isWrapText, "a closed editor no longer follows")
    }

    private fun CodeEditor.area() = node.lookup(".code-area") as CodeArea

    /** A host of a window whose services hold [softWrap]. */
    private fun host(softWrap: SoftWrap) =
        object : FileViewHost {
            private val services = WindowServices().apply { register(SoftWrap::class.java, softWrap) }

            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
        }

    private fun display(robot: FxRobot): CodeDisplay {
        val display = WaitForAsyncUtils.asyncFx<CodeDisplay> { CodeEditors.display() }.get()
        robot.interact { container.children.setAll(display.node) }
        return display
    }

    @Test
    fun `a display shows highlighted text that cannot be edited, with the caret at the top`(robot: FxRobot) {
        val display = display(robot)

        robot.interact { display.show("{\"a\": 1}\n", Highlighters.forLanguage("json")) }

        val area = robot.area()
        assertEquals("{\"a\": 1}\n", display.text)
        assertFalse(area.isEditable)
        assertTrue("property" in area.getStyleOfChar(2), area.getStyleOfChar(2).toString())
        assertEquals(0, area.caretPosition)
    }

    @Test
    fun `a display shows a text over the highlight limit plain`(robot: FxRobot) {
        val display = display(robot)
        val text = "{\"a\": 1}\n".repeat(CodeEditors.HIGHLIGHT_LIMIT / 9 + 1)
        assertTrue(text.length > CodeEditors.HIGHLIGHT_LIMIT)

        robot.interact { display.show(text, Highlighters.forLanguage("json")) }

        val area = robot.area()
        assertEquals(text.length, display.text.length)
        assertTrue(area.getStyleSpans(0, area.length).all { it.style.isEmpty() })
        assertEquals(0, area.caretPosition)
    }

    /** Content held in memory, as a decrypted file's; records what is written. */
    private class MemoryContent(
        var bytes: ByteArray,
    ) : FileContent {
        val written = mutableListOf<String>()

        override fun read(): ByteArray = bytes

        override fun write(bytes: ByteArray) {
            written += String(bytes)
            this.bytes = bytes
        }
    }

    @Test
    fun `an editor of content reads, saves and reloads through it and never touches its virtual path`(robot: FxRobot) {
        val virtual = dir.resolve("missing/notes.txt")
        val content = MemoryContent("a\r\nb\r\n".toByteArray())
        val editor =
            WaitForAsyncUtils.asyncFx<CodeEditor> { CodeEditors.open(virtual, null, content = content) }.get()
        robot.interact { container.children.setAll(editor.node) }
        assertEquals("a\nb\n", editor.text)

        robot.interact { editor.area().insertText(0, "x") }
        assertTrue(editor.dirty.get())
        robot.interact { editor.save() }
        assertEquals(listOf("xa\r\nb\r\n"), content.written)
        assertFalse(editor.dirty.get())

        content.bytes = "changed\r\n".toByteArray()
        robot.interact { editor.reload() }
        assertEquals("changed\n", editor.text)
        assertFalse(editor.dirty.get())

        robot.interact { editor.area().insertText(0, "mine ") }
        content.bytes = "theirs\n".toByteArray()
        robot.interact { editor.reload() }
        assertEquals("mine changed\n", editor.text, "unsaved changes meet a change of the content")
        assertTrue(editor.dirty.get())

        val moved = dir.resolve("elsewhere/notes.txt")
        robot.interact {
            editor.moved(moved)
            editor.save()
        }
        assertEquals("mine changed\r\n", content.written.last(), "a moved editor keeps its content")
        assertFalse(Files.exists(virtual.parent))
        assertFalse(Files.exists(moved.parent))
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
        // Valid UTF-8 in the sample that decides whether a file is text, after it a byte undefined in Windows-1252 too:
        // shown read-only.
        val file = dir.resolve("latin1.txt").apply { writeBytes("a".repeat(9000).toByteArray() + 0x81.toByte()) }
        val editor = open(file, robot)
        val before = editor.text

        var replaced = true
        robot.interact { replaced = editor.replaceText("b") }

        assertFalse(replaced)
        assertFalse(editor.canReplaceText)
        assertEquals(before, editor.text)
        assertFalse(editor.dirty.get())
    }

    @Test
    fun `a display moves by line with cmd left when the mac text keys are on`(robot: FxRobot) {
        val display = WaitForAsyncUtils.asyncFx<CodeDisplay> { AreaDisplay(macKeys = true) }.get()
        robot.interact {
            container.children.setAll(display.node)
            display.show("abc def\n", null)
        }
        val area = robot.area()
        robot.interact { area.moveTo(4) }

        robot.press(area, KeyCode.LEFT, meta = true)

        assertEquals(0, area.caretPosition)
    }
}
