package dev.sluide.fileviewers.zoom

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The zoom levels of images, diagrams (ADR 0033), documents (ADR 0083) and PDF pages (ADR 0192), as scales: 1.0 is
 * 100%. Shared by the previews and the PDF view.
 */
object ZoomSteps {
    const val MIN: Double = 0.1
    const val MAX: Double = 16.0

    /** The levels zooming in and out steps through. */
    val presets: List<Double> =
        listOf(0.1, 0.25, 0.33, 0.5, 0.67, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0, 12.0, 16.0)

    /** How far apart (relative) a scale and a preset may be and still count as the same. */
    private const val TOLERANCE = 1e-3

    /**
     * [scale] as a fixed scale: within [MIN] and [MAX], but not above [current] when that is below [MIN] (a fit of huge
     * content), so that zooming out never enlarges. As `sluide.zoomBy` in previews' `preview.js` does.
     */
    fun clamp(
        scale: Double,
        current: Double = MAX,
    ): Double = scale.coerceIn(min(MIN, current), MAX)

    /** The next preset above [scale], or [MAX]. */
    fun zoomIn(scale: Double): Double = presets.firstOrNull { it > scale * (1 + TOLERANCE) } ?: MAX

    /**
     * The next preset below [scale], or [MIN]; [scale] itself when it is below [MIN] already (a fit of huge content).
     */
    fun zoomOut(scale: Double): Double = presets.lastOrNull { it < scale * (1 - TOLERANCE) } ?: min(scale, MIN)

    /** [scale] in percent, e.g. `67%`. */
    fun label(scale: Double): String = "${(scale * 100).roundToInt()}%"
}
