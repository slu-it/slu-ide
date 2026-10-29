package dev.sluide.coloring.ui.picker

import javafx.scene.Node
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.paint.Stop
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle

/**
 * A square of saturation (x) and value (y) for the current hue, with a strip of hues beside it. The square is three
 * stacked fills (the hue, white fading out to the right, transparent fading to black at the bottom), so nothing is
 * drawn per pixel. Pressing or dragging sets the values; outside the control they clamp to its edge.
 */
internal class SquarePicker(
    private val model: PickerModel,
) {
    /** The square, which takes the mouse. */
    val area =
        Pane().apply {
            id = "picker-square-area"
            fixSize(WIDTH, HEIGHT)
        }

    /** The hue strip, which takes the mouse. */
    val strip =
        Pane().apply {
            id = "picker-hue-strip"
            fixSize(STRIP_WIDTH, HEIGHT)
        }

    private val hueFill = Rectangle(WIDTH, HEIGHT)
    private val marker =
        Circle(MARKER_RADIUS).apply {
            styleClass += "picker-marker"
            isMouseTransparent = true
        }
    private val stripMarker =
        Rectangle(STRIP_WIDTH + 4, BAR_HEIGHT).apply {
            styleClass += "picker-marker"
            x = -2.0
            isMouseTransparent = true
        }

    /** Where the round marker sits in the square. */
    internal val markerPosition: Xy get() = Xy(marker.centerX, marker.centerY)

    val node: Node = HBox(GAP, area, strip)

    init {
        val white =
            Rectangle(WIDTH, HEIGHT).apply {
                fill = gradient(horizontal = true, Stop(0.0, Color.WHITE), Stop(1.0, Color.TRANSPARENT))
            }
        val black =
            Rectangle(WIDTH, HEIGHT).apply {
                fill = gradient(horizontal = false, Stop(0.0, Color.TRANSPARENT), Stop(1.0, Color.BLACK))
            }
        area.children.addAll(hueFill, white, black, marker)
        val hues =
            Rectangle(STRIP_WIDTH, HEIGHT).apply {
                fill =
                    gradient(
                        horizontal = false,
                        *(0..HUE_STOPS)
                            .map {
                                Stop(
                                    it / HUE_STOPS.toDouble(),
                                    Color.hsb(it * HUE_STEP, 1.0, 1.0),
                                )
                            }.toTypedArray(),
                    )
            }
        strip.children.addAll(hues, stripMarker)
        area.onPrimaryPressOrDrag(::setSquare)
        strip.onPrimaryPressOrDrag(::setHue)
        update()
    }

    /** Moves the markers and the square's hue to the model's values. */
    fun update() {
        hueFill.fill = Color.hsb(model.h, 1.0, 1.0)
        val p = PickerGeometry.svToSquare(model.s, model.v, WIDTH, HEIGHT)
        marker.centerX = p.x
        marker.centerY = p.y
        stripMarker.y = PickerGeometry.hueToStrip(model.h, HEIGHT) - BAR_HEIGHT / 2
    }

    private fun setSquare(e: MouseEvent) {
        val sv = PickerGeometry.squareToSv(e.x, e.y, WIDTH, HEIGHT)
        model.input(s = sv.s, v = sv.v)
    }

    private fun setHue(e: MouseEvent) = model.input(h = PickerGeometry.stripToHue(e.y, HEIGHT))

    internal companion object {
        const val WIDTH = 220.0
        const val HEIGHT = 180.0
        const val STRIP_WIDTH = 20.0
        const val GAP = 8.0
        private const val MARKER_RADIUS = 6.0
        private const val BAR_HEIGHT = 4.0
        private const val HUE_STOPS = 6
        private const val HUE_STEP = 60.0
    }
}
