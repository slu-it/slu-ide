package dev.sluide.games.blockbreaker.ui

import dev.sluide.games.blockbreaker.BlockBreakerGame
import dev.sluide.games.blockbreaker.BlockBreakerModel
import dev.sluide.games.common.SessionState
import dev.sluide.games.common.ui.FieldLayout
import dev.sluide.games.common.ui.FieldPlacement
import javafx.scene.canvas.Canvas
import javafx.util.Subscription

/**
 * The logical field x of [x], a coordinate in an area of [areaWidth] x [areaHeight] showing the field as [layout]
 * fits it at [zoom]; may lie outside the field (the model clamps). Pure.
 */
internal fun fieldX(
    layout: FieldLayout,
    areaWidth: Double,
    areaHeight: Double,
    x: Double,
    zoom: Double,
): Double {
    val place = layout.fit(areaWidth, areaHeight, 1.0, zoom)
    return (x - place.offsetX) / place.scale
}

/** The field of the game drawn to fit its area; [redraw] on every change and every frame. FX thread. */
internal class FieldCanvas(
    private val model: BlockBreakerModel,
) : Canvas() {
    private val layout = FieldLayout(BlockBreakerGame.WIDTH, BlockBreakerGame.HEIGHT)
    private val field = FieldPainter()
    private val hud = HudPainter()
    private var images: BrickImages? = null

    /** The device scale of the current images; null before the first drawing. */
    internal val deviceScale: Double? get() = images?.deviceScale

    /** The game zoom (ADR 0155), at most 1.0; setting it redraws. */
    var zoom: Double = 1.0
        set(value) {
            field = value
            redraw()
        }

    private val scaleSubscription: Subscription =
        sceneProperty()
            .flatMap { it.windowProperty() }
            .flatMap<Number> { it.renderScaleXProperty() }
            .subscribe { _ -> redraw() }

    init {
        widthProperty().addListener { _ -> redraw() }
        heightProperty().addListener { _ -> redraw() }
    }

    /** The logical field x of [x] in canvas coordinates. */
    fun fieldX(x: Double): Double = fieldX(layout, width, height, x, zoom)

    /** Where the field lies in the canvas at the current zoom. */
    internal val placement: FieldPlacement
        get() = layout.fit(width, height, scene?.window?.renderScaleX ?: 1.0, zoom)

    /** Stops following the window's render scale. */
    fun dispose() = scaleSubscription.unsubscribe()

    fun redraw() {
        if (width <= 0 || height <= 0) return
        val outputScale = scene?.window?.renderScaleX ?: 1.0
        val place = placement
        val device = place.scale * outputScale
        val brickImages = images?.takeIf { it.deviceScale == device } ?: BrickImages(device).also { images = it }
        val gc = graphicsContext2D
        val frame = Frame(gc, place, brickImages)
        gc.fill = Palette.BACKGROUND
        gc.fillRect(0.0, 0.0, width, height)
        gc.isImageSmoothing = false
        val game = model.game
        field.paint(frame, game)
        hud.hud(frame, model)
        // The paused overlay covers the field; no message under it.
        if (model.state != SessionState.PAUSED) hud.message(frame, model)
    }
}
