package dev.sluide.coloring.ui

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import javafx.beans.property.SimpleObjectProperty
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
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

/** The shared color text field: a TextField with a pseudo class needs the toolkit. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ColorFieldUiTest {
    private val color = SimpleObjectProperty(CssColor.fromRgb8(59, 130, 246))
    private lateinit var field: ColorField

    @Start
    fun start(stage: Stage) {
        field = ColorField(color)
        stage.scene = Scene(field.node, 300.0, 60.0)
        stage.show()
    }

    private fun invalid() = field.node.pseudoClassStates.any { it.pseudoClassName == "invalid" }

    @Test
    fun `an external change stays exact and is not written back rounded`(robot: FxRobot) {
        val exact = CssColor.srgb(0.123, 0.5, 0.7, 0.5)

        robot.interact { color.set(exact) }

        assertEquals(exact, color.value)
    }

    @Test
    fun `a typed hsl color keeps its space`(robot: FxRobot) {
        robot.interact { field.node.text = "hsl(200 50% 50%)" }

        assertEquals(ColorSpace.HSL, color.value.space)
        assertEquals("hsl(200 50% 50%)", field.node.text)
    }

    @Test
    fun `bad text is invalid and keeps the color, blank is not invalid`(robot: FxRobot) {
        val before = color.value

        robot.interact { field.node.text = "nonsense" }
        assertTrue(invalid())
        assertEquals(before, color.value)

        robot.interact { field.node.text = "" }
        assertFalse(invalid())
    }

    @Test
    fun `after dispose the color is no longer followed`(robot: FxRobot) {
        robot.interact { field.dispose() }

        robot.interact { color.set(CssColor.fromRgb8(1, 2, 3)) }

        assertEquals("#3b82f6", field.node.text)
    }

    @Test
    fun `building on a color that is not 8-bit leaves the property unchanged`(robot: FxRobot) {
        val exact = CssColor.srgb(0.123, 0.5, 0.7, 0.5)
        val property = SimpleObjectProperty(exact)

        robot.interact { ColorField(property) }

        assertEquals(exact, property.value)
    }

    @Test
    fun `escape restores the text of the color and clears invalid, enter selects all`(robot: FxRobot) {
        robot.interact { field.node.text = "#zzz" }
        assertTrue(invalid())

        robot.interact { press(KeyCode.ESCAPE) }

        assertEquals("#3b82f6", field.node.text)
        assertFalse(invalid())

        robot.interact { press(KeyCode.ENTER) }

        assertEquals("#3b82f6", field.node.selectedText)
    }

    private fun press(code: KeyCode) =
        Event.fireEvent(field.node, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
}
