package dev.sluide.fileexplorer.actions

import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Shows the real dialog without blocking. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MoveConfirmationUiTest {
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    private fun show(
        robot: FxRobot,
        question: MoveQuestion,
    ): Alert {
        lateinit var dialog: Alert
        robot.interact {
            dialog = moveConfirmation(stage, question)
            dialog.show()
        }
        WaitForAsyncUtils.waitForFxEvents()
        return dialog
    }

    @Test
    fun `Cancel is the default button and has the focus, so Enter moves nothing`(robot: FxRobot) {
        val question = moveQuestion(listOf("a.txt", "src"), emptyList(), "target")
        val dialog = show(robot, question)
        val move = dialog.dialogPane.lookupButton(MOVE_BUTTON) as Button
        val cancel = dialog.dialogPane.lookupButton(ButtonType.CANCEL) as Button

        assertEquals("Move", dialog.title)
        assertEquals(question.header, dialog.headerText)
        assertEquals(question.details, dialog.contentText)
        assertEquals("Move", move.text)
        assertTrue(cancel.isDefaultButton)
        assertFalse(move.isDefaultButton)
        assertSame(cancel, dialog.dialogPane.scene.focusOwner)

        // Enter fires the default button through the scene's accelerator.
        robot.interact {
            dialog.dialogPane.scene.accelerators
                .getValue(KeyCodeCombination(KeyCode.ENTER))
                .run()
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(ButtonType.CANCEL, dialog.result)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `with skipped entries the button reads Move the others`(robot: FxRobot) {
        val dialog = show(robot, moveQuestion(listOf("a.txt"), listOf("b.txt"), "target"))

        assertNull(dialog.dialogPane.lookupButton(MOVE_BUTTON))
        assertEquals("Move the others", (dialog.dialogPane.lookupButton(MOVE_OTHERS_BUTTON) as Button).text)
        assertTrue((dialog.dialogPane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton)

        robot.interact { (dialog.dialogPane.lookupButton(MOVE_OTHERS_BUTTON) as Button).fire() }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(MOVE_OTHERS_BUTTON, dialog.result)
    }
}
