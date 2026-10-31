package dev.sluide.ide.conversions

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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Shows the real dialog without blocking. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class OverwriteConfirmationUiTest {
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `Cancel is the default button and has the focus, so Enter replaces or converts nothing`(
        overwrite: Boolean,
        robot: FxRobot,
    ) {
        val file = Path.of("a.yaml")
        val confirm = if (overwrite) REPLACE_BUTTON else CONVERT_SAVED_BUTTON
        val question = if (overwrite) overwriteQuestion(file) else convertSavedQuestion(file)
        lateinit var dialog: Alert
        robot.interact {
            dialog = if (overwrite) overwriteConfirmation(stage, file) else confirmation(stage, question, confirm)
            dialog.show()
        }

        // Read on the FX thread: the dialog focuses its button there.
        val state =
            WaitForAsyncUtils
                .asyncFx<List<Any?>> {
                    val cancel = dialog.dialogPane.lookupButton(ButtonType.CANCEL) as Button
                    val other = dialog.dialogPane.lookupButton(confirm) as Button
                    listOf(
                        dialog.contentText,
                        cancel.isDefaultButton,
                        other.isDefaultButton,
                        cancel === dialog.dialogPane.scene.focusOwner,
                    )
                }.get()
        assertEquals(listOf(question, true, false, true), state)

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
