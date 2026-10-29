package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.CssColor
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/** The CSS gradient functions the builder writes. */
enum class GradientType { LINEAR, RADIAL, CONIC }

/** A color stop at [position] 0..1 along the gradient. */
data class GradientStop(
    val color: CssColor,
    val position: Double,
)

/**
 * A CSS gradient. [stops] are sorted by position and at least two. [angleDeg] is the direction of a linear gradient
 * (0deg = to top, 90deg = to right) or the start angle of a conic one (`from`); a radial gradient ignores it.
 * [space] is the interpolation space; `null` leaves the CSS default (sRGB) out of the text.
 */
data class Gradient(
    val type: GradientType,
    val angleDeg: Double,
    val stops: List<GradientStop>,
    val space: MixSpace?,
)

/** A point in a box, x to the right and y down, as JavaFX and CSS count. */
data class Point(
    val x: Double,
    val y: Double,
)

/**
 * The CSS text of [gradient]. The interpolation method is written where CSS Images 4 puts it (its grammar takes it
 * before or after the direction): `linear-gradient(in oklch 90deg, …)`, `radial-gradient(circle in oklch, …)` (a
 * circle reaching the farthest corner, the CSS default size) and `conic-gradient(from 90deg in oklch, …)`.
 */
fun css(gradient: Gradient): String {
    val stopTexts = gradient.stops.map { emittedCss(it.color) }
    // A browser interpolates in Oklab when a stop is not in legacy sRGB syntax, so "no space" must then say srgb.
    val inSpace =
        (gradient.space?.css ?: if (stopTexts.all(::isLegacySrgbText)) null else "srgb")?.let { "in $it" }
    val angle = "${num(gradient.angleDeg)}deg"
    val head =
        when (gradient.type) {
            GradientType.LINEAR -> listOfNotNull(inSpace, angle)
            GradientType.RADIAL -> listOfNotNull("circle", inSpace)
            GradientType.CONIC -> listOfNotNull("from $angle", inSpace)
        }.joinToString(" ")
    val stops =
        gradient.stops.indices.joinToString(
            ", ",
        ) { "${stopTexts[it]} ${num(gradient.stops[it].position * 100)}%" }
    return "${gradient.type.name.lowercase()}-gradient($head, $stops)"
}

/** The color of [gradient] at [t] 0..1: the end stops' colors outside the stops, else mixed in the space. */
fun colorAt(
    gradient: Gradient,
    t: Double,
): CssColor {
    val stops = gradient.stops
    if (t <= stops.first().position) return stops.first().color
    if (t >= stops.last().position) return stops.last().color
    val after = stops.indexOfFirst { it.position > t }
    val a = stops[after - 1]
    val b = stops[after]
    val span = b.position - a.position
    if (span <= 0.0) return b.color
    val u = (t - a.position) / span
    return colorMix(a.color, null, b.color, u * 100, gradient.space ?: MixSpace.SRGB) ?: b.color
}

/** [n] colors (at least 2) at even steps from 0 to 1. */
fun sample(
    gradient: Gradient,
    n: Int,
): List<CssColor> {
    require(n >= 2) { "at least two samples" }
    return List(n) { colorAt(gradient, it / (n - 1.0)) }
}

/**
 * The start (0%) and end (100%) of the gradient line of a linear gradient at [angleDeg] in a [width] x [height] box,
 * as CSS defines it: through the center, so long that the corners lie on the 0% and 100% lines.
 */
fun linearEndpoints(
    angleDeg: Double,
    width: Double,
    height: Double,
): Pair<Point, Point> {
    val rad = Math.toRadians(angleDeg)
    val dx = sin(rad)
    val dy = -cos(rad)
    val half = (abs(width * dx) + abs(height * dy)) / 2
    val cx = width / 2
    val cy = height / 2
    return Point(cx - dx * half, cy - dy * half) to Point(cx + dx * half, cy + dy * half)
}

/**
 * The position 0..1 of the point ([x], [y]) in a [width] x [height] box along [gradient], as the preview draws it:
 * linear, the projection on the gradient line (not clamped); radial, the distance from the center relative to the
 * farthest corner; conic, the angle clockwise from up, minus the start angle, as a share of the full turn.
 */
fun gradientT(
    gradient: Gradient,
    x: Double,
    y: Double,
    width: Double,
    height: Double,
): Double {
    val cx = width / 2
    val cy = height / 2
    return when (gradient.type) {
        GradientType.LINEAR -> {
            val (from, to) = linearEndpoints(gradient.angleDeg, width, height)
            val dx = to.x - from.x
            val dy = to.y - from.y
            val len2 = dx * dx + dy * dy
            if (len2 <= 0.0) 0.0 else ((x - from.x) * dx + (y - from.y) * dy) / len2
        }

        GradientType.RADIAL -> {
            val radius = hypot(cx, cy)
            if (radius <= 0.0) 0.0 else hypot(x - cx, y - cy) / radius
        }

        GradientType.CONIC -> {
            val turns = (Math.toDegrees(atan2(x - cx, -(y - cy))) - gradient.angleDeg) / 360
            turns - floor(turns)
        }
    }
}

/** [gradient] with a stop at the middle of its widest gap, in the interpolated color, and the new stop's index. */
fun addStop(gradient: Gradient): Pair<Gradient, Int> {
    val stops = gradient.stops
    val gap = (1 until stops.size).maxBy { stops[it].position - stops[it - 1].position }
    val position = (stops[gap - 1].position + stops[gap].position) / 2
    val added = GradientStop(colorAt(gradient, position), position)
    return gradient.copy(stops = stops.take(gap) + added + stops.drop(gap)) to gap
}

/** [gradient] without the stop at [index]; unchanged if only two stops are left. */
fun removeStop(
    gradient: Gradient,
    index: Int,
): Gradient =
    if (gradient.stops.size <= 2) {
        gradient
    } else {
        gradient.copy(stops = gradient.stops.filterIndexed { i, _ -> i != index })
    }

/**
 * [gradient] with stop [index] moved to [position] (limited to 0..1) and the stops sorted again, so a stop can pass
 * its neighbours; with the new index of the moved stop. A stop that arrives at the position of another is put behind
 * it when it came from the left and before it when it came from the right, so it can be dragged back out.
 */
fun moveStop(
    gradient: Gradient,
    index: Int,
    position: Double,
): Pair<Gradient, Int> {
    val moved = gradient.stops[index].copy(position = position.coerceIn(0.0, 1.0))
    val others = gradient.stops.filterIndexed { i, _ -> i != index }
    val newIndex =
        if (moved.position >= gradient.stops[index].position) {
            others.count { it.position <= moved.position }
        } else {
            others.count { it.position < moved.position }
        }
    return gradient.copy(stops = others.take(newIndex) + moved + others.drop(newIndex)) to newIndex
}

/**
 * The stops to paint [gradient] with where the painter only interpolates in sRGB: the real stops (two at one position
 * make a sharp edge) and, unless the space is sRGB, [perSegment] interpolated colors inside each segment, so the
 * painted steps follow the interpolation space.
 */
fun previewStops(
    gradient: Gradient,
    perSegment: Int = 32,
): List<GradientStop> {
    val stops = gradient.stops
    val interior = if (gradient.space == null || gradient.space == MixSpace.SRGB) 0 else perSegment
    return buildList {
        add(stops.first())
        for (i in 1 until stops.size) {
            val a = stops[i - 1].position
            val b = stops[i].position
            if (b > a) {
                for (k in 1..interior) {
                    val t = a + (b - a) * k / (interior + 1)
                    add(GradientStop(colorAt(gradient, t), t))
                }
            }
            add(stops[i])
        }
    }
}

/** [gradient] with the color of stop [index] replaced. */
fun setStopColor(
    gradient: Gradient,
    index: Int,
    color: CssColor,
): Gradient = gradient.copy(stops = gradient.stops.mapIndexed { i, s -> if (i == index) s.copy(color = color) else s })

/** At most one decimal, no trailing zeros. */
private fun num(value: Double): String =
    BigDecimal(value).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

private fun isLegacySrgbText(text: String): Boolean =
    text.startsWith("#") || text.startsWith("rgb(") || text.startsWith("rgba(") || text.startsWith("hsl(")
