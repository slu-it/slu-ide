package dev.sluide.todos.view

import dev.sluide.todos.Todo
import dev.sluide.todos.TodoScope
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.text.TextFlow
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UI tests of the TODO dialog (controls and the preview need the toolkit); the dialog is not shown. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TodoDialogUiTest {
    @Start
    fun start(stage: Stage) {
        stage.show()
    }

    private fun request(
        todo: Todo? = null,
        scope: TodoScope = TodoScope.SHARED,
        projectPossible: Boolean = true,
    ) = TodoDialogRequest(null, todo, scope, projectPossible, Path.of("."), { PlainDescriptionEditor(it) }, {}, {})

    private fun dialog(
        robot: FxRobot,
        request: TodoDialogRequest,
    ): TodoDialog {
        var made: TodoDialog? = null
        robot.interact { made = TodoDialog(request) }
        return requireNotNull(made)
    }

    private fun TodoDialog.answer(): TodoDraft? = resultConverter.call(dialogPane.buttonTypes.first())

    private fun descendants(node: Node): List<Node> =
        listOf(node) + ((node as? Parent)?.childrenUnmodifiable.orEmpty().flatMap(::descendants))

    @Test
    fun `confirm is disabled while the summary is blank`(robot: FxRobot) {
        val dialog = dialog(robot, request())
        assertTrue(dialog.confirmButton.isDisable)
        robot.interact { dialog.summaryField.text = "  " }
        assertTrue(dialog.confirmButton.isDisable)
        robot.interact { dialog.summaryField.text = "Do it" }
        assertFalse(dialog.confirmButton.isDisable)
        assertEquals("Add", dialog.confirmButton.text)
    }

    @Test
    fun `the result carries the chosen scope, summary and description without trailing whitespace`(robot: FxRobot) {
        val dialog = dialog(robot, request())
        robot.interact {
            dialog.scopeButtons.getValue(TodoScope.PROJECT).fire()
            dialog.summaryField.text = "Do it  "
            (dialog.editor.node as TextArea).text = "- a\n\n"
        }
        assertEquals(TodoDraft(TodoScope.PROJECT, "Do it", "- a"), dialog.answer())
    }

    @Test
    fun `an edit starts with the TODO and offers Save`(robot: FxRobot) {
        val dialog = dialog(robot, request(Todo("id", "Old", "text"), TodoScope.PROJECT))
        assertEquals("Save", dialog.confirmButton.text)
        assertEquals(TodoScope.PROJECT, dialog.scope)
        assertEquals(TodoDraft(TodoScope.PROJECT, "Old", "text"), dialog.answer())
    }

    @Test
    fun `the selected scope cannot be deselected`(robot: FxRobot) {
        val dialog = dialog(robot, request())
        robot.interact { dialog.scopeButtons.getValue(TodoScope.SHARED).fire() }
        assertEquals(TodoScope.SHARED, dialog.scope)
    }

    @Test
    fun `Preview renders the editor text and Write brings the editor back`(robot: FxRobot) {
        val dialog = dialog(robot, request())
        robot.interact {
            (dialog.editor.node as TextArea).text = "# Hi"
            dialog.previewButton.fire()
        }
        val preview = dialog.descriptionHolder.children.single() as ScrollPane
        assertTrue(descendants(preview.content).any { it is TextFlow && it.styleClass.contains("h1") })
        robot.interact {
            (dialog.editor.node as TextArea).text = "## Other"
            dialog.writeButton.fire()
            dialog.previewButton.fire()
        }
        val again = dialog.descriptionHolder.children.single() as ScrollPane
        assertTrue(descendants(again.content).any { it is TextFlow && it.styleClass.contains("h2") })
        robot.interact { dialog.writeButton.fire() }
        assertTrue(dialog.descriptionHolder.children.single() === dialog.editor.node)
    }

    @Test
    fun `Project is disabled without a folder`(robot: FxRobot) {
        val dialog = dialog(robot, request(projectPossible = false))
        assertTrue(dialog.scopeButtons.getValue(TodoScope.PROJECT).isDisable)
        assertFalse(dialog.scopeButtons.getValue(TodoScope.SHARED).isDisable)
    }

    @Test
    fun `cancel gives no draft`(robot: FxRobot) {
        val dialog = dialog(robot, request())
        assertNull(dialog.resultConverter.call(dialog.dialogPane.buttonTypes.last()))
    }
}
