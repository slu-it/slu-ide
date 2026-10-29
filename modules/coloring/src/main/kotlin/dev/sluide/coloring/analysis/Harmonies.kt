package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.normalizeHue
import dev.sluide.coloring.color.toSrgbGamut

/** The classic color harmonies of one color; the color itself is not part of the lists. All in sRGB. */
data class Harmonies(
    val complementary: List<CssColor>,
    val analogous: List<CssColor>,
    val triadic: List<CssColor>,
    val splitComplementary: List<CssColor>,
)

/**
 * The harmonies of [color]: hue rotations in OKLCH (lightness and chroma kept) by +180 (complementary), -30 and +30
 * (analogous), +120 and +240 (triadic), +150 and +210 (split complementary), gamut-mapped to sRGB. An achromatic
 * color has no hue, so every rotation equals the color (gamut-mapped).
 */
fun harmonies(color: CssColor): Harmonies {
    val origin = color.to(ColorSpace.OKLCH)

    fun rotate(degrees: Double): CssColor =
        if (origin.isHueMissing) {
            color.toSrgbGamut()
        } else {
            origin.copy(c2 = normalizeHue(origin.c2 + degrees), missing = 0).toSrgbGamut()
        }

    fun rotations(vararg degrees: Double) = degrees.map(::rotate)
    return Harmonies(
        complementary = rotations(180.0),
        analogous = rotations(-30.0, 30.0),
        triadic = rotations(120.0, 240.0),
        splitComplementary = rotations(150.0, 210.0),
    )
}
