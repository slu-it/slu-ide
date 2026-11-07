package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Card
import javafx.geometry.VPos
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import kotlin.math.floor

/** Draws single cards, scaled to the given width and height; the table view puts the images on its piles. */
internal object CardPainter {
    /** Height of a card as a multiple of its width. */
    const val ASPECT = 1.4

    private val RED = Color.web("#c62828")
    private val BLACK = Color.web("#1c1c1c")

    /** Draws the face of [card] into 0,0 to [w], [h]; [dark] only softens the white a little, the faces stay light. */
    fun paintFace(
        gc: GraphicsContext,
        card: Card,
        w: Double,
        h: Double,
        dark: Boolean,
    ) {
        val ink = if (card.red) RED else BLACK
        paper(gc, w, h, dark)
        index(gc, card, w, h, ink, inverted = false)
        index(gc, card, w, h, ink, inverted = true)
        if (card.rank <= 10) pips(gc, card, w, h, ink) else faceCard(gc, card, w, h, ink)
    }

    /** Draws the back of a card: a white margin around a blue field with a diamond lattice and a small emblem. */
    fun paintBack(
        gc: GraphicsContext,
        w: Double,
        h: Double,
        dark: Boolean,
    ) {
        paper(gc, w, h, dark)
        val m = snap(w * 0.07)
        val r = w * 0.03
        val blue = Color.web(if (dark) "#28497a" else "#2f5f9e")
        gc.fill = blue
        gc.fillRoundRect(m, m, w - 2 * m, h - 2 * m, r, r)
        gc.save()
        gc.beginPath()
        gc.rect(m, m, w - 2 * m, h - 2 * m)
        gc.clip()
        gc.stroke = Color.web("#ffffff", 0.28)
        gc.lineWidth = 1.0
        val step = w * 0.15
        var x = -h
        while (x < w + h) {
            gc.strokeLine(x, 0.0, x + h, h)
            gc.strokeLine(x + h, 0.0, x, h)
            x += step
        }
        gc.restore()
        gc.stroke = Color.web("#ffffff", 0.9)
        gc.lineWidth = 1.0
        gc.strokeRoundRect(m + 0.5, m + 0.5, w - 2 * m - 1, h - 2 * m - 1, r, r)
        val c = w * 0.18
        val xs = doubleArrayOf(w / 2, w / 2 + c, w / 2, w / 2 - c)
        val ys = doubleArrayOf(h / 2 - c * 1.4, h / 2, h / 2 + c * 1.4, h / 2)
        gc.fill = blue
        gc.fillPolygon(xs, ys, 4)
        gc.stroke = Color.WHITE
        gc.lineWidth = 1.5
        gc.strokePolygon(xs, ys, 4)
        gc.fill = Color.WHITE
        val dot = w * 0.06
        gc.fillOval(w / 2 - dot, h / 2 - dot, 2 * dot, 2 * dot)
    }

    private fun paper(
        gc: GraphicsContext,
        w: Double,
        h: Double,
        dark: Boolean,
    ) {
        val r = w * 0.12
        gc.fill = if (dark) Color.web("#e9e6df") else Color.WHITE
        gc.fillRoundRect(0.0, 0.0, w, h, r, r)
        gc.stroke = if (dark) Color.web("#8f8b82") else Color.web("#b4b4b4")
        gc.lineWidth = 1.0
        gc.strokeRoundRect(0.5, 0.5, w - 1, h - 1, r, r)
    }

    private fun index(
        gc: GraphicsContext,
        card: Card,
        w: Double,
        h: Double,
        ink: Color,
        inverted: Boolean,
    ) {
        gc.save()
        if (inverted) {
            gc.translate(w, h)
            gc.rotate(180.0)
        }
        text(gc, label(card.rank), w * 0.12, h * 0.095, h * 0.13, ink, w * 0.18)
        SuitShapes.draw(gc, card.suit, w * 0.12, h * 0.225, w * 0.16, ink)
        gc.restore()
    }

    private fun pips(
        gc: GraphicsContext,
        card: Card,
        w: Double,
        h: Double,
        ink: Color,
    ) {
        val x0 = w * 0.31
        val xw = w * 0.38
        val y0 = h * 0.18
        val yh = h * 0.64
        val size = if (card.rank == 1) w * 0.42 else w * 0.14
        for (p in PipLayout.pips(card.rank)) {
            SuitShapes.draw(gc, card.suit, x0 + p.x * xw, y0 + p.y * yh, size, ink, p.inverted)
        }
    }

    private fun faceCard(
        gc: GraphicsContext,
        card: Card,
        w: Double,
        h: Double,
        ink: Color,
    ) {
        val tint = ink.interpolate(Color.WHITE, 0.88)
        val px = snap(w * 0.24)
        val py = snap(h * 0.08)
        gc.fill = tint
        gc.fillRect(px, py, w - 2 * px, h - 2 * py)
        gc.stroke = ink
        gc.lineWidth = 1.0
        gc.strokeRect(px + 0.5, py + 0.5, w - 2 * px - 1, h - 2 * py - 1)
        for (turned in listOf(false, true)) {
            gc.save()
            if (turned) {
                gc.translate(w, h)
                gc.rotate(180.0)
            }
            FaceEmblems.draw(gc, card.rank, w / 2, h * 0.21, w * 0.28, ink, tint)
            text(gc, label(card.rank), w / 2, h * 0.375, h * 0.1, ink, w * 0.4)
            gc.restore()
        }
        gc.stroke = ink.interpolate(Color.WHITE, 0.5)
        gc.lineWidth = 1.0
        val mid = floor(h / 2) + 0.5
        gc.strokeLine(px, mid, w - px, mid)
    }

    private fun text(
        gc: GraphicsContext,
        s: String,
        cx: Double,
        cy: Double,
        size: Double,
        color: Color,
        maxWidth: Double,
    ) {
        gc.font = Font.font("System", FontWeight.BOLD, size)
        gc.textAlign = TextAlignment.CENTER
        gc.textBaseline = VPos.CENTER
        gc.fill = color
        gc.fillText(s, cx, cy, maxWidth)
    }

    private fun label(rank: Int) =
        when (rank) {
            1 -> "A"
            11 -> "J"
            12 -> "Q"
            13 -> "K"
            else -> rank.toString()
        }

    private fun snap(v: Double) = Math.round(v).toDouble()
}
