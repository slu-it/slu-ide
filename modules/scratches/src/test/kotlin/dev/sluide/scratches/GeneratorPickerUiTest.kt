package dev.sluide.scratches

import dev.sluide.scratches.picker.PickerRow
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Scene
import javafx.scene.control.Label
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
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UI tests of the generated scratch file picker: what needs a scene, the row logic is in [PickerRowsTest]. Needs a
 * display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GeneratorPickerUiTest {
    private lateinit var stage: Stage
    private lateinit var picker: GeneratorPicker
    private var picked: ScratchGenerator? = null

    private val alpha = ScratchGenerator(GeneratorGroup.UUIDS, "Alpha", "alpha") { "a\n" }
    private val beta = ScratchGenerator(GeneratorGroup.UUIDS, "Beta", "beta") { "b\n" }
    private val gamma = ScratchGenerator(GeneratorGroup.IDS, "Gamma", "gamma", "csv") { "g\n" }
    private val delta = ScratchGenerator(GeneratorGroup.IDS, "Delta", "delta") { "d\n" }

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        picker = GeneratorPicker(listOf(alpha, beta, gamma, delta))
        stage.scene = Scene(StackPane(), 600.0, 400.0)
        stage.show()
    }

    @AfterEach
    fun close(robot: FxRobot) {
        robot.interact { picker.hide() }
    }

    private fun open(
        robot: FxRobot,
        preselected: ScratchGenerator? = null,
    ) {
        robot.interact { picker.show(stage, preselected) { picked = it } }
    }

    /** The rows as texts, headers in brackets. */
    private fun rows() =
        picker.list.items.map {
            when (it) {
                is PickerRow.Header -> "[${it.title}]"
                is PickerRow.Entry -> it.item.name
            }
        }

    private fun key(
        robot: FxRobot,
        code: KeyCode,
    ) {
        robot.interact {
            Event.fireEvent(picker.field, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
        }
    }

    /** A primary-button event of [type] at the centre of [cell]: x and y are scene coordinates, so the cell has it. */
    private fun mouse(
        type: EventType<MouseEvent>,
        cell: ListCell<*>,
    ): MouseEvent {
        val scene = cell.localToScene(cell.width / 2, cell.height / 2)
        val screen = cell.localToScreen(cell.width / 2, cell.height / 2)
        return MouseEvent(
            type,
            scene.x,
            scene.y,
            screen.x,
            screen.y,
            MouseButton.PRIMARY,
            1,
            false,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            false,
            true,
            null,
        )
    }

    @Test
    fun `headers and entries render, the first entry is selected without a preselection`(robot: FxRobot) {
        open(robot)
        assertTrue(picker.field.isFocused)
        assertEquals(listOf("[UUIDs]", "Alpha", "Beta", "[IDs]", "Gamma", "Delta"), rows())
        assertEquals(alpha, picker.selected)
        val headers = robot.lookup(".scratch-picker-header").queryAllAs(Label::class.java).map { it.text }
        assertEquals(listOf("UUIDs", "IDs"), headers)
        val fileNames = robot.lookup(".scratch-picker-extension").queryAllAs(Label::class.java).map { it.text }
        assertTrue("gamma.csv" in fileNames, "file names: $fileNames")
    }

    @Test
    fun `the preselected generator is selected, Down and Up skip the headers`(robot: FxRobot) {
        open(robot, beta)
        assertEquals(beta, picker.selected)
        key(robot, KeyCode.DOWN)
        assertEquals(gamma, picker.selected)
        assertEquals(4, picker.list.selectionModel.selectedIndex)
        key(robot, KeyCode.UP)
        assertEquals(beta, picker.selected)
        key(robot, KeyCode.UP)
        key(robot, KeyCode.UP)
        assertEquals(alpha, picker.selected, "stops at the first entry, below its header")
    }

    @Test
    fun `a header can be neither selected nor picked by the mouse`(robot: FxRobot) {
        open(robot, delta)
        val cells = robot.lookup(".list-cell").queryAllAs(ListCell::class.java)
        val header = cells.first { it.item == PickerRow.Header("IDs") }
        val clicks = listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED)
        robot.interact { clicks.forEach { Event.fireEvent(header, mouse(it, header)) } }
        assertEquals(delta, picker.selected)
        assertNull(picked)
        assertTrue(picker.isShowing)

        val entry = cells.first { it.item == PickerRow.Entry(gamma) }
        robot.interact { clicks.forEach { Event.fireEvent(entry, mouse(it, entry)) } }
        assertEquals(gamma, picked, "the same clicks pick an entry")
    }

    @Test
    fun `filtering hides the header of a group without matches and selects the first entry`(robot: FxRobot) {
        open(robot)
        robot.interact { picker.field.text = "GAM" }
        assertEquals(listOf("[IDs]", "Gamma"), rows())
        assertEquals(gamma, picker.selected)
        robot.interact { picker.field.text = "uuid" }
        assertEquals(listOf("[UUIDs]", "Alpha", "Beta"), rows())
        assertEquals(alpha, picker.selected)
    }
}
