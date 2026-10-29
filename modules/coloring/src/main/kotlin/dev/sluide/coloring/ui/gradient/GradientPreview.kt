package dev.sluide.coloring.ui.gradient

import dev.sluide.coloring.analysis.Gradient
import dev.sluide.coloring.analysis.GradientType
import dev.sluide.coloring.analysis.linearEndpoints
import dev.sluide.coloring.analysis.previewStops
import dev.sluide.coloring.analysis.sample
import dev.sluide.coloring.ui.Swatch
import dev.sluide.coloring.ui.toArgbClipped
import dev.sluide.coloring.ui.toFxColorClipped
import javafx.animation.Animation
import javafx.animation.PauseTransition
import javafx.scene.Scene
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.Region
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.ImagePattern
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.RadialGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.Rectangle
import javafx.stage.Window
import javafx.util.Duration
import javafx.util.Subscription
import kotlin.math.hypot

/**
 * The large preview of a gradient on a checkerboard. Colors outside sRGB are clipped per channel, as browsers paint
 * gradients. Linear and radial gradients are painted from [previewStops], so every interpolation space shows as CSS
 * would; a conic one is drawn per pixel into an image of the size in device pixels. The image is redrawn when the
 * size or the output scale changes and, at most once per [conicThrottleMillis], when the gradient changes: the
 * first change draws at once, later ones within the interval wait for its end and draw the latest, so a drag
 * updates the preview steadily without a redraw per mouse move.
 */
internal class GradientPreview(
    private val conicThrottleMillis: Double = 50.0,
) : Region() {
    private val checker = Rectangle().apply { fill = Swatch.CHECKER }
    private val picture = Rectangle()
    private val trailing = PauseTransition().apply { setOnFinished { drawConic() } }
    private val scaleSubscription: Subscription
    private var lastDraw = System.nanoTime() - NEVER
    private var lookup: IntArray? = null
    private var lookupKey: Gradient? = null

    /** The gradient shown; set it to redraw. */
    var gradient: Gradient? = null
        set(value) {
            if (value == field) return
            field = value
            redraw(forceConic = false)
        }

    /** How many conic renderings happened; for tests. */
    internal var conicRenderings = 0
        private set

    /** The conic gradient of the last rendering; for tests. */
    internal var renderedGradient: Gradient? = null
        private set

    init {
        styleClass += "gradient-preview"
        setPrefSize(PREF_WIDTH, HEIGHT)
        setMinHeight(HEIGHT)
        setMaxHeight(HEIGHT)
        children += listOf(checker, picture)
        scaleSubscription =
            sceneProperty()
                .flatMap(Scene::windowProperty)
                .flatMap(Window::outputScaleXProperty)
                .subscribe { _ -> redraw(forceConic = true) }
    }

    /** Stops following the window's output scale and cancels a pending redraw. */
    fun dispose() {
        scaleSubscription.unsubscribe()
        trailing.stop()
    }

    override fun layoutChildren() {
        for (r in listOf(checker, picture)) {
            r.width = width
            r.height = height
        }
        redraw(forceConic = true)
    }

    private fun redraw(forceConic: Boolean) {
        val g = gradient ?: return
        if (width <= 0.0 || height <= 0.0) return
        when (g.type) {
            GradientType.LINEAR -> {
                trailing.stop()
                val (from, to) = linearEndpoints(g.angleDeg, width, height)
                picture.fill = LinearGradient(from.x, from.y, to.x, to.y, false, CycleMethod.NO_CYCLE, stops(g))
            }

            GradientType.RADIAL -> {
                trailing.stop()
                val radius = hypot(width / 2, height / 2)
                picture.fill =
                    RadialGradient(0.0, 0.0, width / 2, height / 2, radius, false, CycleMethod.NO_CYCLE, stops(g))
            }

            GradientType.CONIC -> {
                scheduleConic(forceConic)
            }
        }
    }

    private fun scheduleConic(force: Boolean) {
        if (force || conicThrottleMillis <= 0.0) {
            trailing.stop()
            drawConic()
            return
        }
        if (trailing.status == Animation.Status.RUNNING) return
        val waited = (System.nanoTime() - lastDraw) / NANOS_PER_MILLI
        if (waited >= conicThrottleMillis) {
            drawConic()
        } else {
            trailing.duration = Duration.millis(conicThrottleMillis - waited)
            trailing.playFromStart()
        }
    }

    private fun drawConic() {
        val g = gradient?.takeIf { it.type == GradientType.CONIC } ?: return
        if (width <= 0.0 || height <= 0.0) return
        conicRenderings++
        renderedGradient = g
        lastDraw = System.nanoTime()
        val scale = scene?.window?.outputScaleX ?: 1.0
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val image = WritableImage(w, h)
        val pixels = conicPixels(w, h, g.angleDeg, lookupOf(g))
        image.pixelWriter.setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w)
        picture.fill = ImagePattern(image, 0.0, 0.0, width, height, false)
    }

    /** The colors around the circle; kept while only the start angle or the size changes. */
    private fun lookupOf(g: Gradient): IntArray {
        val key = g.copy(angleDeg = 0.0)
        lookup?.takeIf { key == lookupKey }?.let { return it }
        return sample(g, LOOKUP_SIZE)
            .map { it.toArgbClipped() }
            .toIntArray()
            .also {
                lookup = it
                lookupKey = key
            }
    }

    private fun stops(g: Gradient): List<Stop> = previewStops(g).map { Stop(it.position, it.color.toFxColorClipped()) }

    companion object {
        const val HEIGHT = 200.0
        private const val PREF_WIDTH = 400.0
        private const val LOOKUP_SIZE = 720
        private const val NANOS_PER_MILLI = 1_000_000.0
        private const val NEVER = 3_600_000_000_000L
    }
}
