package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.TextEditor
import dev.sluide.fileviewers.press
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.layout.StackPane
import javafx.stage.Stage
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
    }
}
