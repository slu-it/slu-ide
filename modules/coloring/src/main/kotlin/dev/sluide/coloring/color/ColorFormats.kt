package dev.sluide.coloring.color

/** The notations a color can be shown in. [label] is what the UI prints. */
enum class ColorFormat(
    val label: String,
) {
    HEX("HEX"),
    RGB("RGB"),
    HSL("HSL"),
    NAME("Name"),
    HWB("HWB"),
    LAB("Lab"),
    LCH("LCH"),
    OKLAB("OKLab"),
    OKLCH("OKLCH"),
    HSV("HSV/HSB"),
    CMYK("CMYK"),
}

/**
 * A color written in [format]. [approximate] is `true` if the color lies outside sRGB and the text is the
 * gamut-mapped color (only the sRGB-based formats can be approximate).
 */
data class FormattedColor(
    val format: ColorFormat,
    val text: String,
    val approximate: Boolean,
)

/** A titled group of [entries] for display. */
data class FormatGroup(
    val title: String,
    val entries: List<FormattedColor>,
)

/** The closest CSS named color: [distance] is the deltaEOK, [exact] if the 8-bit sRGB values are equal. */
data class NamedMatch(
    val name: String,
    val distance: Double,
    val exact: Boolean,
)

/** `true` if this color lies outside the sRGB gamut. */
val CssColor.outOfSrgb: Boolean get() = !inSrgbGamut()

private val SRGB_BASED =
    setOf(
        ColorFormat.HEX,
        ColorFormat.RGB,
        ColorFormat.HSL,
        ColorFormat.NAME,
        ColorFormat.HWB,
        ColorFormat.HSV,
        ColorFormat.CMYK,
    )

/** [color] in [format]; `null` only for [ColorFormat.NAME] without an exact named color. */
fun format(
    color: CssColor,
    format: ColorFormat,
): FormattedColor? {
    val text = ColorFormatter.text(color, format) ?: return null
    return FormattedColor(format, text, format in SRGB_BASED && color.outOfSrgb)
}

/** All notations of [color] in display groups: "CSS", "Modern CSS" and "Design tools". */
fun formatGroups(color: CssColor): List<FormatGroup> {
    fun group(
        title: String,
        vararg formats: ColorFormat,
    ) = FormatGroup(title, formats.mapNotNull { format(color, it) })
    return listOf(
        group("CSS", ColorFormat.HEX, ColorFormat.RGB, ColorFormat.HSL, ColorFormat.NAME),
        group("Modern CSS", ColorFormat.HWB, ColorFormat.LAB, ColorFormat.LCH, ColorFormat.OKLAB, ColorFormat.OKLCH),
        group("Design tools", ColorFormat.HSV, ColorFormat.CMYK),
    )
}

/** The closest CSS named color by deltaEOK, alpha ignored; exact when the 8-bit sRGB values are equal. */
fun nearestNamed(color: CssColor): NamedMatch {
    if (!color.outOfSrgb) NamedColors.nameOf(color.toRgb8().copy(a = 255))?.let { return NamedMatch(it, 0.0, true) }
    val opaque = color.copy(alpha = 1.0, missing = color.missing and (1 shl CssColor.ALPHA).inv())
    val best =
        NamedColors.all
            .map { (name, c) -> name to deltaEOK(opaque, CssColor.fromRgb8(c.r, c.g, c.b)) }
            .minBy { it.second }
    return NamedMatch(best.first, best.second, false)
}

/**
 * The shortest faithful CSS text of [color] for generated CSS (`color-mix()`, gradients): hex (8 digits when
 * the alpha is below 1) inside the sRGB gamut, else the `oklch()` text. A color with missing components (`none`) is
 * written as `oklch()` with `none` in their place, so that a browser mixes it as the tool does.
 */
fun cssText(color: CssColor): String =
    when {
        color.missing != 0 && color.space == ColorSpace.OKLCH -> oklchKeepingNone(color)
        color.outOfSrgb -> ColorFormatter.oklch(color)
        else -> ColorFormatter.hex(color)
    }

private fun oklchKeepingNone(color: CssColor): String {
    val parts =
        ColorFormatter
            .oklch(color)
            .removePrefix("oklch(")
            .removeSuffix(")")
            .split(" / ")
    val channels = parts[0].split(" ").mapIndexed { i, t -> if (color.isMissing(i)) "none" else t }
    val alpha = if (color.isMissing(CssColor.ALPHA)) "none" else parts.getOrNull(1)
    return "oklch(" + channels.joinToString(" ") + (alpha?.let { " / $it" } ?: "") + ")"
}
