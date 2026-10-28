package dev.sluide.todos.view

import dev.sluide.todos.TodoFolder
import dev.sluide.todos.TodoScope
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UI tests of the folder dialog (controls need the toolkit); the dialog is not shown. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FolderDialogUiTest {
    @Start
    fun start(stage: Stage) {
        stage.show()
    }

    private fun dialog(
        robot: FxRobot,
        request: FolderDialogRequest = FolderDialogRequest(null, null, TodoScope.SHARED, true),
    ): FolderDialog {
        var made: FolderDialog? = null
        robot.interact { made = FolderDialog(request) }
        return requireNotNull(made)
    }

    private fun FolderDialog.answer(): FolderDraft? = resultConverter.call(dialogPane.buttonTypes.first())

    @Test
    fun `confirm is disabled while the name is blank`(robot: FxRobot) {
        val dialog = dialog(robot)
        assertTrue(dialog.confirmButton.isDisable)
        robot.interact { dialog.nameField.text = "  " }
        assertTrue(dialog.confirmButton.isDisable)
        robot.interact { dialog.nameField.text = "Release" }
        assertFalse(dialog.confirmButton.isDisable)
        assertEquals("Add", dialog.confirmButton.text)
    }

    @Test
    fun `the result carries the chosen scope and the trimmed name`(robot: FxRobot) {
        val dialog = dialog(robot)
        robot.interact {
            dialog.scopeButtons.getValue(TodoScope.PROJECT).fire()
            dialog.nameField.text = "  Release 1.0 "
        }
        assertEquals(FolderDraft(TodoScope.PROJECT, "Release 1.0"), dialog.answer())
        assertNull(dialog.resultConverter.call(dialog.dialogPane.buttonTypes.last()))
    }

    @Test
    fun `Project is disabled without a project folder`(robot: FxRobot) {
        assertTrue(
            dialog(
                robot,
                FolderDialogRequest(null, null, TodoScope.SHARED, false),
            ).scopeButtons.getValue(TodoScope.PROJECT).isDisable,
        )
        assertFalse(dialog(robot).scopeButtons.getValue(TodoScope.PROJECT).isDisable)
    }

    @Test
    fun `an edit starts with the folder and offers Save, a draft replaces it`(robot: FxRobot) {
        val folder = TodoFolder("f", "Old", false, emptyList())
        val edit = dialog(robot, FolderDialogRequest(null, folder, TodoScope.PROJECT, true))
        assertEquals("Save", edit.confirmButton.text)
        assertEquals(FolderDraft(TodoScope.PROJECT, "Old"), edit.answer())
        val again =
            dialog(
                robot,
                FolderDialogRequest(null, folder, TodoScope.PROJECT, true, FolderDraft(TodoScope.SHARED, "Typed")),
            )
        assertEquals(FolderDraft(TodoScope.SHARED, "Typed"), again.answer())
    }
}
