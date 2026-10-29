package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.toSrgbGamut

/** The color-vision deficiencies [simulate] can show. */
enum class Deficiency { PROTANOPIA, DEUTERANOPIA, TRITANOPIA, ACHROMATOPSIA }

private val PROTAN =
    arrayOf(
        doubleArrayOf(0.152286, 1.052583, -0.204868),
        doubleArrayOf(0.114503, 0.786281, 0.099216),
        doubleArrayOf(-0.003882, -0.048116, 1.051998),
    )
private val DEUTAN =
    arrayOf(
        doubleArrayOf(0.367322, 0.860646, -0.227968),
        doubleArrayOf(0.280085, 0.672501, 0.047413),
        doubleArrayOf(-0.011820, 0.042940, 0.968881),
    )
private val TRITAN =
    arrayOf(
        doubleArrayOf(1.255528, -0.076749, -0.178779),
        doubleArrayOf(-0.078411, 0.930809, 0.147602),
        doubleArrayOf(0.004733, 0.691367, 0.303900),
    )

/**
 * How [color] looks with [deficiency], in sRGB with the alpha kept. The color is gamut-mapped and linearised; the
 * three dichromacies apply the Machado et al. 2009 matrices at severity 1.0 on linear RGB and clip to 0..1;
 * achromatopsia maps the linear luminance (WCAG weights) to a gray.
 */
fun simulate(
    color: CssColor,
    deficiency: Deficiency,
): CssColor {
    val lin = color.toSrgbGamut().to(ColorSpace.SRGB_LINEAR)
    val out =
        when (deficiency) {
            Deficiency.PROTANOPIA -> {
                apply(PROTAN, lin)
            }

            Deficiency.DEUTERANOPIA -> {
                apply(DEUTAN, lin)
            }

            Deficiency.TRITANOPIA -> {
                apply(TRITAN, lin)
            }

            Deficiency.ACHROMATOPSIA -> {
                val y = 0.2126 * lin.c0 + 0.7152 * lin.c1 + 0.0722 * lin.c2
                doubleArrayOf(y, y, y)
            }
        }
    return CssColor(
        ColorSpace.SRGB_LINEAR,
        out[0].coerceIn(0.0, 1.0),
        out[1].coerceIn(0.0, 1.0),
        out[2].coerceIn(0.0, 1.0),
        color.alpha,
    ).to(ColorSpace.SRGB)
}

private fun apply(
    m: Array<DoubleArray>,
    c: CssColor,
) = DoubleArray(3) { m[it][0] * c.c0 + m[it][1] * c.c1 + m[it][2] * c.c2 }
