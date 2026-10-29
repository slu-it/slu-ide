package dev.sluide.coloring.ui

import dev.sluide.coloring.color.ColorFormatter
import dev.sluide.coloring.color.CssColor
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.control.Tooltip
import javafx.scene.image.WritableImage
import javafx.scene.input.MouseButton
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.scene.paint.ImagePattern
import javafx.scene.shape.Rectangle
import javafx.util.Subscription

/**
 * A clickable color square (or a [width] x [height] rectangle) on a checkerboard, so that the alpha shows; [onClick]
 * runs on a primary click.
 */
internal class Swatch(
    private val color: ObservableValue<CssColor>,
    size: Double = 24.0,
    width: Double = size,
    height: Double = size,
    onClick: (CssColor) -> Unit = {},
) : Region() {
    private val fill = Rectangle(width, height)
    private val checker = Rectangle(width, height).apply { this.fill = CHECKER }
    private val tip = Tooltip()
    private val subscription: Subscription

    /** The text of the tooltip: the hex of the color shown. */
    internal val tooltipText: String? get() = tip.text

    init {
        styleClass += "swatch"
        setMinSize(width, height)
        setPrefSize(width, height)
        setMaxSize(width, height)
        children += checker
        children += fill
        Tooltip.install(this, tip)
        subscription = color.subscribe { c -> show(c) }
        setOnMouseClicked { e ->
            if (e.button == MouseButton.PRIMARY) color.value?.let(onClick)
        }
    }

    /** The rectangles follow the size of the region, so a swatch that may grow (the preview) fills its space. */
    override fun layoutChildren() {
        listOf(checker, fill).forEach {
            it.width = width
            it.height = height
        }
    }

    /** Stops following the color; a swatch of a closed tab must not stay subscribed to the window's state. */
    fun dispose() = subscription.unsubscribe()

    private fun show(color: CssColor?) {
        fill.fill = color?.toFxColor() ?: Color.TRANSPARENT
        tip.text = color?.let(ColorFormatter::hex)
    }

    companion object {
        private val CHECKER =
            ImagePattern(
                WritableImage(2, 2).also { image ->
                    val writer = image.pixelWriter
                    val light = Color.rgb(255, 255, 255)
                    val dark = Color.rgb(200, 200, 200)
                    writer.setColor(0, 0, light)
                    writer.setColor(1, 1, light)
                    writer.setColor(1, 0, dark)
                    writer.setColor(0, 1, dark)
                },
                0.0,
                0.0,
                8.0,
                8.0,
                false,
            )

        /** A swatch of a changing [color]; a primary click makes it the current color of [state]. */
        fun of(
            color: ObservableValue<CssColor>,
            state: ColoringState,
            size: Double = 24.0,
        ) = Swatch(color, size) { state.current.set(it) }

        /** A swatch of a fixed [color]; a primary click makes it the current color of [state]. */
        fun of(
            color: CssColor,
            state: ColoringState,
            size: Double = 24.0,
        ) = of(SimpleObjectProperty(color), state, size)
    }
}
