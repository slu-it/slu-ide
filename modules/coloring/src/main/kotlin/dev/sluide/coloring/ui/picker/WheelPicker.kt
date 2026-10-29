package dev.sluide.coloring.ui.picker

import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.paint.Stop
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import javafx.stage.Window
import javafx.util.Subscription
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A disc of hue (angle) and saturation (distance from the center) with a vertical slider of the value beside it. The
 * disc is an image drawn when the output scale of the window changes (HiDPI), never while dragging; the value darkens
 * it with a black overlay. Outside the disc a press or drag clamps to the rim.
 */
internal class WheelPicker(
    private val model: PickerModel,
) {
    /** The disc, which takes the mouse. */
    val area =
        Pane().apply {
            id = "picker-wheel-area"
            fixSize(SIZE, SIZE)
        }

    /** The value slider, which takes the mouse. */
    val slider =
        Pane().apply {
            id = "picker-value-slider"
            fixSize(SLIDER_WIDTH, SIZE)
        }

    private val image =
        ImageView().apply {
            fitWidth = SIZE
            fitHeight = SIZE
        }
    private val shade = Circle(RADIUS, RADIUS, RADIUS, Color.BLACK).apply { isMouseTransparent = true }
    private val marker =
        Circle(MARKER_RADIUS).apply {
            styleClass += "picker-marker"
            isMouseTransparent = true
        }
    private val sliderFill = Rectangle(SLIDER_WIDTH, SIZE)
    private val sliderMarker =
        Rectangle(SLIDER_WIDTH + 4, BAR_HEIGHT).apply {
            styleClass += "picker-marker"
            x = -2.0
            isMouseTransparent = true
        }

    val node: Node = HBox(GAP, area, slider)

    private val scaleSubscription: Subscription

    /** The size in pixels of the image drawn, 0 before the first draw. */
    internal var imagePixels = 0
        private set

    init {
        area.children.addAll(image, shade, marker)
        slider.children.addAll(sliderFill, sliderMarker)
        area.onPrimaryPressOrDrag(::setDisc)
        slider.onPrimaryPressOrDrag(::setValue)
        val scale: ObservableValue<Number> =
            area.sceneProperty().flatMap(Scene::windowProperty).flatMap(Window::outputScaleXProperty)
        scaleSubscription =
            scale.subscribe { value ->
                // Without a window the scale is unknown: keep the image until the node is shown.
                if (value != null) draw(value.toDouble())
            }
        update()
    }

    /** Stops following the window's output scale. */
    fun dispose() = scaleSubscription.unsubscribe()

    /** Moves the markers, darkens the disc and recolors the slider from the model's values. */
    fun update() {
        shade.opacity = 1 - model.v / PERCENT
        val p = PickerGeometry.hsToWheel(model.h, model.s, RADIUS)
        marker.centerX = RADIUS + p.x
        marker.centerY = RADIUS + p.y
        sliderFill.fill =
            gradient(
                horizontal = false,
                Stop(0.0, Color.hsb(model.h, model.s / PERCENT, 1.0)),
                Stop(1.0, Color.BLACK),
            )
        sliderMarker.y = PickerGeometry.valueToSlider(model.v, SIZE) - BAR_HEIGHT / 2
    }

    private fun setDisc(e: MouseEvent) {
        val hs = PickerGeometry.wheelToHs(e.x - RADIUS, e.y - RADIUS, RADIUS, model.h)
        model.input(h = hs.h, s = hs.s)
    }

    private fun setValue(e: MouseEvent) = model.input(v = PickerGeometry.sliderToValue(e.y, SIZE))

    /** Draws the disc at full value, with an antialiased rim, as an image of [SIZE] times [scale] pixels. */
    private fun draw(scale: Double) {
        val pixels = (SIZE * scale).roundToInt().coerceAtLeast(1)
        if (pixels == imagePixels) return
        imagePixels = pixels
        val radius = pixels / 2.0
        val argb = IntArray(pixels * pixels)
        for (y in 0 until pixels) {
            for (x in 0 until pixels) {
                val dx = x + HALF - radius
                val dy = y + HALF - radius
                val coverage = (radius - hypot(dx, dy) + HALF).coerceIn(0.0, 1.0)
                if (coverage <= 0.0) continue
                val hs = PickerGeometry.wheelToHs(dx, dy, radius, 0.0)
                val c = Color.hsb(hs.h, hs.s / PERCENT, 1.0)
                argb[y * pixels + x] =
                    ((coverage * OPAQUE).roundToInt() shl ALPHA_SHIFT) or
                    ((c.red * OPAQUE).roundToInt() shl RED_SHIFT) or
                    ((c.green * OPAQUE).roundToInt() shl GREEN_SHIFT) or
                    (c.blue * OPAQUE).roundToInt()
            }
        }
        image.image =
            WritableImage(pixels, pixels).also {
                it.pixelWriter.setPixels(0, 0, pixels, pixels, PixelFormat.getIntArgbInstance(), argb, 0, pixels)
            }
    }

    internal companion object {
        const val SIZE = 200.0
        const val RADIUS = SIZE / 2
        const val SLIDER_WIDTH = 20.0
        const val GAP = 8.0
        private const val MARKER_RADIUS = 6.0
        private const val BAR_HEIGHT = 4.0
        private const val PERCENT = 100.0
        private const val HALF = 0.5
        private const val OPAQUE = 255
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
    }
}
