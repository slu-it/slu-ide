package dev.sluide.editor

import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The confirmation dialog shared by tabs and windows. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CloseConfirmationUiTest {
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 300.0, 200.0)
        stage.show()
    }

    @Test
    fun `lists the warnings under the question, with Close and Cancel`(robot: FxRobot) {
        lateinit var alert: Alert
        robot.interact {
            alert =
                closeConfirmation(
                    stage,
                    "Close Tabs",
                    "Close these tabs anyway?",
                    listOf("Terminal 1 is still running java.", "Claude Code 1 is still running: sbx run claude ."),
                )
        }

        assertEquals(Alert.AlertType.CONFIRMATION, alert.alertType)
        assertEquals(stage, alert.owner)
        assertEquals("Close Tabs", alert.title)
        assertEquals("Close these tabs anyway?", alert.headerText)
        assertEquals(
            "Terminal 1 is still running java.\nClaude Code 1 is still running: sbx run claude .",
            alert.contentText,
        )
        assertEquals(listOf("Close", "Cancel"), alert.buttonTypes.map { it.text })
        assertEquals(ButtonBar.ButtonData.OK_DONE, alert.buttonTypes.first().buttonData)
        assertEquals(ButtonType.CANCEL, alert.buttonTypes.last())
    }

    @Test
    fun `the Close button confirms`(robot: FxRobot) {
        lateinit var alert: Alert
        robot.interact {
            alert = closeConfirmation(null, "Close Window", "Close this window anyway?", listOf("Job 1 is running."))
            alert.show()
            (alert.dialogPane.lookupButton(CLOSE_BUTTON) as Button).fire()
        }

        assertFalse(alert.isShowing)
        assertEquals(CLOSE_BUTTON, alert.result)
    }
}
