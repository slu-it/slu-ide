package dev.sluide.coloring.color

import kotlin.math.max

/** Naive CMYK, each 0..1, without a color profile: the plain formula from sRGB. */
data class Cmyk(
    val c: Double,
    val m: Double,
    val y: Double,
    val k: Double,
)

/** [color] as naive CMYK; colors outside sRGB are gamut-mapped first. */
fun toCmyk(color: CssColor): Cmyk {
    val s = color.toSrgbGamut()
    val k = 1 - max(s.c0, max(s.c1, s.c2))
    if (k >= 1 - 1e-12) return Cmyk(0.0, 0.0, 0.0, 1.0)
    return Cmyk((1 - s.c0 - k) / (1 - k), (1 - s.c1 - k) / (1 - k), (1 - s.c2 - k) / (1 - k), k)
}

/** The sRGB color of naive [cmyk] (inverse of [toCmyk]). */
fun fromCmyk(
    cmyk: Cmyk,
    alpha: Double = 1.0,
): CssColor =
    CssColor.srgb(
        (1 - cmyk.c) * (1 - cmyk.k),
        (1 - cmyk.m) * (1 - cmyk.k),
        (1 - cmyk.y) * (1 - cmyk.k),
        alpha,
    )
