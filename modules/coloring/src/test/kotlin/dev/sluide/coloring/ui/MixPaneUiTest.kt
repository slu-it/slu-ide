package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.MixSpace
import dev.sluide.coloring.color.ColorFormatter
import dev.sluide.coloring.color.CssColor
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals

/** The wiring of the Mix pane to the window's state; needs controls in a scene. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MixPaneUiTest {
    private val state = ColoringState()
    private lateinit var pane: MixPane

    @Start
    fun start(stage: Stage) {
        pane = MixPane(state)
        stage.scene = Scene(pane.node as Parent, 800.0, 500.0)
        stage.show()
    }

    @Test
    fun `moving the slider updates the color-mix text`(robot: FxRobot) {
        assertEquals("color-mix(in oklch, #3b82f6 50%, #ffffff 50%)", pane.mixCssText)

        robot.interact { pane.amount.value = 25.0 }

        assertEquals("color-mix(in oklch, #3b82f6 75%, #ffffff 25%)", pane.mixCssText)
    }

    @Test
    fun `choosing a space changes the color-mix text`(robot: FxRobot) {
        robot.interact { pane.space.value = MixSpace.SRGB }

        assertEquals("color-mix(in srgb, #3b82f6 50%, #ffffff 50%)", pane.mixCssText)
    }

    @Test
    fun `typing a second color changes the mix`(robot: FxRobot) {
        robot.interact { pane.secondText.text = "#000000" }

        assertEquals(CssColor.fromRgb8(0, 0, 0), state.second.value)
        assertEquals("color-mix(in oklch, #3b82f6 50%, #000000 50%)", pane.mixCssText)
    }

    @Test
    fun `the opacity and the background change the flattened color`(robot: FxRobot) {
        robot.interact { state.current.set(CssColor.fromRgb8(0, 0, 0)) }
        robot.interact { pane.opacitySlider.value = 50.0 }
        assertEquals("#808080", pane.flattenedCssText)

        robot.interact { pane.backgroundText.text = "#000000" }

        assertEquals("#000000", pane.flattenedCssText)
    }

    @Test
    fun `clicking a strip swatch makes it the current color`(robot: FxRobot) {
        val swatch = pane.stripSwatches[0]
        val tip = swatch.tooltipText

        robot.interact { clickPrimary(swatch) }

        assertEquals(tip, ColorFormatter.hex(state.current.get()))
    }

    @Test
    fun `after dispose a state change no longer updates the mix`(robot: FxRobot) {
        val before = pane.mixCssText
        robot.interact { pane.dispose() }

        robot.interact { state.current.set(CssColor.fromRgb8(1, 2, 3)) }

        assertEquals(before, pane.mixCssText)
    }
}
