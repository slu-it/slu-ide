package dev.sluide.coloring.ui

import dev.sluide.coloring.color.ColorFormatter
import dev.sluide.coloring.color.CssColor
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.input.Clipboard
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** The wiring of the Shades & Harmonies pane to the window's state; needs swatches in a scene. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class HarmoniesPaneUiTest {
    private val state = ColoringState()
    private lateinit var pane: HarmoniesPane

    @Start
    fun start(stage: Stage) {
        pane = HarmoniesPane(state)
        stage.scene = Scene(pane.node as Parent, 700.0, 500.0)
        stage.show()
    }

    @Test
    fun `the source step mark moves with the current color`(robot: FxRobot) {
        val before = pane.sourceCells.single()

        robot.interact { state.current.set(CssColor.fromRgb8(10, 10, 10)) }

        assertNotSame(before, pane.sourceCells.single())
    }

    @Test
    fun `clicking a harmony swatch makes its color current`(robot: FxRobot) {
        val harmonySwatch = pane.harmonySwatches[1]
        val tip = harmonySwatch.tooltipText

        robot.interact { click(harmonySwatch) }

        assertEquals(tip, ColorFormatter.hex(state.current.get()))
    }

    @Test
    fun `after dispose a state change no longer updates the shades`(robot: FxRobot) {
        val before = pane.shownShades
        robot.interact { pane.dispose() }

        robot.interact { state.current.set(CssColor.fromRgb8(1, 2, 3)) }

        assertEquals(before, pane.shownShades)
    }

    @Test
    fun `copy as CSS variables copies the lines with the typed prefix`(robot: FxRobot) {
        robot.interact { pane.prefixField.text = "brand" }

        robot.interact { pane.copyButton.fire() }

        var clip: String? = null
        robot.interact { clip = Clipboard.getSystemClipboard().string }
        assertEquals(11, clip?.lines()?.size)
        assertTrue(clip?.startsWith("--brand-50: #") == true, clip)
    }
}
