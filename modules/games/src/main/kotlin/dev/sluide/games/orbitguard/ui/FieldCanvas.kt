package dev.sluide.games.orbitguard.ui

import dev.sluide.games.common.SessionState
import dev.sluide.games.common.ui.FieldLayout
import dev.sluide.games.orbitguard.OrbitGuardGame
import dev.sluide.games.orbitguard.OrbitGuardModel
import javafx.scene.canvas.Canvas
import javafx.util.Subscription

/** The field of the game drawn to fit its area; [redraw] on every change and every frame. FX thread. */
internal class FieldCanvas(
    private val model: OrbitGuardModel,
) : Canvas() {
    private val layout = FieldLayout(OrbitGuardGame.WIDTH, OrbitGuardGame.HEIGHT)
    private val field = FieldPainter()
    private val hud = HudPainter()
    private var images: SpriteImages? = null

    /** The device scale of the current sprite images; null before the first drawing. */
    internal val deviceScale: Double? get() = images?.deviceScale
    private var small: SpriteImages? = null

    private val scaleSubscription: Subscription =
        sceneProperty()
            .flatMap { it.windowProperty() }
            .flatMap<Number> { it.renderScaleXProperty() }
            .subscribe { _ -> redraw() }

    init {
        widthProperty().addListener { _ -> redraw() }
        heightProperty().addListener { _ -> redraw() }
    }

    /** Stops following the window's render scale. */
    fun dispose() = scaleSubscription.unsubscribe()

    fun redraw() {
        if (width <= 0 || height <= 0) return
        val outputScale = scene?.window?.renderScaleX ?: 1.0
        val place = layout.fit(width, height, outputScale)
        val device = place.scale * outputScale
        val full = images?.takeIf { it.deviceScale == device } ?: SpriteImages(device).also { images = it }
        val half = small?.takeIf { it.deviceScale == device / 2 } ?: SpriteImages(device / 2).also { small = it }
        val gc = graphicsContext2D
        val frame = Frame(gc, place, full, half)
        gc.fill = Palette.BACKGROUND
        gc.fillRect(0.0, 0.0, width, height)
        gc.isImageSmoothing = false
        val game = model.game
        field.paint(frame, game)
        field.explosions(frame, game, hud)
        hud.hud(frame, model)
        // The paused overlay covers the field; no message under it.
        if (model.state != SessionState.PAUSED) hud.message(frame, model)
    }
}
