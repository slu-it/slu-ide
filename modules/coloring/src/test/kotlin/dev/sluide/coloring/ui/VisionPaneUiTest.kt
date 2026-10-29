package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.Deficiency
import dev.sluide.coloring.analysis.simulate
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import javafx.scene.Scene
import javafx.scene.control.Label
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
import kotlin.test.assertTrue

/** Wiring of the Vision pane to the window's state; the rows themselves are tested in VisionModelTest. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class VisionPaneUiTest {
    private val state = ColoringState()
    private lateinit var pane: VisionPane

    @Start
    fun start(stage: Stage) {
        state.current.set(CssColor.fromRgb8(255, 0, 0))
        state.second.set(CssColor.fromRgb8(0, 20, 0))
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
    fun `changing the current color updates the normal and a simulated row`(robot: FxRobot) {
        robot.interact { state.current.set(CssColor.fromRgb8(0, 0, 255)) }

        assertEquals("#0000ff", swatches(robot)[0].tooltipText)
        assertEquals(
            cssText(simulate(CssColor.fromRgb8(0, 0, 255), Deficiency.PROTANOPIA)),
            cssTexts(robot)[1],
        )
    }

    @Test
    fun `switching the roles swaps the colors of the sample`(robot: FxRobot) {
        val before = sampleColors(robot)

        robot.interact { state.currentIsText.set(false) }

        assertEquals(before.reversed(), sampleColors(robot))
    }

    @Test
    fun `after dispose nothing updates`(robot: FxRobot) {
        val before = cssTexts(robot)
        val sample = sampleColors(robot)

        robot.interact {
            pane.dispose()
            state.current.set(CssColor.fromRgb8(0, 0, 255))
            state.currentIsText.set(false)
        }

        assertEquals(before, cssTexts(robot))
        assertEquals(sample, sampleColors(robot))
    }

    @Test
    fun `the warning shows for a pair that loses contrast and hides for a passing one`(robot: FxRobot) {
        assertTrue(warningLabel(robot).isVisible)

        robot.interact { state.second.set(CssColor.fromRgb8(255, 255, 255)) }
        robot.interact { state.current.set(CssColor.fromRgb8(0, 0, 0)) }

        assertFalse(warningLabel(robot).isVisible)
    }
}
