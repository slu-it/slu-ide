package dev.sluide.games.solitaire.ui

import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.shape.ArcType

/** The emblems of the face cards, simple geometric shapes of our own: a crown (K), a diadem (Q) and a shield (J). */
internal object FaceEmblems {
    /** Draws the emblem of [rank] (11, 12 or 13) in [color] into the box of side [size] centred on [cx], [cy]. */
    fun draw(
        gc: GraphicsContext,
        rank: Int,
        cx: Double,
        cy: Double,
        size: Double,
        color: Color,
        tint: Color,
    ) {
        gc.save()
        gc.translate(cx, cy)
        gc.scale(size, size)
        gc.fill = color
        gc.stroke = color
        when (rank) {
            13 -> crown(gc)
            12 -> diadem(gc)
            else -> shield(gc, tint)
        }
        gc.restore()
    }

    private fun crown(gc: GraphicsContext) {
        gc.fillPolygon(
            doubleArrayOf(-0.45, -0.45, -0.22, 0.0, 0.22, 0.45, 0.45),
            doubleArrayOf(0.4, -0.2, 0.08, -0.32, 0.08, -0.2, 0.4),
            7,
        )
        for (x in doubleArrayOf(-0.45, 0.0, 0.45)) {
            val y = if (x == 0.0) -0.32 else -0.2
            gc.fillOval(x - 0.075, y - 0.15, 0.15, 0.15)
        }
        gc.fillRect(-0.45, 0.4, 0.9, 0.08)
    }

    private fun diadem(gc: GraphicsContext) {
        gc.lineWidth = 0.1
        gc.strokeArc(-0.4, -0.3, 0.8, 1.0, 0.0, 180.0, ArcType.OPEN)
        gc.fillRect(-0.48, 0.2, 0.96, 0.14)
        gc.fillPolygon(doubleArrayOf(0.0, 0.14, 0.0, -0.14), doubleArrayOf(-0.5, -0.32, -0.14, -0.32), 4)
        for (x in doubleArrayOf(-0.4, 0.4)) gc.fillOval(x - 0.07, 0.0, 0.14, 0.14)
    }

    private fun shield(
        gc: GraphicsContext,
        tint: Color,
    ) {
        gc.beginPath()
        gc.moveTo(-0.4, -0.45)
        gc.lineTo(0.4, -0.45)
        gc.lineTo(0.4, 0.05)
        gc.bezierCurveTo(0.4, 0.3, 0.15, 0.4, 0.0, 0.5)
        gc.bezierCurveTo(-0.15, 0.4, -0.4, 0.3, -0.4, 0.05)
        gc.closePath()
        gc.fill()
        gc.stroke = tint
        gc.lineWidth = 0.1
        gc.strokeLine(0.0, -0.3, 0.0, 0.3)
    }
}
