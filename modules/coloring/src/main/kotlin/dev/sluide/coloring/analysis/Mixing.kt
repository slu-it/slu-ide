package dev.sluide.coloring.analysis

import dev.sluide.coloring.color.ColorSpace
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.normalizeHue
import dev.sluide.coloring.color.toSrgbGamut

/** The color spaces [colorMix] interpolates in, with their CSS keyword. */
enum class MixSpace(
    val css: String,
    internal val space: ColorSpace,
) {
    SRGB("srgb", ColorSpace.SRGB),
    OKLAB("oklab", ColorSpace.OKLAB),
    OKLCH("oklch", ColorSpace.OKLCH),
}

/**
 * CSS Color 5 `color-mix(in [space], a pa, b pb)`; [pa] and [pb] are percentages 0..100, `null` if omitted.
 *
 * Percentages: both omitted give 50/50; one omitted is 100 minus the other; a sum of 0 (or a percentage outside
 * 0..100) gives `null`; a sum below 100 multiplies the result alpha by sum/100; a sum above 100 is scaled to 100.
 * Both colors are converted to [space] (no gamut mapping). A missing component (`none` or a powerless hue) takes the
 * other color's value; missing in both stays missing. Non-hue coordinates are interpolated premultiplied by the
 * alpha, hues along the shorter arc. The result is in [space].
 */
fun colorMix(
    a: CssColor,
    pa: Double?,
    b: CssColor,
    pb: Double?,
    space: MixSpace,
): CssColor? {
    if ((pa != null && pa !in 0.0..100.0) || (pb != null && pb !in 0.0..100.0)) return null
    val p1 = pa ?: pb?.let { 100 - it } ?: 50.0
    val p2 = pb ?: pa?.let { 100 - it } ?: 50.0
    val sum = p1 + p2
    if (sum <= 0.0) return null
    val t = p2 / sum
    val alphaFactor = if (sum < 100.0) sum / 100.0 else 1.0

    val x = a.toMixSpace(space.space)
    val y = b.toMixSpace(space.space)
    val aAlphaMissing = a.isMissing(CssColor.ALPHA)
    val bAlphaMissing = b.isMissing(CssColor.ALPHA)
    val xs = doubleArrayOf(x.c0, x.c1, x.c2)
    val ys = doubleArrayOf(y.c0, y.c1, y.c2)

    // Missing components take the other color's value.
    var missing = 0
    for (i in 0..2) {
        val xm = x.isMissing(i)
        val ym = y.isMissing(i)
        if (xm && ym) {
            missing = missing or (1 shl i)
        } else if (xm) {
            xs[i] = ys[i]
        } else if (ym) {
            ys[i] = xs[i]
        }
    }
    // A missing alpha takes the other's; missing in both counts as opaque.
    val alphaA =
        if (!aAlphaMissing) {
            a.alpha
        } else if (!bAlphaMissing) {
            b.alpha
        } else {
            1.0
        }
    val alphaB =
        if (!bAlphaMissing) {
            b.alpha
        } else if (!aAlphaMissing) {
            a.alpha
        } else {
            1.0
        }

    val alpha = alphaA * (1 - t) + alphaB * t
    val hueIndex = space.space.hueIndex
    val out = DoubleArray(3)
    for (i in 0..2) {
        if (i == hueIndex) {
            var h1 = xs[i]
            var h2 = ys[i]
            val d = h2 - h1
            if (d > 180) {
                h1 += 360
            } else if (d < -180) {
                h2 += 360
            }
            out[i] = normalizeHue(h1 * (1 - t) + h2 * t)
        } else {
            val v = xs[i] * alphaA * (1 - t) + ys[i] * alphaB * t
            out[i] = if (alpha == 0.0) v else v / alpha
        }
    }
    return CssColor(space.space, out[0], out[1], out[2], alpha * alphaFactor, missing)
}

/**
 * [foreground] composited over [background] with Porter-Duff "over" on the gamma-encoded sRGB components, as
 * browsers composite. Both are gamut-mapped to sRGB first. The result alpha is
 * `a_f + a_b * (1 - a_f)`; the result is in sRGB. Two fully transparent colors give transparent black.
 */
fun flatten(
    foreground: CssColor,
    background: CssColor,
): CssColor {
    val f = foreground.toSrgbGamut()
    val b = background.toSrgbGamut()
    val ao = f.alpha + b.alpha * (1 - f.alpha)
    if (ao <= 0.0) return CssColor.srgb(0.0, 0.0, 0.0, 0.0)

    fun ch(
        cf: Double,
        cb: Double,
    ) = (cf * f.alpha + cb * b.alpha * (1 - f.alpha)) / ao
    return CssColor.srgb(ch(f.c0, b.c0), ch(f.c1, b.c1), ch(f.c2, b.c2), ao)
}

/** The kinds of coordinate CSS Color 4 section 12.4 treats as analogous across spaces. */
private enum class Analog { RED, GREEN, BLUE, LIGHTNESS, COLORFULNESS, HUE, OPPONENT_A, OPPONENT_B }

private fun analogs(space: ColorSpace): List<Analog?> =
    when (space) {
        ColorSpace.SRGB, ColorSpace.SRGB_LINEAR, ColorSpace.XYZ_D65, ColorSpace.XYZ_D50 -> {
            listOf(Analog.RED, Analog.GREEN, Analog.BLUE)
        }

        ColorSpace.HSL -> {
            listOf(Analog.HUE, Analog.COLORFULNESS, Analog.LIGHTNESS)
        }

        ColorSpace.HWB, ColorSpace.HSV -> {
            listOf(Analog.HUE, null, null)
        }

        ColorSpace.LAB, ColorSpace.OKLAB -> {
            listOf(Analog.LIGHTNESS, Analog.OPPONENT_A, Analog.OPPONENT_B)
        }

        ColorSpace.LCH, ColorSpace.OKLCH -> {
            listOf(Analog.LIGHTNESS, Analog.COLORFULNESS, Analog.HUE)
        }
    }

/**
 * This color in [target] with its missing components carried forward (CSS Color 4 section 12.4): a missing
 * component of the original marks the analogous component of [target] as missing; `to()` alone would drop it.
 */
private fun CssColor.toMixSpace(target: ColorSpace): CssColor {
    val converted = to(target)
    if (space == target) return converted
    val from = analogs(space)
    val into = analogs(target)
    var mask = converted.missing
    for (i in 0..2) {
        val kind = from[i] ?: continue
        if (!isMissing(i)) continue
        val j = into.indexOf(kind)
        if (j >= 0) mask = mask or (1 shl j)
    }
    return converted.copy(missing = mask)
}
