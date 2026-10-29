package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorFormatter
import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.CssColorParser
import dev.sluide.coloring.color.cssText
import java.math.BigDecimal

/** A percentage as CSS text without trailing zeros: 50.0 gives `50`, 12.5 gives `12.5`. */
internal fun percentText(percent: Double): String = BigDecimal.valueOf(percent).stripTrailingZeros().toPlainString()

/** The CSS `color-mix()` of [a] and [b] with [percentB] of [b]; both percentages are always written. */
internal fun colorMixText(
    a: CssColor,
    b: CssColor,
    percentB: Double,
    space: MixSpace,
): String =
    "color-mix(in ${space.css}, ${emittedCss(a)} ${percentText(100 - percentB)}%, " +
        "${emittedCss(b)} ${percentText(percentB)}%)"

/**
 * The CSS text of [color] for generated CSS (`color-mix()`): [cssText], except that an OKLCH color with missing
 * components (`none`) is written as `oklch()` with `none` in their place, so that a browser mixes it as the tool
 * does. Missing components of other spaces are not kept.
 */
internal fun emittedCss(color: CssColor): String {
    if (color.missing == 0 || color.space != ColorSpace.OKLCH) return cssText(color)
    // Format with stand-in chroma and alpha, so that the hue is not dropped as powerless and the alpha is written.
    val shown =
        color.copy(
            c1 = if (color.isMissing(1)) 0.1 else color.c1,
            alpha = if (color.isMissing(CssColor.ALPHA)) 0.5 else color.alpha,
            missing = 0,
        )
    val parts =
        ColorFormatter
            .oklch(shown)
            .removePrefix("oklch(")
            .removeSuffix(")")
            .split(" / ")
    val channels = parts[0].split(" ").mapIndexed { i, t -> if (color.isMissing(i)) "none" else t }
    val alpha = if (color.isMissing(CssColor.ALPHA)) "none" else parts.getOrNull(1)
    return "oklch(" + channels.joinToString(" ") + (alpha?.let { " / $it" } ?: "") + ")"
}

/** [color] as a browser reads the CSS text [emittedCss] gives for it, so that a shown mix equals the rendered one. */
internal fun asEmitted(color: CssColor): CssColor = CssColorParser.parse(emittedCss(color)) ?: color

/**
 * The mix of [a] and [b] (both [asEmitted]) with [percentB] of [b] in [space]; falls back to [a] if the mix is
 * undefined.
 */
internal fun mixColor(
    a: CssColor,
    b: CssColor,
    percentB: Double,
    space: MixSpace,
): CssColor = colorMix(asEmitted(a), 100 - percentB, asEmitted(b), percentB, space) ?: a

/** The nine mixes of [a] with 10 %, 20 % ... 90 % of [b] in [space]. */
internal fun mixSteps(
    a: CssColor,
    b: CssColor,
    space: MixSpace,
): List<CssColor> = (1..9).map { mixColor(a, b, it * 10.0, space) }

/** [foreground] at [opacityPercent] of its own opacity over [background], flattened to one color. */
internal fun flattenedOver(
    foreground: CssColor,
    opacityPercent: Double,
    background: CssColor,
): CssColor = flatten(foreground.copy(alpha = foreground.alpha * opacityPercent / 100.0), background)

/** The flattened color as CSS text. */
internal fun flattenText(
    foreground: CssColor,
    opacityPercent: Double,
    background: CssColor,
): String = cssText(flattenedOver(foreground, opacityPercent, background))
