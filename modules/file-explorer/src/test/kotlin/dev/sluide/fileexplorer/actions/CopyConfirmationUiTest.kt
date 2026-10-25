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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Shows the real dialog without blocking. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CopyConfirmationUiTest {
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    @Test
    fun `Cancel is the default button and has the focus, so Enter copies nothing`(robot: FxRobot) {
        val question = copyUnsavedQuestion(listOf("a.txt", "b.txt"))
        lateinit var dialog: Alert
        robot.interact {
            dialog = copyConfirmation(stage, question)
            dialog.show()
        }
        val copy = dialog.dialogPane.lookupButton(COPY_BUTTON) as Button
        val cancel = dialog.dialogPane.lookupButton(ButtonType.CANCEL) as Button

        assertEquals("Copy", dialog.title)
        assertEquals(question.header, dialog.headerText)
        assertEquals(question.details, dialog.contentText)
        assertEquals("Copy", copy.text)
        assertTrue(cancel.isDefaultButton)
        assertFalse(copy.isDefaultButton)
        assertSame(cancel, dialog.dialogPane.scene.focusOwner)

        // Enter fires the default button through the scene's accelerator.
        robot.interact {
            dialog.dialogPane.scene.accelerators
                .getValue(KeyCodeCombination(KeyCode.ENTER))
                .run()
        }

        assertEquals(ButtonType.CANCEL, dialog.result)
        assertFalse(dialog.isShowing)
    }
}
