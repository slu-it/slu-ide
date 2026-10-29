package dev.sluide.coloring.ui.picker

import dev.sluide.coloring.ui.Swatch
import javafx.scene.Node
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.paint.Stop
import javafx.scene.shape.Rectangle

/** A horizontal slider for the alpha, on a checkerboard, running from transparent to the opaque current color. */
internal class AlphaSlider(
    private val model: PickerModel,
    private val width: Double,
) {
    private val track = Rectangle(width, HEIGHT)
    private val thumb =
        Rectangle(THUMB_WIDTH, HEIGHT + 4).apply {
            styleClass += "picker-marker"
            y = -2.0
            isMouseTransparent = true
        }

    /** The slider, which takes the mouse. */
    val node: Node =
        Pane().apply {
            id = "picker-alpha-slider"
            fixSize(width, HEIGHT)
            children.addAll(Rectangle(width, HEIGHT).apply { fill = Swatch.CHECKER }, track, thumb)
            onPrimaryPressOrDrag(::set)
        }

    /** The x of the thumb's center. */
    internal val thumbX: Double get() = thumb.x + THUMB_WIDTH / 2

    init {
        update()
    }

    /** Moves the thumb and recolors the track from the model's values. */
    fun update() {
        val opaque = Color.hsb(model.h, model.s / PERCENT, model.v / PERCENT)
        track.fill = gradient(horizontal = true, Stop(0.0, opaque.deriveColor(0.0, 1.0, 1.0, 0.0)), Stop(1.0, opaque))
        thumb.x = PickerGeometry.alphaToX(model.a, width) - THUMB_WIDTH / 2
    }

    private fun set(e: MouseEvent) = model.input(a = PickerGeometry.xToAlpha(e.x, width))

    private companion object {
        const val HEIGHT = 16.0
        const val THUMB_WIDTH = 5.0
        const val PERCENT = 100.0
    }
}
