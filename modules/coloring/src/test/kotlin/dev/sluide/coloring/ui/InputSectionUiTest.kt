package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals

/** The field's and the header's wiring to the slot being edited need a scene. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class InputSectionUiTest {
    private val state = ColoringState()
    private lateinit var input: InputSection

    @Start
    fun start(stage: Stage) {
        input = InputSection(state)
        state.editingColor.set(CssColor.fromRgb8(59, 130, 246))
        stage.scene = Scene(VBox(input.node), 340.0, 300.0)
        stage.show()
    }

    private fun field(robot: FxRobot) = robot.lookup(".coloring-input").queryAs(TextField::class.java)

    @Test
    fun `typing a valid text sets the slot being edited and keeps the typed notation`(robot: FxRobot) {
        robot.interact { field(robot).text = "rgb(255 0 0)" }

        assertEquals(CssColor.fromRgb8(255, 0, 0), state.contrastText.color.get())
        assertEquals("rgb(255 0 0)", field(robot).text)
    }

    @Test
    fun `the header and the field follow a switch of the slot`(robot: FxRobot) {
        robot.interact { state.edit(state.mix2) }

        assertEquals("Editing: Mix › Color 2", robot.lookup("#coloring-editing").queryAs(Label::class.java).text)
        assertEquals("#ef4444", field(robot).text)
    }

    @Test
    fun `an external change updates the field`(robot: FxRobot) {
        robot.interact { state.contrastText.color.set(CssColor.fromRgb8(0, 255, 0)) }

        assertEquals("#00ff00", field(robot).text)
    }

    @Test
    fun `after dispose an external change no longer updates the field`(robot: FxRobot) {
        robot.interact {
            input.dispose()
            state.editingColor.set(CssColor.fromRgb8(0, 255, 0))
        }

        assertEquals("#3b82f6", field(robot).text)
    }
}
