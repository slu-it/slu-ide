package dev.sluide.todos.view

import dev.sluide.todos.Todo
import dev.sluide.todos.TodoFolder
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UI test of the delete confirmation: the dialog is built but not shown, as it would block. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DeleteConfirmationUiTest {
    @Start
    fun start(stage: Stage) {
        stage.show()
    }

    @Test
    fun `Cancel is the default button, so Enter does not delete`(robot: FxRobot) {
        var alert: Alert? = null
        robot.interact { alert = deleteConfirmation(null, Todo("id", "Buy milk", "")) }
        val pane = requireNotNull(alert).dialogPane
        assertTrue((pane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        assertFalse((pane.lookupButton(DELETE_BUTTON) as Button).isDefaultButton)
        assertEquals("Delete TODO “Buy milk”?", requireNotNull(alert).headerText)
    }

    private fun folderAlert(
        robot: FxRobot,
        todos: List<Todo>,
    ): Alert {
        var alert: Alert? = null
        robot.interact { alert = folderDeletionConfirmation(null, TodoFolder("f", "Release", false, todos)) }
        return requireNotNull(alert)
    }

    @Test
    fun `an empty folder asks Delete or Cancel and Cancel is the default`(robot: FxRobot) {
        val alert = folderAlert(robot, emptyList())
        assertEquals(listOf(DELETE_BUTTON, ButtonType.CANCEL), alert.dialogPane.buttonTypes)
        assertEquals("Delete folder “Release”?", alert.headerText)
        assertTrue((alert.dialogPane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        assertFalse((alert.dialogPane.lookupButton(DELETE_BUTTON) as Button).isDefaultButton)
    }

    @Test
    fun `a folder with TODOs asks Delete All, Keep TODOs or Cancel and Cancel is the default`(robot: FxRobot) {
        val alert = folderAlert(robot, listOf(Todo("a", "A", ""), Todo("b", "B", "")))
        assertEquals(listOf(DELETE_ALL_BUTTON, KEEP_TODOS_BUTTON, ButtonType.CANCEL), alert.dialogPane.buttonTypes)
        assertEquals("Delete folder “Release” with its 2 TODOs?", alert.headerText)
        assertEquals("Keep TODOs moves them out of the folder.", alert.contentText)
        assertTrue((alert.dialogPane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)
        assertFalse((alert.dialogPane.lookupButton(DELETE_ALL_BUTTON) as Button).isDefaultButton)
        assertFalse((alert.dialogPane.lookupButton(KEEP_TODOS_BUTTON) as Button).isDefaultButton)
    }
}
