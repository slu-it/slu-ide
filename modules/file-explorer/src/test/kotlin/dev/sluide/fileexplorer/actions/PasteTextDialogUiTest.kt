package dev.sluide.fileexplorer.actions

import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.fileicons.fileIconFor
import dev.sluide.texttypes.TextType
import dev.sluide.texttypes.TextTypes
import javafx.collections.FXCollections
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.ButtonType
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Shows the real Paste Text dialog without blocking. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PasteTextDialogUiTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var stage: Stage

    private val json = checkNotNull(TextTypes.byExtension("json"))
    private val yaml = checkNotNull(TextTypes.byExtension("yaml"))

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        stage.scene = Scene(StackPane(), 200.0, 100.0)
        stage.show()
    }

    private fun show(
        robot: FxRobot,
        detected: TextType = json,
    ): PasteTextDialog {
        lateinit var dialog: PasteTextDialog
        robot.interact {
            dialog = PasteTextDialog(stage, "pasted-2026-03-04-050607", detected, folder)
            dialog.show()
        }
        return dialog
    }

    private fun ok(dialog: PasteTextDialog) = dialog.dialogPane.lookupButton(ButtonType.OK)

    private fun key(
        target: javafx.scene.Node,
        code: KeyCode,
    ) = Event.fireEvent(target, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))

    @Test
    fun `the name is prefilled and selected with the focus, and the detected type is preselected`(robot: FxRobot) {
        val dialog = show(robot, yaml)

        assertEquals("Paste Text", dialog.title)
        assertSame(stage, dialog.owner)
        assertNull(dialog.headerText)
        assertEquals(30, dialog.field.prefColumnCount)
        assertTrue(FILE_ICONS_CSS in dialog.dialogPane.stylesheets, "the icons get their colours")
        assertEquals(dialog.field.width, dialog.combo.width, "the combo is as wide as the field")
        assertEquals(
            Region.USE_PREF_SIZE,
            (dialog.dialogPane.content as Region).minHeight,
            "the buttons are not cut off",
        )
        assertEquals(Region.USE_PREF_SIZE, dialog.dialogPane.minHeight, "the dialog pane keeps its content height")
        assertEquals("pasted-2026-03-04-050607", dialog.field.text)
        assertEquals("pasted-2026-03-04-050607", dialog.field.selectedText)
        assertSame(dialog.field, dialog.dialogPane.scene.focusOwner)
        assertEquals(TextTypes.all, dialog.combo.items)
        assertEquals(yaml, dialog.combo.value)
        assertEquals("Saved as “pasted-2026-03-04-050607.yaml”.", dialog.note.text)
        assertFalse(ok(dialog).isDisable)
    }

    @Test
    fun `every type shows its file icon and its label, the detected one marked, No extension last without extension`(
        robot: FxRobot,
    ) {
        val dialog = show(robot)

        // All items in a list tall enough to render every cell, made by the combo's own cell factory.
        lateinit var cells: List<ListCell<TextType>>
        robot.interact {
            val list =
                ListView(FXCollections.observableArrayList(dialog.combo.items)).apply {
                    cellFactory =
                        dialog.combo.cellFactory
                }
            stage.scene = Scene(list, 400.0, 1200.0)
            list.layout()
            cells = list.lookupAll(".list-cell").filterIsInstance<ListCell<TextType>>().filter { it.item != null }
        }
        val rows = cells.map { it.item to (it.text to it.graphic) }
        lateinit var emptied: List<ListCell<TextType>>
        robot.interact {
            val list = stage.scene.root as ListView<TextType>
            list.items.clear()
            list.layout()
            emptied = cells
        }
        assertTrue(emptied.isNotEmpty())
        emptied.forEach {
            assertNull(it.text, "an emptied cell shows no text")
            assertNull(it.graphic, "an emptied cell shows no icon")
        }

        assertEquals(26, rows.size)
        rows.forEach { (type, row) ->
            assertEquals(textTypeLabel(type, json), row.first)
            val icon = row.second as FontIcon
            assertEquals(16, icon.iconSize.toInt())
            val expected = fileIconFor("x.${type.extension ?: ""}", directory = false, expanded = false) { true }
            assertEquals(expected.ikon, icon.iconCode, "icon of ${type.name}")
        }
        assertEquals("JSON (.json) · detected", rows.first { it.first == json }.second.first)
        assertEquals("No extension", rows.last().second.first)
        // The closed combo shows the selection in the same way.
        assertEquals("JSON (.json) · detected", dialog.combo.buttonCell.text)
    }

    @Test
    fun `changing the type updates the note and validates the name again`(robot: FxRobot) {
        folder.resolve("a.json").writeText("x")
        val dialog = show(robot)
        robot.interact { dialog.field.text = "a" }
        assertTrue(ok(dialog).isDisable, "a.json is taken")
        assertTrue(checkNotNull(dialog.problem.text).contains("already exists"))
        assertNull(dialog.note.text, "no note beside a problem")

        robot.interact { dialog.combo.value = yaml }

        assertFalse(ok(dialog).isDisable)
        assertNull(dialog.problem.text)
        assertEquals("Saved as “a.yaml”.", dialog.note.text)

        robot.interact { dialog.combo.value = TextTypes.NO_EXTENSION }
        assertEquals("Saved as “a”.", dialog.note.text)
    }

    @Test
    fun `OK is disabled for a blank name and a name ending with a slash`(robot: FxRobot) {
        val dialog = show(robot)

        robot.interact { dialog.field.text = " " }
        assertTrue(ok(dialog).isDisable)
        assertNull(dialog.note.text)
        robot.interact { dialog.field.text = "dir/" }
        assertTrue(ok(dialog).isDisable)
        assertEquals("The name must not end with /.", dialog.problem.text)
        robot.interact { dialog.field.text = "new/sub/x" }
        assertFalse(ok(dialog).isDisable)
    }

    @Test
    fun `Enter in the name field is OK and gives the final name`(robot: FxRobot) {
        val dialog = show(robot)
        robot.interact { dialog.field.text = "config.yaml" }

        robot.interact { key(dialog.field, KeyCode.ENTER) }

        assertEquals("config.yaml.json", dialog.result)
        assertFalse(dialog.isShowing)
    }

    @Test
    fun `Enter does nothing while the name has a problem`(robot: FxRobot) {
        val dialog = show(robot)
        robot.interact { dialog.field.text = "" }

        robot.interact { key(dialog.field, KeyCode.ENTER) }

        assertTrue(dialog.isShowing)
        assertNull(dialog.result)
    }

    @Test
    fun `Escape closes the dialog without a name`(robot: FxRobot) {
        val dialog = show(robot)

        robot.interact { key(dialog.field, KeyCode.ESCAPE) }

        assertFalse(dialog.isShowing)
        assertNull(dialog.result)
    }
}
