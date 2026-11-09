package dev.sluide.games.minigolf.ui

import dev.sluide.games.common.ui.FieldLayout
import dev.sluide.games.minigolf.COURSE
import dev.sluide.games.minigolf.geometry.Vec
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// UI test: drawing needs the toolkit (fonts, the canvas graphics context) and a canvas to read pixels back from.
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MiniGolfPaintersUiTest {
    private lateinit var canvas: Canvas
    private val placement = FieldLayout(320, 240).fit(640.0, 480.0, 1.0)

    @Start
    fun start(stage: Stage) {
        canvas = Canvas(640.0, 480.0)
        stage.scene = Scene(StackPane(canvas), 640.0, 480.0)
        stage.show()
    }

    private fun pixel(
        x: Double,
        y: Double,
    ): Color = canvas.snapshot(null, null).pixelReader.getColor((x * 2).toInt(), (y * 2).toInt())

    @Test
    fun `every hole paints at scale 2 with felt and ball`(robot: FxRobot) {
        robot.interact {
            for (hole in COURSE) {
                for (step in listOf(0L, 37L, 400L)) {
                    HolePainter.paint(canvas.graphicsContext2D, placement, hole, step, hole.tee, true)
                }
                assertNotEquals(
                    MiniGolfPalette.SURROUND,
                    pixel(hole.tee.x + 8, hole.tee.y),
                    "felt of hole ${hole.number}",
                )
                assertNotEquals(MiniGolfPalette.SURROUND, pixel(hole.tee.x, hole.tee.y))
            }
        }
    }

    @Test
    fun `the ball is drawn only when visible`(robot: FxRobot) {
        val hole = COURSE.first()
        robot.interact {
            val gc = canvas.graphicsContext2D
            HolePainter.paint(gc, placement, hole, 0, hole.tee, true)
            val shown = pixel(hole.tee.x, hole.tee.y)
            HolePainter.paint(gc, placement, hole, 0, hole.tee, false)
            val hidden = pixel(hole.tee.x, hole.tee.y)

            assertNotEquals(hidden, shown)
        }
    }

    @Test
    fun `the ball is drawn over the flag`(robot: FxRobot) {
        val hole = COURSE[1]
        val onPennant = Vec(hole.cup.x + 5, hole.cup.y - 15)
        robot.interact {
            HolePainter.paint(canvas.graphicsContext2D, placement, hole, 0, onPennant, false)
            assertFalse(pixel(onPennant.x, onPennant.y).blue > 0.8, "the pennant is red")
            HolePainter.paint(canvas.graphicsContext2D, placement, hole, 0, onPennant, true)
            assertTrue(pixel(onPennant.x, onPennant.y).blue > 0.8, "the ball covers the pennant")
        }
    }

    @Test
    fun `aim ghost hud scorecard and burst paint without error`(robot: FxRobot) {
        robot.interact {
            val gc = canvas.graphicsContext2D
            val hole = COURSE.first()
            HolePainter.paint(gc, placement, hole, 0, hole.tee, true)
            AimPainter.paintAim(gc, placement, hole.tee, 0.0, 0.8)
            assertNotEquals(MiniGolfPalette.SURROUND, pixel(hole.tee.x + 30, hole.tee.y))
            AimPainter.paintGhost(gc, placement, listOf(hole.tee, Vec(120.0, 130.0), Vec(180.0, 128.0)))
            HudPainter.paint(gc, placement, 1, 2, 0, null)
            val pars = COURSE.map { it.par }
            ScorecardPainter.paint(
                gc,
                placement,
                pars,
                pars.map { it },
                List(pars.size) { null },
                final = false,
                newBest = false,
            )
            val dimmed = pixel(5.0, 100.0)
            assertNotEquals(MiniGolfPalette.SURROUND, dimmed, "the dim layer of the card covers the field")
            ScorecardPainter.paint(
                gc,
                placement,
                pars,
                pars.mapIndexed { i, p -> p.takeIf { i < 3 } },
                pars,
                final = true,
                newBest = true,
            )
            val burst = HoleInOneBurst()
            burst.start(hole.cup, 0)
            burst.paint(gc, placement, 30)
        }
    }
}
