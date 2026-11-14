package dev.sluide.barcodes.engine

import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow

/**
 * A warning for colours scanners may not read, or null: a foreground lighter than the background, or a WCAG contrast
 * below 3:1 (the ratio rounded down to one decimal, so that 2.97 does not show as 3.0). Alpha is ignored.
 */
internal fun colorWarning(
    foreground: Int,
    background: Int,
): String? {
    val front = relativeLuminance(foreground)
    val back = relativeLuminance(background)
    if (front > back) return "Many scanners cannot read light-on-dark codes."
    val ratio = (back + 0.05) / (front + 0.05)
    if (ratio >= 3.0) return null
    return "Low contrast (%.1f:1); scanners may fail.".format(Locale.ROOT, floor(ratio * 10) / 10)
}

/** The WCAG 2 relative luminance of the RGB in [argb], 0 for black to 1 for white. */
private fun relativeLuminance(argb: Int): Double {
    fun linear(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
}
