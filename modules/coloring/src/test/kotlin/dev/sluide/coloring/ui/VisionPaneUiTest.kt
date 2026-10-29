package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.Deficiency
import dev.sluide.coloring.analysis.simulate
import dev.sluide.coloring.color.ColorFormatter
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.StackPane
import javafx.scene.paint.Paint
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Wiring of the Vision pane to the window's state; the rows themselves are tested in VisionModelTest. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class VisionPaneUiTest {
    private val state = ColoringState()
    private lateinit var pane: VisionPane

    @Start
    fun start(stage: Stage) {
        state.contrastText.color.set(CssColor.fromRgb8(255, 0, 0))
        state.contrastBackground.color.set(CssColor.fromRgb8(0, 20, 0))
        pane = VisionPane(state)
        stage.scene = Scene(StackPane(pane.node), 460.0, 520.0)
        stage.show()
    }

    private fun swatches(robot: FxRobot) = robot.lookup(".swatch").queryAllAs(Swatch::class.java).toList()

    private fun samples(robot: FxRobot) = robot.lookup(".vision-sample").queryAllAs(StackPane::class.java).toList()

    /** Background and text color of the first sample. */
    private fun sampleColors(robot: FxRobot): List<Paint> =
        listOf(
            samples(robot)[0].background.fills[0].fill,
            robot
                .lookup(".vision-sample-text")
                .queryAllAs(Label::class.java)
                .first()
                .textFill,
        )

    private fun warningLabel(robot: FxRobot) = robot.lookup(".vision-warning").queryAs(Label::class.java)

    private fun cssTexts(robot: FxRobot) = robot.lookup(".vision-css").queryAllAs(Label::class.java).map { it.text }

    @Test
    fun `changing the text color updates the normal and a simulated row`(robot: FxRobot) {
        robot.interact { state.contrastText.color.set(CssColor.fromRgb8(0, 0, 255)) }

        assertEquals("#0000ff", swatches(robot)[2].tooltipText)
        assertEquals(
            cssText(simulate(CssColor.fromRgb8(0, 0, 255), Deficiency.PROTANOPIA)),
            cssTexts(robot)[2],
        )
    }

    @Test
    fun `the slot views select the shared Contrast slots for the Vision tab`(robot: FxRobot) {
        robot.interact { click(swatches(robot)[1]) }

        assertSame(state.contrastBackground, state.editing.get())
        assertSame(state.contrastBackground, state.remembered[ColoringState.VISION])
        assertNull(state.remembered[ColoringState.CONTRAST])
    }

    @Test
    fun `a click on a simulated swatch inspects it and changes no slot`(robot: FxRobot) {
        val text = state.contrastText.color.get()
        val background = state.contrastBackground.color.get()
        val simulatedBackground = swatches(robot)[5]
        val expected = simulatedBackground.tooltipText

        robot.interact { click(simulatedBackground) }

        assertEquals(expected, ColorFormatter.hex(state.inspected.get()))
        assertEquals(text, state.contrastText.color.get())
        assertEquals(background, state.contrastBackground.color.get())
    }

    @Test
    fun `swapping the pair swaps the colors of the sample`(robot: FxRobot) {
        val before = sampleColors(robot)

        robot.interact {
            val text = state.contrastText.color.get()
            state.contrastText.color.set(state.contrastBackground.color.get())
            state.contrastBackground.color.set(text)
        }

        assertEquals(before.reversed(), sampleColors(robot))
    }

    @Test
    fun `after dispose nothing updates`(robot: FxRobot) {
        val before = cssTexts(robot)
        val sample = sampleColors(robot)
        val slotText = robot.lookup("#vision-text").queryAs(TextField::class.java).text

        robot.interact {
            pane.dispose()
            state.contrastText.color.set(CssColor.fromRgb8(0, 0, 255))
            state.contrastBackground.color.set(CssColor.fromRgb8(255, 255, 255))
        }

        assertEquals(before, cssTexts(robot))
        assertEquals(sample, sampleColors(robot))
        assertEquals(slotText, robot.lookup("#vision-text").queryAs(TextField::class.java).text)
    }

    @Test
    fun `the warning shows for a pair that loses contrast and hides for a passing one`(robot: FxRobot) {
        assertTrue(warningLabel(robot).isVisible)

        robot.interact { state.contrastBackground.color.set(CssColor.fromRgb8(255, 255, 255)) }
        robot.interact { state.contrastText.color.set(CssColor.fromRgb8(0, 0, 0)) }

        assertFalse(warningLabel(robot).isVisible)
    }
}
