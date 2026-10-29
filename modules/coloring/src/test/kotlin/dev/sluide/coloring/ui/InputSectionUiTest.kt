package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.NamedColors
import dev.sluide.coloring.color.nearestNamed
import dev.sluide.coloring.color.toRgb8
import javafx.css.PseudoClass
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.layout.VBox
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

/** Pseudo-classes, key and mouse routing need a scene. Needs a display. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class InputSectionUiTest {
    private val state = ColoringState()
    private lateinit var input: InputSection
    private val invalid = PseudoClass.getPseudoClass("invalid")

    @Start
    fun start(stage: Stage) {
        input = InputSection(state)
        state.current.set(CssColor.fromRgb8(59, 130, 246))
        stage.scene = Scene(VBox(input.node), 340.0, 300.0)
        stage.show()
    }

    private fun field(robot: FxRobot) = robot.lookup(".coloring-input").queryAs(TextField::class.java)

    @Test
    fun `typing an invalid text marks the field and keeps the color`(robot: FxRobot) {
        val before = state.current.get()

        robot.interact { field(robot).text = "#zzz" }

        assertTrue(field(robot).pseudoClassStates.contains(invalid))
        assertEquals(before, state.current.get())
    }

    @Test
    fun `typing a valid text sets the current color and keeps the typed notation`(robot: FxRobot) {
        robot.interact { field(robot).text = "rgb(255 0 0)" }

        assertEquals(CssColor.fromRgb8(255, 0, 0), state.current.get())
        assertEquals("rgb(255 0 0)", field(robot).text)
        assertFalse(field(robot).pseudoClassStates.contains(invalid))
    }

    @Test
    fun `an external change updates the field`(robot: FxRobot) {
        robot.interact { state.current.set(CssColor.fromRgb8(0, 255, 0)) }

        assertEquals("#00ff00", field(robot).text)
    }

    @Test
    fun `the text written for an external change does not replace the color`(robot: FxRobot) {
        val gray = CssColor.srgb(0.5, 0.5, 0.5)

        robot.interact { state.current.set(gray) }

        // The field now reads #808080, which parses to a slightly different gray: it must not be read back.
        assertEquals("#808080", field(robot).text)
        assertEquals(gray, state.current.get())
    }

    @Test
    fun `escape restores the text of the current color and clears the invalid mark`(robot: FxRobot) {
        robot.interact { field(robot).text = "#zzz" }
        assertTrue(field(robot).pseudoClassStates.contains(invalid))

        robot.interact {
            Event.fireEvent(
                field(robot),
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false),
            )
        }

        assertEquals("#3b82f6", field(robot).text)
        assertFalse(field(robot).pseudoClassStates.contains(invalid))
    }

    @Test
    fun `a click on the nearest name makes the named color current and keeps the alpha`(robot: FxRobot) {
        val translucent = CssColor.fromRgb8(59, 130, 246, 128)
        robot.interact { state.current.set(translucent) }
        val name = nearestNamed(translucent).name
        val label = robot.lookup(".coloring-name").queryAs(Label::class.java)
        assertEquals("Nearest name: $name", label.text)

        // A real click: the handler tests the mouse button, which only a mouse event carries.
        robot.clickOn(label, MouseButton.PRIMARY)

        val rgb = state.current.get().toRgb8()
        assertEquals(128, rgb.a)
        assertEquals(name, NamedColors.nameOf(rgb.copy(a = 255)))
    }

    @Test
    fun `after dispose an external change no longer updates the field`(robot: FxRobot) {
        robot.interact {
            input.dispose()
            state.current.set(CssColor.fromRgb8(0, 255, 0))
        }

        assertEquals("#3b82f6", field(robot).text)
    }
}
