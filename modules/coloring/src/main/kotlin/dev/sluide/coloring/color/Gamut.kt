package dev.sluide.coloring.color

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A color as 8-bit sRGB components 0..255. */
data class Rgb8(
    val r: Int,
    val g: Int,
    val b: Int,
    val a: Int = 255,
)

private const val JND = 0.02
private const val GAMUT_EPSILON = 0.0001

/** `true` if this color lies inside the sRGB gamut, [epsilon] being the tolerance on each component. */
fun CssColor.inSrgbGamut(epsilon: Double = 1e-4): Boolean {
    val s = toSrgb()
    return listOf(s.c0, s.c1, s.c2).all { it >= -epsilon && it <= 1 + epsilon }
}

/** Euclidean distance of two colors in OKLab (the CSS Color 4 deltaEOK). */
fun deltaEOK(
    a: CssColor,
    b: CssColor,
): Double {
    val x = a.to(ColorSpace.OKLAB)
    val y = b.to(ColorSpace.OKLAB)
    val dl = x.c0 - y.c0
    return sqrt(dl * dl + (x.c1 - y.c1) * (x.c1 - y.c1) + (x.c2 - y.c2) * (x.c2 - y.c2))
}

/** This color in sRGB with every component clamped to 0..1 (no perceptual mapping). */
fun CssColor.clipToSrgb(): CssColor {
    val s = toSrgb()
    return CssColor.srgb(s.c0.coerceIn(0.0, 1.0), s.c1.coerceIn(0.0, 1.0), s.c2.coerceIn(0.0, 1.0), alpha)
}

/**
 * This color mapped into the sRGB gamut, returned in sRGB, by the CSS Color 4 gamut-mapping algorithm: in OKLCH,
 * lightness of 1 or more gives white and 0 or less black; otherwise the chroma is reduced by binary search until
 * the clipped color is within a deltaEOK of 0.02. Colors already in the gamut keep their value (clamped by at most
 * the tolerance of [inSrgbGamut]).
 */
fun CssColor.toSrgbGamut(): CssColor {
    // Checked before the lightness clamps: a color in the gamut gives the same result either way (white, black).
    if (inSrgbGamut()) return clipToSrgb()
    val origin = to(ColorSpace.OKLCH)
    if (origin.c0 >= 1) return CssColor.srgb(1.0, 1.0, 1.0, alpha)
    if (origin.c0 <= 0) return CssColor.srgb(0.0, 0.0, 0.0, alpha)
    var current = origin
    var clipped = current.clipToSrgb()
    if (deltaEOK(clipped, current) < JND) return clipped
    var min = 0.0
    var max = origin.c1
    var minInGamut = true
    while (max - min > GAMUT_EPSILON) {
        val chroma = (min + max) / 2
        current = origin.copy(c1 = chroma)
        if (minInGamut && current.inSrgbGamut()) {
            min = chroma
            continue
        }
        clipped = current.clipToSrgb()
        val e = deltaEOK(clipped, current)
        if (e < JND) {
            if (JND - e < GAMUT_EPSILON) return clipped
            minInGamut = false
            min = chroma
        } else {
            max = chroma
        }
    }
    return clipped
}

/** This color gamut-mapped ([toSrgbGamut]) and rounded to 8-bit components. */
fun CssColor.toRgb8(): Rgb8 {
    val s = toSrgbGamut()

    fun to8(v: Double) = (v * 255).roundToInt().coerceIn(0, 255)
    return Rgb8(to8(s.c0), to8(s.c1), to8(s.c2), to8(alpha))
}
