package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.TextEditor
import dev.sluide.fileviewers.press
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file-less text editor of dialogs. UI test because key routing through the scene and style spans on a CodeArea
 * need a scene (`uiTest`).
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TextAreaEditorUiTest {
    private val container = StackPane()

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(container, 400.0, 200.0)
        stage.show()
    }

    private fun open(
        robot: FxRobot,
        text: String,
    ): TextAreaEditor {
        val editor =
            WaitForAsyncUtils
                .asyncFx<TextAreaEditor> {
                    TextAreaEditor(text, Highlighters.markdown, highlightPause = Duration.ofMillis(20))
                }.get()
        robot.interact { container.children.setAll(editor.node) }
        return editor
    }

    @Test
    fun `shows the text, reflects edits, wraps and tab indents through the code view keys`(robot: FxRobot) {
        val editor = open(robot, "- item")
        assertEquals("- item", editor.text)
        assertTrue(editor.area.isWrapText)

        robot.interact {
            editor.focus()
            editor.area.moveTo(0)
        }
        robot.press(editor.area, KeyCode.TAB)

        assertEquals("    - item", editor.text, "the key map indents with four spaces, not a tab")
    }

    @Test
    fun `undo stops at the initial text, which has no history`(robot: FxRobot) {
        val editor = open(robot, "a")
        robot.interact { editor.area.insertText(1, "bc") }
        assertEquals("abc", editor.text)

        robot.interact {
            editor.area.undo()
            editor.area.undo()
        }

        assertEquals("a", editor.text, "the initial text is no undo step")
    }

    @Test
    fun `markdown is highlighted after a pause and styling is no undo step`(robot: FxRobot) {
        val editor = open(robot, "plain\n")

        robot.interact { editor.area.insertText(0, "# Title\n") }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { editor.area.getStyleOfChar(2).isNotEmpty() }

        robot.interact { editor.area.undo() }
        assertEquals("plain\n", editor.text)
    }

    @Test
    fun `the public editor wraps the same view`(robot: FxRobot) {
        val editor = WaitForAsyncUtils.asyncFx<TextEditor> { CodeEditors.text("x", null) }.get()
        robot.interact { container.children.setAll(editor.node) }
        assertEquals("x", editor.text)
        assertEquals("x", editor.textProperty.value)

        robot.interact {
            editor.replaceText("abc")
            editor.select(1)
        }
        val area = (editor.node as VirtualizedScrollPane<*>).content as CodeArea
        assertEquals("b", fx { area.selectedText }, "replaceText and select reach the area")
        assertTrue(fx { area.isFocused })

        robot.interact { editor.highlighter = Highlighters.markdown }
        assertEquals(Highlighters.markdown, editor.highlighter)
    }

    @Test
    fun `replacing the text is one undo step and keeps the caret's offset`(robot: FxRobot) {
        val editor = open(robot, "one\ntwo\nthree")
        robot.interact {
            editor.area.insertText(0, "1 ")
            editor.area.moveTo(5)
            editor.replaceText("eins\nzwei")
        }
        assertEquals("eins\nzwei", editor.text)
        assertEquals(5, fx { editor.area.caretPosition })

        robot.interact { editor.replaceText("x") }
        assertEquals(1, fx { editor.area.caretPosition }, "the caret goes to the end of a shorter text")

        robot.interact { editor.area.undo() }
        assertEquals("eins\nzwei", editor.text)
        robot.interact { editor.area.undo() }
        assertEquals("1 one\ntwo\nthree", editor.text, "each replacement is a single undo step")
    }

    @Test
    fun `select marks one character, or puts the caret at the end, and focuses`(robot: FxRobot) {
        val editor = open(robot, "abc")
        robot.interact { editor.select(1) }
        assertEquals("b", fx { editor.area.selectedText })
        assertTrue(fx { editor.area.isFocused })

        robot.interact { editor.select(3) }
        assertEquals("", fx { editor.area.selectedText })
        assertEquals(3, fx { editor.area.caretPosition })

        robot.interact { editor.select(99) }
        assertEquals(3, fx { editor.area.caretPosition }, "kept within the text")
    }

    @Test
    fun `a new highlighter highlights at once, none clears the styles`(robot: FxRobot) {
        val editor =
            WaitForAsyncUtils
                .asyncFx<TextAreaEditor> {
                    TextAreaEditor("# Title\n", null, highlightPause = Duration.ofMillis(20))
                }.get()
        robot.interact { container.children.setAll(editor.node) }
        assertTrue(fx { editor.area.getStyleOfChar(2).isEmpty() })

        robot.interact { editor.highlighter = Highlighters.markdown }
        assertTrue(fx { editor.area.getStyleOfChar(2).isNotEmpty() }, "highlighted without waiting for a pause")

        robot.interact { editor.highlighter = null }
        assertTrue(fx { editor.area.getStyleOfChar(2).isEmpty() })

        robot.interact {
            editor.highlighter = Highlighters.markdown
            editor.area.insertText(editor.area.length, "## Sub\n")
        }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) {
            fx { editor.area.getStyleOfChar(editor.area.length - 3).isNotEmpty() }
        }
    }

    @Test
    fun `an error mark stays over highlighting until removed or the next edit`(robot: FxRobot) {
        val editor = open(robot, "plain\n")

        robot.interact {
            editor.replaceText("# Title\n")
            editor.markError(2)
        }
        assertEquals(listOf(2), fx { errorChars(editor) })
        // The highlighting the replacement started arrives after the mark and keeps it.
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { fx { editor.area.getStyleOfChar(4).size > 0 } }
        assertEquals(listOf(2), fx { errorChars(editor) }, "kept by the background highlighting")

        robot.interact { editor.highlighter = null }
        assertEquals(listOf(2), fx { errorChars(editor) }, "kept when the highlighter changes")

        robot.interact { editor.markError(99) }
        assertEquals(listOf(6), fx { errorChars(editor) }, "past the end marks the last character but the line break")

        robot.interact { editor.markError(null) }
        assertEquals(emptyList(), fx { errorChars(editor) })

        robot.interact {
            editor.markError(0)
            editor.area.insertText(3, "x")
        }
        assertEquals(emptyList(), fx { errorChars(editor) }, "an edit removes the mark")
    }

    @Test
    fun `an overlay adds classes over the highlighting, a new one replaces it and the error mark coexists`(
        robot: FxRobot,
    ) {
        val editor = open(robot, "# Title\nsome words here\n")
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { fx { editor.area.getStyleOfChar(2).isNotEmpty() } }
        val before = fx { editor.area.getStyleOfChar(2).toSet() }

        robot.interact { editor.overlay(listOf(StyledRange(0, 7, "soft"), StyledRange(2, 4, "strong"))) }
        assertEquals(before + "soft" + "strong", fx { editor.area.getStyleOfChar(2).toSet() })
        assertEquals(setOf("soft"), fx { editor.area.getStyleOfChar(5).toSet() - before })
        assertEquals(false, fx { "soft" in editor.area.getStyleOfChar(9) })

        robot.interact { editor.markError(3) }
        assertEquals(listOf(3), fx { errorChars(editor) })
        assertTrue(fx { "strong" in editor.area.getStyleOfChar(3) }, "error mark keeps the overlay")

        robot.interact { editor.overlay(listOf(StyledRange(8, 12, "soft"), StyledRange(-5, 0, "gone"))) }
        assertEquals(false, fx { "soft" in editor.area.getStyleOfChar(2) || "strong" in editor.area.getStyleOfChar(2) })
        assertTrue(fx { "soft" in editor.area.getStyleOfChar(9) })
        assertEquals(listOf(3), fx { errorChars(editor) }, "the error mark stays")

        // The highlighting arrives after an edit and keeps the overlay.
        robot.interact { editor.area.insertText(editor.area.length, "# h") }
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) {
            fx { editor.area.getStyleOfChar(editor.area.length - 1).isNotEmpty() }
        }
        assertTrue(fx { "soft" in editor.area.getStyleOfChar(9) }, "kept by the background highlighting")

        robot.interact { editor.replaceText("# Other\n") }
        assertEquals(false, fx { (0 until editor.area.length).any { "soft" in editor.area.getStyleOfChar(it) } })
    }

    @Test
    fun `a read-only editor refuses typing but lets the caret move`(robot: FxRobot) {
        val editor = open(robot, "abc")
        robot.interact {
            editor.focus()
            editor.area.moveTo(3)
            editor.editable = false
        }
        robot.typeChar(editor, "x")
        assertEquals("abc", fx { editor.text })
        robot.press(editor.area, KeyCode.LEFT)
        assertEquals(2, fx { editor.caretOffset })

        robot.interact { editor.editable = true }
        robot.typeChar(editor, "x")
        assertEquals("abxc", fx { editor.text })
    }

    @Test
    fun `reveal scrolls a range far below into view and leaves the caret and the selection`(robot: FxRobot) {
        val editor = open(robot, (1..100).joinToString("\n") { "line $it" })
        robot.interact { editor.selectRange(2, 4) }
        robot.interact { editor.area.showParagraphAtTop(0) }
        val end = editor.text.length

        robot.interact { editor.reveal(end - 3, end) }

        assertTrue(fx { editor.area.lastVisibleParToAllParIndex() } >= 99, "the last line is shown")
        assertEquals(2, fx { editor.selection.start })
        assertEquals(4, fx { editor.caretOffset })
    }

    @Test
    fun `selection, caret offset and its property follow selectRange`(robot: FxRobot) {
        val editor = open(robot, "hello world")
        val seen = mutableListOf<Int>()
        robot.interact { editor.caretOffsetProperty.addListener { _, _, offset -> seen += offset } }

        robot.interact { editor.selectRange(2, 5) }

        assertEquals(2, fx { editor.selection.start })
        assertEquals(5, fx { editor.selection.end })
        assertEquals(5, fx { editor.caretOffset })
        assertTrue(5 in seen, "the property reported the caret move: $seen")
    }

    @Test
    fun `selectRange keeps the range within the text`(robot: FxRobot) {
        val editor = open(robot, "hello world")

        robot.interact { editor.selectRange(3, 99) }

        assertEquals(3, fx { editor.selection.start })
        assertEquals(11, fx { editor.selection.end })
    }

    @Test
    fun `selectRange scrolls the range into view`(robot: FxRobot) {
        val editor = open(robot, (1..100).joinToString("\n") { "line $it" })
        val end = editor.text.length

        robot.interact { editor.selectRange(end - 3, end) }

        assertTrue(fx { editor.area.lastVisibleParToAllParIndex() } >= 99, "the last line is shown")
    }

    @Test
    fun `replace puts the caret after a text of another length and each edit is its own undo step`(robot: FxRobot) {
        val editor = open(robot, "hello world")
        robot.interact { editor.area.insertText(0, "a") }

        robot.interact { editor.replace(1, 6, "hi") }

        assertEquals("ahi world", fx { editor.text })
        assertEquals(3, fx { editor.caretOffset })

        robot.interact { editor.area.insertText(3, "b") }
        robot.interact { editor.area.undo() }
        assertEquals("ahi world", fx { editor.text }, "typing after the replacement is its own step")
        robot.interact { editor.area.undo() }
        assertEquals("ahello world", fx { editor.text }, "the replacement is one step")
        robot.interact { editor.area.undo() }
        assertEquals("hello world", fx { editor.text }, "typing before it is its own step")
    }

    @Test
    fun `replace keeps the range within the text and inserts for a reversed one`(robot: FxRobot) {
        val editor = open(robot, "hello")

        robot.interact { editor.replace(5, 99, "!") }
        assertEquals("hello!", fx { editor.text })

        robot.interact { editor.replace(3, 1, "-") }
        assertEquals("hel-lo!", fx { editor.text })
        assertEquals(4, fx { editor.caretOffset })
    }

    @Test
    fun `caret screen bounds exist once shown and not before the editor is in a scene`(robot: FxRobot) {
        val editor = open(robot, "hello")
        val detached = fx { TextAreaEditor("x", null) }
        assertNull(fx { detached.caretScreenBounds() })
        assertNotNull(fx { editor.caretScreenBounds() })
    }

    /** The characters styled as an error; RichTextFX gives a line break the style of the character before it. */
    private fun errorChars(editor: TextAreaEditor): List<Int> =
        editor.area.let { area ->
            (0 until area.length).filter { area.text[it] != '\n' && "text-error" in area.getStyleOfChar(it) }
        }

    /** Types [text] into the editor as a key-typed event, not synthetic OS input. */
    private fun FxRobot.typeChar(
        editor: TextAreaEditor,
        text: String,
    ) = interact {
        Event.fireEvent(
            editor.area,
            KeyEvent(KeyEvent.KEY_TYPED, text, "", KeyCode.UNDEFINED, false, false, false, false),
        )
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()
}
