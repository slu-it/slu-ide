package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Suit
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color

/** The four suit symbols as vector paths of our own design; no font glyphs, which differ between platforms. */
internal object SuitShapes {
    /** Fills [suit] in [color] into the square box of side [size] centred on [cx], [cy]; turned by 180 degrees if [inverted]. */
    fun draw(
        gc: GraphicsContext,
        suit: Suit,
        cx: Double,
        cy: Double,
        size: Double,
        color: Color,
        inverted: Boolean = false,
    ) {
        gc.save()
        gc.translate(cx, cy)
        if (inverted) gc.rotate(180.0)
        gc.scale(size, size)
        gc.fill = color
        when (suit) {
            Suit.HEARTS -> heart(gc, 1.0, 0.0)
            Suit.DIAMONDS -> diamond(gc)
            Suit.CLUBS -> club(gc)
            Suit.SPADES -> spade(gc)
        }
        gc.restore()
    }

    // Unit box -0.5..0.5; [k] and [d] squash and shift the y coordinates (a spade is a flipped, shorter heart).
    private fun heart(
        gc: GraphicsContext,
        k: Double,
        d: Double,
    ) {
        fun y(v: Double) = v * k + d
        gc.beginPath()
        gc.moveTo(0.0, y(0.5))
        gc.bezierCurveTo(-0.15, y(0.35), -0.5, y(0.1), -0.5, y(-0.18))
        gc.bezierCurveTo(-0.5, y(-0.42), -0.15, y(-0.5), 0.0, y(-0.25))
        gc.bezierCurveTo(0.15, y(-0.5), 0.5, y(-0.42), 0.5, y(-0.18))
        gc.bezierCurveTo(0.5, y(0.1), 0.15, y(0.35), 0.0, y(0.5))
        gc.closePath()
        gc.fill()
    }

    private fun diamond(gc: GraphicsContext) {
        gc.fillPolygon(doubleArrayOf(0.0, 0.38, 0.0, -0.38), doubleArrayOf(-0.5, 0.0, 0.5, 0.0), 4)
    }

    private fun club(gc: GraphicsContext) {
        val r = 0.22
        gc.fillOval(-r, -0.5, 2 * r, 2 * r)
        gc.fillOval(-0.5, -0.04, 2 * r, 2 * r)
        gc.fillOval(0.5 - 2 * r, -0.04, 2 * r, 2 * r)
        stem(gc)
    }

    private fun spade(gc: GraphicsContext) {
        heart(gc, -0.78, -0.11)
        stem(gc)
    }

    private fun stem(gc: GraphicsContext) {
        gc.beginPath()
        gc.moveTo(-0.04, 0.1)
        gc.quadraticCurveTo(-0.04, 0.38, -0.2, 0.5)
        gc.lineTo(0.2, 0.5)
        gc.quadraticCurveTo(0.04, 0.38, 0.04, 0.1)
        gc.closePath()
        gc.fill()
    }
}
