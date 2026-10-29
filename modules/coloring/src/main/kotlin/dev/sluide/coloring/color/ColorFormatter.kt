package dev.sluide.coloring.color

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Writes colors as text. Decimals per component are the fewest that keep the 8-bit sRGB round trip through
 * [CssColorParser] for every 8-bit color (checked by a property test): HSL, HSV and CMYK 1 decimal (CMYK in
 * percent), HWB 1, Lab and LCH 2 (hue 2), OKLab and OKLCH lightness as a percentage with 2 decimals, a, b and
 * chroma 5, hue 2 (fewer decimals of a, b and chroma fail the round trip for channels near 0, where the sRGB
 * curve is linear and a channel step is tiny in OKLab), alpha 3. Legacy `hsl()` and `hsv()` carry the
 * alpha as a fourth argument (`hsla()` for HSL), `cmyk()` too. Trailing zeros are trimmed.
 */
internal object ColorFormatter {
    fun text(
        color: CssColor,
        format: ColorFormat,
    ): String? =
        when (format) {
            ColorFormat.HEX -> hex(color)
            ColorFormat.RGB -> rgb(color)
            ColorFormat.HSL -> hsl(color)
            ColorFormat.NAME -> name(color)
            ColorFormat.HWB -> hwb(color)
            ColorFormat.LAB -> lab(color)
            ColorFormat.LCH -> lch(color)
            ColorFormat.OKLAB -> oklab(color)
            ColorFormat.OKLCH -> oklch(color)
            ColorFormat.HSV -> hsv(color)
            ColorFormat.CMYK -> cmyk(color)
        }

    fun hex(color: CssColor): String {
        val c = color.toRgb8()
        val base = "#%02x%02x%02x".format(c.r, c.g, c.b)
        return if (c.a < 255) base + "%02x".format(c.a) else base
    }

    private fun rgb(color: CssColor): String {
        val c = color.toRgb8()
        val alpha = alphaOrNull(color)
        return if (alpha == null) "rgb(${c.r}, ${c.g}, ${c.b})" else "rgba(${c.r}, ${c.g}, ${c.b}, $alpha)"
    }

    private fun hsl(color: CssColor): String {
        val s = color.toSrgbGamut().to(ColorSpace.HSL)
        val args = "${hue(s.c0, 1)}, ${num(s.c1, 1)}%, ${num(s.c2, 1)}%"
        val alpha = alphaOrNull(color)
        return if (alpha == null) "hsl($args)" else "hsla($args, $alpha)"
    }

    private fun name(color: CssColor): String? {
        val c = color.toRgb8()
        if (c.a == 0 && c.r == 0 && c.g == 0 && c.b == 0) return "transparent"
        return NamedColors.nameOf(c)
    }

    private fun hwb(color: CssColor): String {
        val s = color.toSrgbGamut().to(ColorSpace.HWB)
        return "hwb(${hue(s.c0, 1)} ${num(s.c1, 1)}% ${num(s.c2, 1)}%${slashAlpha(color)})"
    }

    private fun lab(color: CssColor): String {
        val s = color.to(ColorSpace.LAB)
        return "lab(${num(s.c0, 2)} ${num(s.c1, 2)} ${num(s.c2, 2)}${slashAlpha(color)})"
    }

    private fun lch(color: CssColor): String {
        val s = color.to(ColorSpace.LCH)
        return "lch(${num(s.c0, 2)} ${num(s.c1, 2)} ${hue(s.c2, 2)}${slashAlpha(color)})"
    }

    private fun oklab(color: CssColor): String {
        val s = color.to(ColorSpace.OKLAB)
        return "oklab(${num(s.c0 * 100, 2)}% ${num(s.c1, 5)} ${num(s.c2, 5)}${slashAlpha(color)})"
    }

    fun oklch(color: CssColor): String {
        val s = color.to(ColorSpace.OKLCH)
        return "oklch(${num(s.c0 * 100, 2)}% ${num(s.c1, 5)} ${hue(s.c2, 2)}${slashAlpha(color)})"
    }

    private fun hsv(color: CssColor): String {
        val s = color.toSrgbGamut().to(ColorSpace.HSV)
        val args = "${hue(s.c0, 1)}, ${num(s.c1, 1)}%, ${num(s.c2, 1)}%"
        val alpha = alphaOrNull(color)
        return if (alpha == null) "hsv($args)" else "hsv($args, $alpha)"
    }

    private fun cmyk(color: CssColor): String {
        val c = toCmyk(color)
        val args = listOf(c.c, c.m, c.y, c.k).joinToString(", ") { num(it * 100, 1) + "%" }
        val alpha = alphaOrNull(color)
        return if (alpha == null) "cmyk($args)" else "cmyk($args, $alpha)"
    }

    /** The alpha text if it is below 1 after rounding, else `null`. */
    private fun alphaOrNull(color: CssColor): String? =
        if (color.toRgb8().a == 255) null else num(color.alpha.coerceIn(0.0, 1.0), 3).takeIf { it != "1" }

    private fun slashAlpha(color: CssColor): String = alphaOrNull(color)?.let { " / $it" } ?: ""

    /** A hue in [0, 360); a missing or powerless hue is stored as 0 by [CssColor.to]. */
    private fun hue(
        degrees: Double,
        decimals: Int,
    ): String {
        val wrapped = ((degrees % 360) + 360) % 360
        val rounded = BigDecimal(wrapped).setScale(decimals, RoundingMode.HALF_UP)
        return if (rounded >= BigDecimal(360)) "0" else plain(rounded)
    }

    private fun num(
        value: Double,
        decimals: Int,
    ): String = plain(BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP))

    private fun plain(value: BigDecimal): String =
        if (value.signum() == 0) "0" else value.stripTrailingZeros().toPlainString().lowercase(Locale.ROOT)
}
