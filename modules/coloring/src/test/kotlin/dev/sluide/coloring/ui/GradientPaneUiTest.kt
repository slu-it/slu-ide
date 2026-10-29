package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.Gradient
import dev.sluide.coloring.analysis.GradientStop
import dev.sluide.coloring.analysis.GradientType
import dev.sluide.coloring.analysis.MixSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.ui.gradient.GradientPreview
import javafx.event.Event
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.input.Clipboard
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
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

/**
 * The wiring of the gradient pane's controls to the gradient model (the model itself is in GradientsTest). Needs a
 * scene for the controls, the toggle groups and the clipboard.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GradientPaneUiTest {
    private val state = ColoringState()
    private lateinit var pane: GradientPane
    private lateinit var root: Parent

    @Start
    fun start(stage: Stage) {
        pane = GradientPane(state, conicThrottleMillis = 0.0)
        root = pane.node as Parent
        stage.scene = Scene(root, 700.0, 600.0)
        stage.show()
    }

    private fun <T> find(id: String): T {
        @Suppress("UNCHECKED_CAST")
        return checkNotNull(root.lookup("#$id")) { "no node #$id" } as T
    }

    private fun css() = find<TextField>("gradient-css").text

    @Test
    fun `starts with the current color to the second color, linear 90deg in oklch`() {
        assertEquals("linear-gradient(in oklch 90deg, #3b82f6 0%, #ffffff 100%)", css())
    }

    @Test
    fun `changing the type updates the css`(robot: FxRobot) {
        robot.interact { find<ToggleButton>("gradient-conic").fire() }
        assertEquals("conic-gradient(from 90deg in oklch, #3b82f6 0%, #ffffff 100%)", css())
        robot.interact { find<ToggleButton>("gradient-radial").fire() }
        assertEquals("radial-gradient(circle in oklch, #3b82f6 0%, #ffffff 100%)", css())
    }

    @Test
    fun `typing in the stop field updates the css`(robot: FxRobot) {
        val field = find<TextField>("gradient-stop-text")
        robot.interact { field.text = "red" }
        assertEquals("linear-gradient(in oklch 90deg, #ff0000 0%, #ffffff 100%)", css())
    }

    @Test
    fun `the stop field follows the selected stop and edits only that one`(robot: FxRobot) {
        val field = find<TextField>("gradient-stop-text")
        robot.interact {
            pane.stopsBar.handle(1).fireEvent(
                MouseEvent(
                    MouseEvent.MOUSE_PRESSED,
                    5.0,
                    5.0,
                    5.0,
                    5.0,
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
        assertEquals("#ffffff", field.text)

        robot.interact { field.text = "red" }

        assertEquals("linear-gradient(in oklch 90deg, #3b82f6 0%, #ff0000 100%)", css())
    }

    @Test
    fun `copy puts the css on the system clipboard`(robot: FxRobot) {
        robot.interact { find<Button>("gradient-copy").fire() }
        robot.interact { assertEquals(css(), Clipboard.getSystemClipboard().string) }
    }

    @Test
    fun `after dispose a change of the current color has no effect`(robot: FxRobot) {
        val setCurrent = find<Button>("gradient-set-current")
        robot.interact { state.current.set(CssColor.fromRgb8(1, 2, 3)) }
        assertFalse(setCurrent.isDisable, "enabled while the current color differs from the stop")

        robot.interact {
            pane.dispose()
            state.current.set(CssColor.fromRgb8(0x3b, 0x82, 0xf6))
        }
        // The button would be disabled again (the stop already has that color) if the pane still listened.
        assertFalse(setCurrent.isDisable)
    }

    private fun drag(
        handle: javafx.scene.Node,
        sceneX: Double,
    ) {
        fun fire(type: javafx.event.EventType<MouseEvent>) =
            Event.fireEvent(
                handle,
                MouseEvent(
                    type,
                    sceneX,
                    5.0,
                    sceneX,
                    5.0,
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
        fire(MouseEvent.MOUSE_PRESSED)
        fire(MouseEvent.MOUSE_DRAGGED)
    }

    @Test
    fun `dragging a handle moves its stop and a stop dragged past the other one swaps places`(robot: FxRobot) {
        val bar = pane.stopsBar
        robot.interact {
            val mid = bar.localToScene(bar.width / 2, 0.0).x
            drag(bar.handle(0), mid)
        }
        assertEquals("linear-gradient(in oklch 90deg, #3b82f6 50%, #ffffff 100%)", css())

        robot.interact { drag(bar.handle(0), bar.localToScene(bar.width + 40, 0.0).x) }
        // Blue went past white to 100 % and is still the selected stop, so it can be dragged back out.
        assertEquals("linear-gradient(in oklch 90deg, #ffffff 100%, #3b82f6 100%)", css())
        assertEquals("#3b82f6", find<TextField>("gradient-stop-text").text)
        robot.interact { drag(bar.handle(1), bar.localToScene(bar.width / 2, 0.0).x) }
        assertEquals("linear-gradient(in oklch 90deg, #3b82f6 50%, #ffffff 100%)", css())
    }

    @Test
    fun `add and remove stop change the stops and removing stops to two is not possible`(robot: FxRobot) {
        val remove = find<Button>("gradient-remove")
        assertTrue(remove.isDisable)

        robot.interact { find<Button>("gradient-add").fire() }
        assertEquals(3, css().split("%").size - 1)
        assertFalse(remove.isDisable)

        robot.interact { remove.fire() }
        assertEquals(2, css().split("%").size - 1)
        assertTrue(remove.isDisable)
    }

    @Test
    fun `an interpolation toggle changes the space and srgb leaves it out`(robot: FxRobot) {
        robot.interact { find<ToggleButton>("gradient-oklab").fire() }
        assertEquals("linear-gradient(in oklab 90deg, #3b82f6 0%, #ffffff 100%)", css())
        robot.interact { find<ToggleButton>("gradient-srgb").fire() }
        assertEquals("linear-gradient(90deg, #3b82f6 0%, #ffffff 100%)", css())
    }

    @Test
    fun `the angle field sets the angle and marks bad input`(robot: FxRobot) {
        val field = find<TextField>("gradient-angle")
        robot.interact { field.text = "45" }
        assertEquals("linear-gradient(in oklch 45deg, #3b82f6 0%, #ffffff 100%)", css())

        robot.interact { field.text = "400" }
        assertEquals("linear-gradient(in oklch 45deg, #3b82f6 0%, #ffffff 100%)", css())
        assertTrue(field.pseudoClassStates.any { it.pseudoClassName == "invalid" })
    }

    @Test
    fun `use stop as current and set to current color go through the state`(robot: FxRobot) {
        robot.interact { find<Button>("gradient-use").fire() }
        assertEquals(CssColor.fromRgb8(0x3b, 0x82, 0xf6), state.current.get())

        robot.interact { state.current.set(CssColor.fromRgb8(255, 0, 0)) }
        robot.interact { find<Button>("gradient-set-current").fire() }
        assertEquals("linear-gradient(in oklch 90deg, #ff0000 0%, #ffffff 100%)", css())
    }

    @Test
    fun `a burst of conic changes is drawn at most twice, the last one included`(robot: FxRobot) {
        val preview = GradientPreview(conicThrottleMillis = 200.0)
        val conic =
            Gradient(
                GradientType.CONIC,
                0.0,
                listOf(
                    GradientStop(CssColor.fromRgb8(0, 0, 255), 0.0),
                    GradientStop(CssColor.fromRgb8(255, 255, 0), 1.0),
                ),
                MixSpace.OKLCH,
            )
        robot.interact {
            val stage = Stage()
            stage.scene = Scene(StackPane(preview), 300.0, 300.0)
            stage.show()
        }
        val before = preview.conicRenderings
        robot.interact { for (angle in 1..20) preview.gradient = conic.copy(angleDeg = angle.toDouble()) }
        val deadline = System.nanoTime() + 5_000_000_000L
        while (preview.renderedGradient?.angleDeg != 20.0 && System.nanoTime() < deadline) {
            Thread.sleep(20) // polling for the trailing redraw with a deadline
        }
        assertEquals(20.0, preview.renderedGradient?.angleDeg)
        assertTrue(preview.conicRenderings - before <= 2, "drawn ${preview.conicRenderings - before} times")
    }
}
