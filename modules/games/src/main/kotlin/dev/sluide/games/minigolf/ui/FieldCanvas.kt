package dev.sluide.games.minigolf.ui

import dev.sluide.games.common.SessionState
import dev.sluide.games.common.ui.FieldLayout
import dev.sluide.games.common.ui.FieldPlacement
import dev.sluide.games.minigolf.MiniGolfGame
import dev.sluide.games.minigolf.MiniGolfModel
import dev.sluide.games.minigolf.Phase
import dev.sluide.games.minigolf.Rules
import dev.sluide.games.minigolf.geometry.Vec
import javafx.geometry.VPos
import javafx.scene.canvas.Canvas
import javafx.scene.canvas.GraphicsContext
import javafx.scene.text.TextAlignment
import javafx.util.Subscription
import kotlin.math.max

/**
 * The logical field point of ([x], [y]), a point in an area of [areaWidth] x [areaHeight] showing the field as
 * [layout] fits it; may lie outside the field (a drag that leaves it keeps reporting; the model caps the power). Pure.
 */
internal fun fieldPoint(
    layout: FieldLayout,
    areaWidth: Double,
    areaHeight: Double,
    x: Double,
    y: Double,
): Vec {
    val place = layout.fit(areaWidth, areaHeight, 1.0)
    return Vec((x - place.offsetX) / place.scale, (y - place.offsetY) / place.scale)
}

/**
 * The stroke number the HUD shows: the one the next shot will be while the ball rests or a push (no shot) rolls it,
 * else the one being played; at least 1. Pure.
 */
internal fun hudStroke(
    phase: Phase,
    strokes: Int,
    shotInProgress: Boolean,
): Int = if (phase == Phase.AIMING || (phase == Phase.ROLLING && !shotInProgress)) strokes + 1 else max(strokes, 1)

/** The field of the round drawn to fit its area; [redraw] on every change and every frame. FX thread. */
internal class FieldCanvas(
    private val model: MiniGolfModel,
) : Canvas() {
    private val layout = FieldLayout(Rules.FIELD_WIDTH.toInt(), Rules.FIELD_HEIGHT.toInt())
    private val burst = BurstState()

    private val scaleSubscription: Subscription =
        sceneProperty()
            .flatMap { it.windowProperty() }
            .flatMap<Number> { it.renderScaleXProperty() }
            .subscribe { _ -> redraw() }

    init {
        widthProperty().addListener { _ -> redraw() }
        heightProperty().addListener { _ -> redraw() }
    }

    /** The logical field point of ([x], [y]) in canvas coordinates. */
    fun fieldPoint(
        x: Double,
        y: Double,
    ): Vec = fieldPoint(layout, width, height, x, y)

    /** Starts the hole-in-one burst at [cup] on the current hole. */
    fun startBurst(cup: Vec) {
        burst.start(model.game, cup)
    }

    /** True while the hole-in-one burst of the current hole is on. */
    internal val isBurstActive: Boolean get() = burst.isActive(model.game)

    /** Stops following the window's render scale. */
    fun dispose() = scaleSubscription.unsubscribe()

    fun redraw() {
        if (width <= 0 || height <= 0) return
        val place = layout.fit(width, height, scene?.window?.renderScaleX ?: 1.0)
        val gc = graphicsContext2D
        gc.fill = MiniGolfPalette.SURROUND
        gc.fillRect(0.0, 0.0, width, height)
        val game = model.game
        HolePainter.paint(gc, place, game.hole, game.stepCount, game.ball, game.ballVisible)
        if (game.phase == Phase.AIMING) AimPainter.paintGhost(gc, place, game.ghostPath)
        model.aim?.let { AimPainter.paintAim(gc, place, game.ball, it.angle, it.power) }
        burst.paint(gc, place, game)
        val toPar = if (game.scores.any { it != null }) game.toPar else null
        HudPainter.paint(
            gc,
            place,
            game.holeIndex + 1,
            game.hole.par,
            hudStroke(game.phase, game.strokes, game.shotInProgress),
            toPar,
        )
        card(gc, place, game)
        // The paused overlay covers the field; no message under it.
        if (model.state != SessionState.PAUSED) message(gc, place)
    }

    private fun card(
        gc: GraphicsContext,
        place: FieldPlacement,
        game: MiniGolfGame,
    ) {
        val final = model.state == SessionState.OVER
        if (!final && game.phase != Phase.CARD) return
        val pars = game.course.map { it.par }
        ScorecardPainter.paint(gc, place, pars, game.scores, model.bestHoles, final, final && model.isNewBest)
    }

    private fun message(
        gc: GraphicsContext,
        place: FieldPlacement,
    ) {
        when (model.state) {
            SessionState.READY -> banner(gc, place, model.readyText, 9.0, READY_Y)
            SessionState.COUNTDOWN -> banner(gc, place, model.countdown.toString(), 40.0, Rules.FIELD_HEIGHT / 2)
            else -> Unit
        }
    }

    /** [text] centred horizontally at [centerY] over a dark rounded panel, so that it reads over the green. */
    private fun banner(
        gc: GraphicsContext,
        place: FieldPlacement,
        text: String,
        size: Double,
        centerY: Double,
    ) {
        val width = if (size > 20.0) size * 1.6 else text.length * size * 0.62 + 20.0
        val height = size * 1.7
        gc.inField(place) {
            fill = MiniGolfPalette.CARD_DIM
            fillRoundRect((Rules.FIELD_WIDTH - width) / 2, centerY - height / 2, width, height, 8.0, 8.0)
            font = MiniGolfPalette.font(size)
            textAlign = TextAlignment.CENTER
            textBaseline = VPos.CENTER
            fill = MiniGolfPalette.ACCENT
            fillText(text, Rules.FIELD_WIDTH / 2, centerY)
        }
    }

    private companion object {
        // Below hole 1, so that the start text hides neither tee nor cup.
        const val READY_Y = 212.0
    }
}
