package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.toSrgbGamut
import kotlin.math.abs

/** One step of a shade scale: [step] is 50, 100..900 or 950; [isSource] marks the input color itself. */
data class Shade(
    val step: Int,
    val color: CssColor,
    val isSource: Boolean,
)

private val STEPS = listOf(50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950)

// Tailwind-like OKLCH lightness per step.
private val LIGHTNESS = listOf(0.971, 0.936, 0.885, 0.808, 0.704, 0.637, 0.577, 0.505, 0.444, 0.396, 0.258)

// Bell-ish chroma weight per step: pale and very dark steps are less saturated.
private val CHROMA_WEIGHT = listOf(0.15, 0.3, 0.5, 0.75, 0.92, 1.0, 1.0, 0.9, 0.75, 0.6, 0.45)

private const val MIN_GAP = 0.001

/**
 * An 11-step scale (50, 100, ..., 900, 950) around [color], Tailwind-like: each step has a fixed OKLCH lightness
 * (.971 for 50 down to .258 for 950). The step whose lightness is nearest the color's is the color itself
 * (`isSource`, the exact input). The other steps keep the color's hue; their chroma is the color's chroma scaled by
 * the step's bell-shaped weight relative to the source step's weight, then gamut-mapped to sRGB. Achromatic colors
 * give grays. If the source lightness would break the strictly decreasing order with a neighbour, the neighbouring
 * lightness values are moved away from the source (by at least 0.001) to keep it. Shades are in sRGB, except the
 * source, which is returned unchanged. Non-finite lightness, chroma or hue count as 0, so no NaN is produced.
 */
fun shades(color: CssColor): List<Shade> {
    val converted = color.to(ColorSpace.OKLCH)

    fun finite(v: Double) = if (v.isFinite()) v else 0.0
    val origin = converted.copy(c0 = finite(converted.c0), c1 = finite(converted.c1), c2 = finite(converted.c2))
    val source = LIGHTNESS.indices.minByOrNull { abs(LIGHTNESS[it] - origin.c0) } ?: 0
    val lightness = LIGHTNESS.toDoubleArray()
    lightness[source] = origin.c0
    for (i in source - 1 downTo 0) lightness[i] = maxOf(lightness[i], lightness[i + 1] + MIN_GAP)
    for (i in source + 1 until STEPS.size) lightness[i] = minOf(lightness[i], lightness[i - 1] - MIN_GAP)

    val hue = if (origin.isHueMissing) 0.0 else origin.c2
    return STEPS.indices.map { i ->
        if (i == source) {
            Shade(STEPS[i], color, true)
        } else {
            val chroma = if (origin.isHueMissing) 0.0 else origin.c1 * CHROMA_WEIGHT[i] / CHROMA_WEIGHT[source]
            val shade = CssColor(ColorSpace.OKLCH, lightness[i].coerceIn(0.0, 1.0), chroma, hue, color.alpha)
            Shade(STEPS[i], shade.toSrgbGamut(), false)
        }
    }
}
