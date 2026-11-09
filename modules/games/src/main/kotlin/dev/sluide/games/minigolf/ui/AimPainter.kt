package dev.sluide.games.minigolf.ui

import dev.sluide.games.common.ui.FieldPlacement
import dev.sluide.games.minigolf.geometry.Vec
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin

/** The aim help while dragging and the ghost of the previous shot. */
internal object AimPainter {
    private const val MIN_LENGTH = 12.0
    private const val LENGTH_PER_POWER = 48.0

    private val GREEN = Color.web("#5fe08a")
    private val YELLOW = Color.web("#ffe14d")
    private val RED = Color.web("#ff4d3d")

    /** The line length for a [power] of 0..1. */
    fun lengthFor(power: Double): Double = MIN_LENGTH + LENGTH_PER_POWER * power.coerceIn(0.0, 1.0)

    /** A line from [ball] in the shot direction [angle] (radians), longer and redder with more [power] (0..1). */
    fun paintAim(
        gc: GraphicsContext,
        placement: FieldPlacement,
        ball: Vec,
        angle: Double,
        power: Double,
    ) {
        val direction = Vec.ofAngle(angle)
        val from = ball + direction * 4.0
        val to = ball + direction * lengthFor(power)
        val color = powerColor(power)
        gc.inField(placement) {
            lineCap = StrokeLineCap.ROUND
            lineJoin = StrokeLineJoin.ROUND
            stroke = Color.color(0.0, 0.0, 0.0, 0.45)
            fill = stroke
            lineWidth = 3.4
            arrow(from, to, 6.5)
            stroke = color
            fill = color
            lineWidth = 2.0
            arrow(from, to, 5.5)
        }
    }

    /** The previous shot's path as a faint dashed line. */
    fun paintGhost(
        gc: GraphicsContext,
        placement: FieldPlacement,
        path: List<Vec>,
    ) {
        if (path.size < 2) return
        gc.inField(placement) {
            stroke = MiniGolfPalette.GHOST
            lineWidth = 1.0
            lineCap = StrokeLineCap.BUTT
            setLineDashes(3.0, 3.0)
            beginPath()
            path.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            stroke()
            setLineDashes()
        }
    }

    /** Green at power 0, yellow at 0.5, red at 1. */
    fun powerColor(power: Double): Color {
        val p = power.coerceIn(0.0, 1.0)
        return if (p <= 0.5) GREEN.interpolate(YELLOW, p * 2) else YELLOW.interpolate(RED, (p - 0.5) * 2)
    }
}
