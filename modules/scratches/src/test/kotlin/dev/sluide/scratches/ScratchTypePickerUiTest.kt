package dev.sluide.scratches

import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.ListCell
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.kordamp.ikonli.javafx.FontIcon
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** UI tests of the scratch type picker. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ScratchTypePickerUiTest {
    private lateinit var stage: Stage
    private lateinit var picker: ScratchTypePicker
    private var picked: ScratchType? = null

    private val markdown = SCRATCH_TYPES.first { it.extension == "md" }

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        picker = ScratchTypePicker()
        stage.scene = Scene(StackPane(), 600.0, 400.0)
        stage.show()
    }

    @AfterEach
    fun close(robot: FxRobot) {
        robot.interact { picker.hide() }
    }

    private fun open(
        robot: FxRobot,
        preselected: ScratchType? = markdown,
    ) {
        robot.interact { picker.show(stage, preselected) { picked = it } }
    }

    private fun names() = picker.list.items.map { it.name }

    private fun key(
        robot: FxRobot,
        code: KeyCode,
    ) {
        robot.interact {
            Event.fireEvent(
                picker.field,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false),
            )
        }
        WaitForAsyncUtils.waitForFxEvents()
    }

    @Test
    fun `shows all types with the field focused and the preselected type selected`(robot: FxRobot) {
        open(robot)
        assertTrue(picker.isShowing)
        assertTrue(picker.field.isFocused)
        assertEquals(SCRATCH_TYPES.size, picker.list.items.size)
        assertEquals(markdown, picker.list.selectionModel.selectedItem)
        val icons = robot.lookup(".ikonli-font-icon").queryAllAs(FontIcon::class.java)
        assertTrue(icons.isNotEmpty())
        assertTrue(icons.all { it.iconSize == 16 }, "icon sizes ${icons.map { it.iconSize }}")
    }

    @Test
    fun `selects the first type without a preselection`(robot: FxRobot) {
        open(robot, null)
        assertEquals(SCRATCH_TYPES.first(), picker.list.selectionModel.selectedItem)
    }

    @Test
    fun `typing filters the types and selects the first match`(robot: FxRobot) {
        open(robot)
        robot.interact { picker.field.text = "KOT" }
        assertEquals(listOf("Kotlin", "Kotlin Script"), names())
        assertEquals("Kotlin", picker.list.selectionModel.selectedItem.name)
    }

    @Test
    fun `no match shows the placeholder and Enter does nothing`(robot: FxRobot) {
        open(robot)
        robot.interact { picker.field.text = "zzz" }
        assertTrue(picker.list.items.isEmpty())
        key(robot, KeyCode.ENTER)
        assertNull(picked)
        assertTrue(picker.isShowing)
    }

    @Test
    fun `Down and Up move the selection`(robot: FxRobot) {
        open(robot)
        key(robot, KeyCode.DOWN)
        assertEquals(SCRATCH_TYPES.indexOf(markdown) + 1, picker.list.selectionModel.selectedIndex)
        key(robot, KeyCode.UP)
        key(robot, KeyCode.UP)
        assertEquals(SCRATCH_TYPES.indexOf(markdown) - 1, picker.list.selectionModel.selectedIndex)
        assertTrue(picker.field.isFocused)
    }

    @Test
    fun `Up and Down stop at the first and last rows`(robot: FxRobot) {
        open(robot, SCRATCH_TYPES.first())
        key(robot, KeyCode.UP)
        assertEquals(0, picker.list.selectionModel.selectedIndex)
        open(robot, SCRATCH_TYPES.last())
        key(robot, KeyCode.DOWN)
        assertEquals(SCRATCH_TYPES.lastIndex, picker.list.selectionModel.selectedIndex)
    }

    @Test
    fun `Enter picks the selected type and hides the popup`(robot: FxRobot) {
        open(robot)
        key(robot, KeyCode.ENTER)
        assertEquals(markdown, picked)
        assertFalse(picker.isShowing)
    }

    @Test
    fun `a click on a row picks it`(robot: FxRobot) {
        open(robot)
        val cell =
            robot
                .lookup(".list-cell")
                .queryAllAs(ListCell::class.java)
                .first { (it.item as? ScratchType)?.extension == "kt" }
        robot.interact {
            Event.fireEvent(
                cell,
                MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
                    1.0,
                    1.0,
                    1.0,
                    1.0,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    null,
                ),
            )
        }
        assertEquals("kt", picked?.extension)
        assertFalse(picker.isShowing)
    }

    @Test
    fun `Escape hides without a pick`(robot: FxRobot) {
        open(robot)
        robot.interact {
            Event.fireEvent(
                picker.field,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false),
            )
        }
        assertFalse(picker.isShowing)
        assertNull(picked)
    }
}
