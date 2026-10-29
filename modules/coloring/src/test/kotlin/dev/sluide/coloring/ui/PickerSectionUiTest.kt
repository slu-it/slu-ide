package dev.sluide.coloring.ui

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wiring of the picker's views to its model and the state: mouse events, the mode toggle, dispose. Needs a display,
 * as the mouse events and the output scale come with a scene and a window.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PickerSectionUiTest {
    private val state = ColoringState()
    private lateinit var picker: PickerSection

    @Start
    fun start(stage: Stage) {
        picker = PickerSection(state)
        stage.scene = Scene(picker.node as Parent, 320.0, 400.0)
        stage.show()
    }

    @Test
    fun `pressing the square sets saturation and value of the current color`(robot: FxRobot) {
        robot.interact { press(picker.square.area, 110.0, 90.0) }

        robot.interact {
            val c = state.current.get()
            assertEquals(ColorSpace.HSV, c.space)
            assertEquals(50.0, c.c1, 1e-6)
            assertEquals(50.0, c.c2, 1e-6)
        }
    }

    @Test
    fun `only the primary button sets the color`(robot: FxRobot) {
        val before = state.current.get()

        robot.interact { press(picker.square.area, 110.0, 90.0, MouseButton.SECONDARY) }

        assertEquals(before, state.current.get())
    }

    @Test
    fun `pressing the hue strip sets the hue`(robot: FxRobot) {
        robot.interact { press(picker.square.strip, 10.0, 90.0) }

        assertEquals(180.0, state.current.get().c0, 1e-6)
    }

    @Test
    fun `pressing the alpha slider sets the alpha`(robot: FxRobot) {
        robot.interact { press(picker.squareAlpha.node, 124.0, 8.0) }

        assertEquals(0.5, state.current.get().alpha, 1e-6)
    }

    @Test
    fun `the wheel replaces the square and back`(robot: FxRobot) {
        robot.interact {
            assertNotNull(picker.square.node.scene)
            assertNull(picker.wheel.node.scene)
            assertEquals(0, picker.wheel.imagePixels)
        }

        robot.interact { picker.modeButtons[1].fire() }
        robot.interact {
            assertNotNull(picker.wheel.node.scene)
            assertNull(picker.square.node.scene)
            assertTrue(picker.wheel.imagePixels > 0)
        }

        robot.interact { picker.modeButtons[0].fire() }
        robot.interact { assertNotNull(picker.square.node.scene) }
    }

    @Test
    fun `pressing the wheel sets hue and saturation`(robot: FxRobot) {
        robot.interact { picker.modeButtons[1].fire() }
        robot.interact { press(picker.wheel.area, 100.0, 0.0) }

        robot.interact {
            val c = state.current.get()
            assertEquals(90.0, c.c0, 1e-6)
            assertEquals(100.0, c.c1, 1e-6)
        }
    }

    @Test
    fun `pressing the value slider of the wheel sets the value`(robot: FxRobot) {
        robot.interact { picker.modeButtons[1].fire() }
        robot.interact { press(picker.wheel.slider, 10.0, 50.0) }

        assertEquals(75.0, state.current.get().c2, 1e-6)
    }

    @Test
    fun `the wheel mode has its own alpha slider that sets the alpha`(robot: FxRobot) {
        robot.interact { picker.modeButtons[1].fire() }
        robot.interact { press(picker.wheelAlpha.node, 114.0, 8.0) }

        assertEquals(0.5, state.current.get().alpha, 1e-6)
    }

    @Test
    fun `a color set from outside moves the marker and the alpha thumb`(robot: FxRobot) {
        robot.interact { state.current.set(CssColor.srgb(0.0, 1.0, 0.0, 0.25)) }

        robot.interact {
            assertEquals(220.0, picker.square.markerPosition.x, 1e-6)
            assertEquals(0.0, picker.square.markerPosition.y, 1e-6)
            assertEquals(62.0, picker.squareAlpha.thumbX, 1e-6)
        }
    }

    @Test
    fun `a disposed picker no longer follows the color`(robot: FxRobot) {
        robot.interact { picker.dispose() }
        robot.interact { state.current.set(CssColor.srgb(0.0, 1.0, 0.0)) }
        robot.interact { press(picker.square.area, 0.0, 0.0) }

        // The hue stayed the one of the blue it started with, so the press emitted blue's hue, not green's.
        robot.interact { assertEquals(217.0, state.current.get().c0, 1.0) }
    }

    private fun press(
        node: Node,
        x: Double,
        y: Double,
        button: MouseButton = MouseButton.PRIMARY,
    ) {
        // Firing recomputes the local position from the scene and screen coordinates, so they must be right.
        val scene = node.localToScene(x, y)
        val screen = node.localToScreen(x, y)
        Event.fireEvent(
            node,
            MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                scene.x,
                scene.y,
                screen.x,
                screen.y,
                button,
                1,
                false,
                false,
                false,
                false,
                button == MouseButton.PRIMARY,
                false,
                button == MouseButton.SECONDARY,
                true,
                false,
                false,
                null,
            ),
        )
    }
}
