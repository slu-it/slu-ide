package dev.sluide.ide.help

import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.input.Clipboard
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
import kotlin.test.assertTrue

/** UI test of the About dialog's buttons: Copy needs a real dialog pane and the clipboard. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class AboutDialogUiTest {
    private lateinit var owner: Stage

    @Start
    fun start(stage: Stage) {
        owner = stage
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    @Test
    fun `Copy puts the lines on the clipboard and keeps the dialog open, Close closes it`(robot: FxRobot) {
        val lines = listOf("Version" to "1.0", "Java" to "Acme 25")
        lateinit var dialog: Dialog<ButtonType>
        robot.interact {
            dialog = aboutDialog(owner, lines)
            dialog.show()
        }
        val pane = dialog.dialogPane
        robot.interact { (pane.lookupButton(pane.buttonTypes.first { it.text == "Copy" }) as Button).fire() }
        var copied: String? = null
        robot.interact { copied = Clipboard.getSystemClipboard().string }
        assertEquals(aboutText(lines), copied)
        assertTrue(dialog.isShowing)

        robot.interact { (pane.lookupButton(ButtonType.CLOSE) as Button).fire() }
        assertFalse(dialog.isShowing)
    }
}
