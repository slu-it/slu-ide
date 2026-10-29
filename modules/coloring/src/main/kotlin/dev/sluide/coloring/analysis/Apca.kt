package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.toSrgbGamut
import kotlin.math.abs
import kotlin.math.pow

// APCA 0.0.98G-4g constants.
private const val BLK_THRS = 0.022
private const val BLK_CLMP = 1.414
private const val NORM_BG = 0.56
private const val NORM_TXT = 0.57
private const val REV_TXT = 0.62
private const val REV_BG = 0.65
private const val SCALE_BOW = 1.14
private const val SCALE_WOB = 1.14
private const val LO_BOW_OFFSET = 0.027
private const val LO_WOB_OFFSET = 0.027
private const val DELTA_Y_MIN = 0.0005
private const val LO_CLIP = 0.1

private fun apcaLuminance(color: CssColor): Double {
    val s = color.toSrgbGamut()
    val y =
        0.2126729 * s.c0.pow(2.4) +
            0.7151522 * s.c1.pow(2.4) +
            0.0721750 * s.c2.pow(2.4)
    return if (y > BLK_THRS) y else y + (BLK_THRS - y).pow(BLK_CLMP)
}

/**
 * APCA lightness contrast Lc (about -108..106) of [text] on [background], algorithm version 0.0.98G-4g. Positive for
 * dark text on a light background, negative for light text on dark. A translucent background is flattened over
 * white, translucent text over the background. Colors are gamut-mapped to sRGB; luminance uses the simple 2.4
 * exponent. Non-finite input gives 0.
 */
fun apcaContrast(
    text: CssColor,
    background: CssColor,
): Double {
    val bg = flatten(background, CssColor.srgb(1.0, 1.0, 1.0))
    val fg = flatten(text, bg)
    val yTxt = apcaLuminance(fg)
    val yBg = apcaLuminance(bg)
    if (yBg.isNaN() || yTxt.isNaN() || abs(yBg - yTxt) < DELTA_Y_MIN) return 0.0
    val out =
        if (yBg > yTxt) {
            val sapc = (yBg.pow(NORM_BG) - yTxt.pow(NORM_TXT)) * SCALE_BOW
            if (sapc < LO_CLIP) 0.0 else sapc - LO_BOW_OFFSET
        } else {
            val sapc = (yBg.pow(REV_BG) - yTxt.pow(REV_TXT)) * SCALE_WOB
            if (sapc > -LO_CLIP) 0.0 else sapc + LO_WOB_OFFSET
        }
    return out * 100
}
