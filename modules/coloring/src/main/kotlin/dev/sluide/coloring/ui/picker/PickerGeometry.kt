package dev.sluide.coloring.ui.picker

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Saturation and value, both 0..100. */
internal data class Sv(
    val s: Double,
    val v: Double,
)

/** Hue in degrees (0..360) and saturation 0..100. */
internal data class Hs(
    val h: Double,
    val s: Double,
)

/** A position in pixels. */
internal data class Xy(
    val x: Double,
    val y: Double,
)

/**
 * The mapping between positions in the picker's controls and color values; no JavaFX. Every position is clamped to
 * its control, so a drag outside it holds the value at the edge.
 */
internal object PickerGeometry {
    private const val HUE_MAX = 360.0
    private const val PERCENT = 100.0

    /** The saturation (left 0 to right 100) and value (top 100 to bottom 0) at ([x], [y]) in a square of [width] by [height]. */
    fun squareToSv(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) = Sv(fraction(x, width) * PERCENT, PERCENT - fraction(y, height) * PERCENT)

    /** The position of saturation [s] and value [v] in a square of [width] by [height]. */
    fun svToSquare(
        s: Double,
        v: Double,
        width: Double,
        height: Double,
    ) = Xy(s.coerceIn(0.0, PERCENT) / PERCENT * width, (PERCENT - v.coerceIn(0.0, PERCENT)) / PERCENT * height)

    /** The hue at [y] in a vertical strip of [height]: 0 at the top, 360 at the bottom. */
    fun stripToHue(
        y: Double,
        height: Double,
    ) = fraction(y, height) * HUE_MAX

    /** The y of [hue] in a vertical strip of [height]. */
    fun hueToStrip(
        hue: Double,
        height: Double,
    ) = hue.coerceIn(0.0, HUE_MAX) / HUE_MAX * height

    /**
     * The hue and saturation at ([dx], [dy]) from the center of a wheel of [radius]: the hue is the angle counter-
     * clockwise from the right (0 right, 90 up), the saturation the distance in percent of the radius, 100 at the rim
     * and beyond. At the center the hue is [centerHue], since it has no angle there.
     */
    fun wheelToHs(
        dx: Double,
        dy: Double,
        radius: Double,
        centerHue: Double,
    ): Hs {
        val distance = hypot(dx, dy)
        if (distance < EPSILON) return Hs(centerHue, 0.0)
        val degrees = Math.toDegrees(atan2(-dy, dx))
        return Hs((degrees + HUE_MAX) % HUE_MAX, (distance / radius).coerceAtMost(1.0) * PERCENT)
    }

    /** The offset from the center of a wheel of [radius] for [hue] and saturation [s]. */
    fun hsToWheel(
        hue: Double,
        s: Double,
        radius: Double,
    ): Xy {
        val distance = s.coerceIn(0.0, PERCENT) / PERCENT * radius
        val angle = Math.toRadians(hue)
        return Xy(distance * cos(angle), -distance * sin(angle))
    }

    /** The value at [y] in a vertical slider of [height]: 100 at the top, 0 at the bottom. */
    fun sliderToValue(
        y: Double,
        height: Double,
    ) = PERCENT - fraction(y, height) * PERCENT

    /** The y of [value] in a vertical slider of [height]. */
    fun valueToSlider(
        value: Double,
        height: Double,
    ) = (PERCENT - value.coerceIn(0.0, PERCENT)) / PERCENT * height

    /** The alpha (0..1) at [x] in a horizontal slider of [width]. */
    fun xToAlpha(
        x: Double,
        width: Double,
    ) = fraction(x, width)

    /** The x of [alpha] in a horizontal slider of [width]. */
    fun alphaToX(
        alpha: Double,
        width: Double,
    ) = alpha.coerceIn(0.0, 1.0) * width

    private const val EPSILON = 1e-9

    private fun fraction(
        position: Double,
        extent: Double,
    ) = if (extent <= 0.0) 0.0 else (position / extent).coerceIn(0.0, 1.0)
}
