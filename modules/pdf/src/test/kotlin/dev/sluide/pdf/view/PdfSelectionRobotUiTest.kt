package dev.sluide.pdf.view

import javafx.geometry.Point2D
import javafx.scene.input.MouseButton
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A drag with the real pointer: the events go through the scene's own routing (picking, delivery to the press target,
 * the scroll pane's skin), which the tests that fire events at the pages skip.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
internal class PdfSelectionRobotUiTest : PdfSelectionUiBase() {
    @Start
    fun start(stage: Stage) = startStage(stage)

    private fun screen(at: Point2D): Point2D =
        fx {
            Point2D(
                stage.x + stage.scene.x + at.x,
                stage.y + stage.scene.y + at.y,
            )
        }

    @Test
    fun `a drag with the real pointer over a word selects and paints it`(robot: FxRobot) {
        // Real input: only it shows the scene's own routing of the press, the drags and the release.
        val view = textView()
        val second = scene(view, "second")
        val from = screen(second.left())
        val to = screen(second.right())
        robot.moveTo(from) // real input, see above
        robot.press(MouseButton.PRIMARY) // real input, see above
        for (step in 1..10) robot.moveTo(Point2D(from.x + (to.x - from.x) * step / 10, from.y)) // real input, see above
        assertTrue(selected(view), "selected while the button is held")
        robot.release(MouseButton.PRIMARY) // real input, see above
        assertTrue(selected(view), "selected after the release")
        assertTrue(colours(paint(), second).hasSelectionBlue())
    }
}
