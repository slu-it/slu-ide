package dev.sluide.coloring.ui.contrast

import dev.sluide.coloring.analysis.apcaContrast
import dev.sluide.coloring.analysis.contrastRatio
import dev.sluide.coloring.analysis.flatten
import dev.sluide.coloring.analysis.nearestPassing
import dev.sluide.coloring.analysis.wcagVerdicts
import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import dev.sluide.coloring.color.toRgb8
import dev.sluide.coloring.color.toSrgbGamut
import java.util.Locale
import kotlin.math.floor

/** One WCAG 2 check with its verdict. */
internal data class Badge(
    val label: String,
    val pass: Boolean,
)

/** A text color that reaches the WCAG [level] (AA = 4.5, AAA = 7); [color] and [colorText] are `null` if none does. */
internal data class Suggestion(
    val level: String,
    val target: Double,
    val color: CssColor?,
    val colorText: String?,
) {
    /** The line saying what is offered. */
    val title: String get() = "Nearest text color passing $level:"

    /** The text shown when there is no such color. */
    val noneText: String get() = "No color with this hue reaches it"
}

/** Everything the Contrast pane shows, decided without JavaFX. */
internal data class ContrastSummary(
    val text: CssColor,
    val background: CssColor,
    /** The [background] over white, as the ratio sees it; what the sample is painted with. */
    val sampleBackground: CssColor,
    val ratioText: String,
    val badges: List<Badge>,
    val apcaText: String,
    val suggestion: Suggestion?,
)

/**
 * The ratio as "4.54 : 1": two decimals, truncated and not rounded, as WCAG says 4.499 must not become 4.5. A tiny
 * epsilon keeps exact values such as 21 or 4.5 from falling to the lower hundredth through floating point noise; the
 * price is that a ratio within 1e-9 below a hundredth shows that hundredth.
 */
internal fun ratioText(ratio: Double): String = String.format(Locale.ROOT, "%.2f : 1", floor(ratio * 100 + 1e-9) / 100)

/** The APCA value as "Lc 63.1": one decimal, the sign kept. */
internal fun apcaText(lc: Double): String = String.format(Locale.ROOT, "Lc %.1f", lc)

/** The contrast of [current] and [second], [current] being the text color if [currentIsText], else the background. */
internal fun contrastView(
    current: CssColor,
    second: CssColor,
    currentIsText: Boolean,
): ContrastSummary {
    val text = if (currentIsText) current else second
    val background = if (currentIsText) second else current
    val ratio = contrastRatio(text, background)
    val v = wcagVerdicts(ratio)
    val target =
        when {
            !v.aaNormal -> "AA" to 4.5
            !v.aaaNormal -> "AAA" to 7.0
            else -> null
        }
    val suggestion =
        target?.let { (level, goal) ->
            val color = suggest(text, background, goal)
            Suggestion(level, goal, color, color?.let(::cssText))
        }
    return ContrastSummary(
        text = text,
        background = background,
        sampleBackground = flatten(background, CssColor.srgb(1.0, 1.0, 1.0)),
        ratioText = ratioText(ratio),
        badges =
            listOf(
                Badge("AA normal", v.aaNormal),
                Badge("AA large", v.aaLarge),
                Badge("AAA normal", v.aaaNormal),
                Badge("AAA large", v.aaaLarge),
                Badge("UI components", v.nonText),
            ),
        apcaText = apcaText(apcaContrast(text, background)),
        suggestion = suggestion,
    )
}

private const val LIGHTNESS_STEP = 0.002

/**
 * A text color near [text] that reaches [target] on [background] as an 8-bit sRGB color, so the color shown and
 * applied is exactly the one that passes. [nearestPassing] finds the exact lightness; rounding it to 8 bits can fall
 * just below the threshold, so the lightness is moved on, away from [text], until the rounded color passes.
 */
internal fun suggest(
    text: CssColor,
    background: CssColor,
    target: Double,
): CssColor? {
    val exact = nearestPassing(text, background, target) ?: return null

    fun snap(c: CssColor) = c.toRgb8().let { CssColor.fromRgb8(it.r, it.g, it.b, it.a) }
    snap(exact).takeIf { contrastRatio(it, background) >= target }?.let { return it }
    val oklch = exact.to(ColorSpace.OKLCH)
    val direction = if (oklch.c0 >= text.to(ColorSpace.OKLCH).c0) 1 else -1
    val hue = if (oklch.isHueMissing) 0.0 else oklch.c2
    var l = oklch.c0
    while (l > 0.0 && l < 1.0) {
        l = (l + direction * LIGHTNESS_STEP).coerceIn(0.0, 1.0)
        val candidate = snap(CssColor(ColorSpace.OKLCH, l, oklch.c1, hue, text.alpha).toSrgbGamut())
        if (contrastRatio(candidate, background) >= target) return candidate
    }
    return null
}
