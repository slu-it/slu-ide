package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.toSrgbGamut

private val WHITE = CssColor.srgb(1.0, 1.0, 1.0)

/**
 * WCAG 2 relative luminance (0..1) of [color]: gamut-mapped to sRGB, linearised with the sRGB transfer function
 * (threshold 0.04045, as CSS and the WCAG errata), weighted 0.2126 / 0.7152 / 0.0722. The alpha is ignored.
 */
fun relativeLuminance(color: CssColor): Double {
    val lin = color.toSrgbGamut().to(ColorSpace.SRGB_LINEAR)
    return 0.2126 * lin.c0 + 0.7152 * lin.c1 + 0.0722 * lin.c2
}

/**
 * WCAG 2 contrast ratio (1..21) of [foreground] on [background]. A translucent background is flattened over white
 * (the document), then a translucent foreground over that background.
 */
fun contrastRatio(
    foreground: CssColor,
    background: CssColor,
): Double {
    val bg = flatten(background, WHITE)
    val fg = flatten(foreground, bg)
    val l1 = relativeLuminance(fg)
    val l2 = relativeLuminance(bg)
    return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
}

/**
 * The WCAG 2 verdicts for one contrast [ratio]: AA needs 4.5 (3 for large text), AAA 7 (4.5 for large text),
 * non-text UI components 3. The unrounded ratio is compared, as WCAG demands.
 */
data class WcagVerdicts(
    val ratio: Double,
    val aaNormal: Boolean,
    val aaLarge: Boolean,
    val aaaNormal: Boolean,
    val aaaLarge: Boolean,
    val nonText: Boolean,
)

/** The [WcagVerdicts] of [ratio]. */
fun wcagVerdicts(ratio: Double): WcagVerdicts =
    WcagVerdicts(
        ratio = ratio,
        aaNormal = ratio >= 4.5,
        aaLarge = ratio >= 3.0,
        aaaNormal = ratio >= 7.0,
        aaaLarge = ratio >= 4.5,
        nonText = ratio >= 3.0,
    )

/** The [WcagVerdicts] of [foreground] on [background]. */
fun wcagVerdicts(
    foreground: CssColor,
    background: CssColor,
): WcagVerdicts = wcagVerdicts(contrastRatio(foreground, background))
