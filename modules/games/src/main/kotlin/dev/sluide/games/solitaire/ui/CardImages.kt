package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Card
import javafx.scene.SnapshotParameters
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.image.Image
import javafx.scene.paint.Color
import kotlin.math.ceil

/**
 * Cards rendered once per card at one [width] and theme, then cached. FX thread only. The images have [scale] pixels
 * per unit (the screen's output scale), so the view shows them at [width] by [height].
 */
internal class CardImages(
    val width: Double,
    val dark: Boolean,
    val scale: Double = 1.0,
) {
    val height: Double = width * CardPainter.ASPECT

    private val faces = HashMap<Card, Image>()
    private var back: Image? = null

    fun face(card: Card): Image =
        faces.getOrPut(card) { render { CardPainter.paintFace(it, card, width, height, dark) } }

    fun back(): Image = back ?: render { CardPainter.paintBack(it, width, height, dark) }.also { back = it }

    private fun render(paint: (GraphicsContext) -> Unit): Image {
        val canvas = Canvas(ceil(width * scale), ceil(height * scale))
        val gc = canvas.graphicsContext2D
        gc.scale(scale, scale)
        paint(gc)
        val params = SnapshotParameters().apply { fill = Color.TRANSPARENT }
        return canvas.snapshot(params, null)
    }
}
