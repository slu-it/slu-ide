package dev.sluide.fileexplorer.actions

import javafx.scene.Scene
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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Shows the real dialog without blocking. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class NameDialogUiTest {
    private lateinit var stage: Stage

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    private fun validate(name: String) = if (name.isEmpty() || name == "taken.txt") "Not $name" else null

    private fun show(
        robot: FxRobot,
        title: String,
        initial: String,
        note: ((String) -> String?)? = null,
    ): NameDialog {
        lateinit var dialog: NameDialog
        robot.interact {
            dialog = NameDialog(stage, title, initial, ::validate, note)
            dialog.show()
        }
        return dialog
    }

    @Test
    fun `OK is disabled and the problem shown while the name is invalid`(robot: FxRobot) {
        val dialog = show(robot, "New File", "")
        val ok = dialog.dialogPane.lookupButton(ButtonType.OK)

        assertEquals("New File", dialog.title)
        assertSame(dialog.field, dialog.dialogPane.scene.focusOwner)
        assertTrue(ok.isDisable)
        assertEquals("Not ", dialog.problem.text)

        robot.interact { dialog.field.text = "taken.txt" }
        assertTrue(ok.isDisable)
        assertEquals("Not taken.txt", dialog.problem.text)

        robot.interact { dialog.field.text = "free.txt" }
        assertFalse(ok.isDisable)
        assertNull(dialog.problem.text)

        robot.interact { dialog.result = dialog.resultConverter.call(ButtonType.OK) }
        assertEquals("free.txt", dialog.result)
    }

    @Test
    fun `the name field has the focus with the base name selected, Cancel gives no name`(robot: FxRobot) {
        val dialog = show(robot, "Rename", "Main.kt")

        // Also while OK, the default button, is enabled.
        assertFalse(dialog.dialogPane.lookupButton(ButtonType.OK).isDisable)
        assertSame(dialog.field, dialog.dialogPane.scene.focusOwner)
        assertEquals("Main", dialog.field.selectedText)
        robot.interact { dialog.result = dialog.resultConverter.call(ButtonType.CANCEL) }
        assertNull(dialog.result)
    }

    @Test
    fun `the note shows below the field for a valid name and follows the typing`(robot: FxRobot) {
        val dialog = show(robot, "Paste Image", "a.png") { if (it.endsWith(".png")) null else "Saved as “$it.png”." }

        assertNull(dialog.note.text)
        assertTrue(dialog.note.isManaged, "its line is kept while empty, as the shown dialog does not grow")
        robot.interact { dialog.field.text = "diagram" }
        assertEquals("Saved as “diagram.png”.", dialog.note.text)
        robot.interact { dialog.field.text = "taken.txt" }
        assertNull(dialog.note.text, "no note beside a problem")
        assertEquals("Not taken.txt", dialog.problem.text)
    }

    @Test
    fun `a dialog without notes has no line for them`(robot: FxRobot) {
        val dialog = show(robot, "New File", "a.txt")

        assertFalse(dialog.note.isManaged)
    }
}
