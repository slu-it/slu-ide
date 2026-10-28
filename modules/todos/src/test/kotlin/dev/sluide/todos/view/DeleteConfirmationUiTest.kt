package dev.sluide.todos.view

import dev.sluide.todos.Todo
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
}
