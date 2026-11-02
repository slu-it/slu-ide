package dev.sluide.ide

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

/** Shows the real dialog without blocking. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class OpenFolderConfirmationUiTest {
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    @Test
    fun `Cancel is the default button and has the focus, so Enter keeps the database connections`(robot: FxRobot) {
        val warnings = listOf("orders-dev: uncommitted transaction (rolled back)")
        lateinit var dialog: Alert
        robot.interact {
            dialog = openFolderConfirmation(stage, warnings)
            dialog.show()
        }

        // Read on the FX thread: the dialog focuses its button there.
        val state =
            WaitForAsyncUtils
                .asyncFx<List<Any?>> {
                    val cancel = dialog.dialogPane.lookupButton(ButtonType.CANCEL) as Button
                    val open = dialog.dialogPane.lookupButton(OPEN_BUTTON) as Button
                    listOf(
                        dialog.title,
                        dialog.contentText,
                        cancel.isDefaultButton,
                        open.isDefaultButton,
                        cancel === dialog.dialogPane.scene.focusOwner,
                    )
                }.get()
        assertEquals(
            listOf(
                "Open Folder",
                "Opening the folder disconnects these databases:\norders-dev: uncommitted transaction (rolled back)",
                true,
                false,
                true,
            ),
            state,
        )

        // Enter fires the default button through the scene's accelerator.
        robot.interact {
            dialog.dialogPane.scene.accelerators
                .getValue(KeyCodeCombination(KeyCode.ENTER))
                .run()
        }

        assertEquals(ButtonType.CANCEL, WaitForAsyncUtils.asyncFx<ButtonType?> { dialog.result }.get())
        assertFalse(WaitForAsyncUtils.asyncFx<Boolean> { dialog.isShowing }.get())
    }
}
