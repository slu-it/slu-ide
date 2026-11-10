package dev.sluide.games.common

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Zoom factors relative to a game's Fit size (1.0: the size the game picks for its tab, ADR 0155); [presets] ascending,
 * include 1.0, first = [min], last = [max]. Zooming in and out steps through the presets.
 */
internal class ZoomRange(
    val presets: List<Double>,
) {
    init {
        require(presets.isNotEmpty() && presets == presets.sorted() && 1.0 in presets) { "invalid presets $presets" }
    }

    val min: Double = presets.first()
    val max: Double = presets.last()

    /**
     * [zoom] within [min] and [max], snapped to a preset it is at (so that a wheel ending at 0.9996 is exactly Fit, as
     * its label says); 1.0 when it is no number.
     */
    fun clamp(zoom: Double): Double {
        if (!zoom.isFinite()) return 1.0
        val clamped = zoom.coerceIn(min, max)
        return preset(clamped) ?: clamped
    }

    /** The next preset above [zoom], or [max]. */
    fun zoomIn(zoom: Double): Double = presets.firstOrNull { it > zoom * (1 + TOLERANCE) } ?: max

    /** The next preset below [zoom], or [min]. */
    fun zoomOut(zoom: Double): Double = presets.lastOrNull { it < zoom * (1 - TOLERANCE) } ?: min

    /** The preset [zoom] is at, or null between presets. */
    fun preset(zoom: Double): Double? = presets.firstOrNull { abs(it - zoom) <= it * TOLERANCE }

    /** `Fit` at 1.0, else [zoom] in percent, e.g. `63%`. */
    fun label(zoom: Double): String = if (abs(zoom - 1.0) <= TOLERANCE) "Fit" else "${(zoom * 100).roundToInt()}%"

    companion object {
        /** How far apart (relative) a zoom and a preset may be and still count as the same. */
        private const val TOLERANCE = 1e-3

        /** Grid and card games: they scroll above Fit. */
        val ABOVE_FIT = ZoomRange(listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0))

        /** Real-time games: they fill the tab at Fit already, and scrolling while playing is unusable. */
        val UP_TO_FIT = ZoomRange(listOf(0.25, 0.5, 0.75, 1.0))
    }
}
