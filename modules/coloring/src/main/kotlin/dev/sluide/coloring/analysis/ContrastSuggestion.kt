package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.toSrgbGamut
import kotlin.math.abs

private const val SCAN_STEP = 0.001
private const val BISECT_ROUNDS = 30

/**
 * The color closest to [foreground] in OKLCH lightness that reaches a WCAG contrast of [target] on [background].
 * Hue and chroma of the foreground are kept and the lightness is varied up and down (scan in steps of 0.001, then
 * bisection); each candidate is gamut-mapped ([toSrgbGamut]), so its chroma may shrink. The candidate with the
 * smaller lightness change wins. Returns [foreground] itself if it already passes, `null` if no lightness reaches
 * the target. Otherwise the result is in sRGB, with the foreground's alpha.
 */
fun nearestPassing(
    foreground: CssColor,
    background: CssColor,
    target: Double = 4.5,
): CssColor? = nearestPassing(foreground, target) { contrastRatio(it, background) }

/**
 * [nearestPassing] for a color whose contrast is measured by [ratio], so that the real pair is tested even when the
 * color is the background of a translucent text: `nearestPassing(background, target) { contrastRatio(text, it) }`.
 */
internal fun nearestPassing(
    foreground: CssColor,
    target: Double,
    ratio: (CssColor) -> Double,
): CssColor? {
    if (ratio(foreground) >= target) return foreground
    val origin = foreground.to(ColorSpace.OKLCH)
    val hue = if (origin.isHueMissing) 0.0 else origin.c2

    fun candidate(l: Double) = CssColor(ColorSpace.OKLCH, l, origin.c1, hue, foreground.alpha).toSrgbGamut()

    fun passes(l: Double) = ratio(candidate(l)) >= target

    fun search(direction: Int): Double? {
        // The end of the scan (white or black) is the best candidate; if it fails, no lightness in this direction passes.
        if (!passes(if (direction > 0) 1.0 else 0.0)) return null
        var failing = origin.c0
        val steps = (if (direction > 0) 1 - origin.c0 else origin.c0) / SCAN_STEP
        for (i in 1..kotlin.math.ceil(steps).toInt()) {
            val l = (origin.c0 + direction * i * SCAN_STEP).coerceIn(0.0, 1.0)
            if (passes(l)) {
                var lo = failing
                var hi = l
                repeat(BISECT_ROUNDS) {
                    val mid = (lo + hi) / 2
                    if (passes(mid)) hi = mid else lo = mid
                }
                return hi
            }
            failing = l
        }
        return null
    }

    val best =
        listOfNotNull(search(1), search(-1)).minByOrNull { abs(it - origin.c0) }
            ?: return null
    return candidate(best)
}
