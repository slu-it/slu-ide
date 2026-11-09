package dev.sluide.games.pipepuzzle.ui

import dev.sluide.games.pipepuzzle.Pos

/**
 * The timing of the board's two animations, on a clock of milliseconds that the caller supplies: the quarter turn of
 * one tile ([turnMillis]) and the flow of the win, which shows one more layer every [layerMillis]. Pure.
 */
internal class BoardAnimation(
    private val turnMillis: Long = TURN_MILLIS,
    private val layerMillis: Long = LAYER_MILLIS,
) {
    private var turnPos: Pos? = null
    private var turnStart = 0L
    private var turnClockwise = true
    private var flowStart = 0L
    private var flowLayers = 0

    /** Starts turning the tile at [pos]; a turn that still runs is finished at once. */
    fun startTurn(
        pos: Pos,
        clockwise: Boolean,
        now: Long,
    ) {
        turnPos = pos
        turnStart = now
        turnClockwise = clockwise
    }

    /** Starts the flow over [layers] layers; the first one (the source) shows at once. */
    fun startFlow(
        layers: Int,
        now: Long,
    ) {
        flowStart = now
        flowLayers = layers
    }

    /** The tile that is turning at [now], or null. */
    fun turningAt(now: Long): Pos? = turnPos.takeIf { now - turnStart < turnMillis }

    /** The angle of the turning tile in degrees: -90 (clockwise) or +90 shrinking to 0; 0 when none turns. */
    fun turnAngleAt(now: Long): Double {
        if (turningAt(now) == null) return 0.0
        val progress = ((now - turnStart).coerceAtLeast(0).toDouble() / turnMillis).coerceAtMost(1.0)
        return (if (turnClockwise) -QUARTER else QUARTER) * (1.0 - progress)
    }

    /** How many layers the flow shows at [now]; null when no flow runs (none started, or complete). */
    fun layersAt(now: Long): Int? {
        val shown = 1 + (now - flowStart).coerceAtLeast(0) / layerMillis
        return if (flowLayers > 0 && shown < flowLayers) shown.toInt() else null
    }

    fun isRunning(now: Long): Boolean = turningAt(now) != null || layersAt(now) != null

    /** Ends the flow at once. */
    fun finishFlow() {
        flowLayers = 0
    }

    /** Ends both animations at once. */
    fun finish() {
        turnPos = null
        flowLayers = 0
    }

    companion object {
        const val TURN_MILLIS = 100L
        const val LAYER_MILLIS = 40L
        private const val QUARTER = 90.0
    }
}
