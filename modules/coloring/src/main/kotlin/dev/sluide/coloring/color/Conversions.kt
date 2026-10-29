package dev.sluide.coloring.color

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

private const val ACHROMATIC_LCH = 0.0015
private const val ACHROMATIC_OKLCH = 4e-6
private const val ACHROMATIC_RGB = 1e-9

private typealias Matrix = Array<DoubleArray>

// CSS Color 4 sample code matrices; inverses are computed so that round trips are exact.
private val LIN_SRGB_TO_XYZ: Matrix =
    arrayOf(
        doubleArrayOf(506752.0 / 1228815, 87881.0 / 245763, 12673.0 / 70218),
        doubleArrayOf(87098.0 / 409605, 175762.0 / 245763, 12673.0 / 175545),
        doubleArrayOf(7918.0 / 409605, 87881.0 / 737289, 1001167.0 / 1053270),
    )
private val XYZ_TO_LIN_SRGB: Matrix = invert(LIN_SRGB_TO_XYZ)

private val D65_TO_D50: Matrix =
    arrayOf(
        doubleArrayOf(1.0479297925449969, 0.022946870601609652, -0.05019226628920524),
        doubleArrayOf(0.02962780877005599, 0.9904344267538799, -0.017073799063418826),
        doubleArrayOf(-0.009243040646204504, 0.015055191490298152, 0.7518742814281371),
    )
private val D50_TO_D65: Matrix = invert(D65_TO_D50)

private val XYZ_TO_LMS: Matrix =
    arrayOf(
        doubleArrayOf(0.8190224379967030, 0.3619062600528904, -0.1288737815209879),
        doubleArrayOf(0.0329836539323885, 0.9292868615863434, 0.0361446663506424),
        doubleArrayOf(0.0481771893596242, 0.2642395317527308, 0.6335478284694309),
    )
private val LMS_TO_XYZ: Matrix = invert(XYZ_TO_LMS)
private val LMS_TO_OKLAB: Matrix =
    arrayOf(
        doubleArrayOf(0.2104542683093140, 0.7936177747023054, -0.0040720430116193),
        doubleArrayOf(1.9779985324311684, -2.4285922420485799, 0.4505937096174110),
        doubleArrayOf(0.0259040424655478, 0.7827717124575296, -0.8086757549230774),
    )
private val OKLAB_TO_LMS: Matrix = invert(LMS_TO_OKLAB)

private val D50_WHITE = doubleArrayOf(0.3457 / 0.3585, 1.0, (1 - 0.3457 - 0.3585) / 0.3585)
private const val EPSILON = 216.0 / 24389
private const val KAPPA = 24389.0 / 27

private fun invert(m: Matrix): Matrix {
    val (a, b, c) = m[0].toList()
    val (d, e, f) = m[1].toList()
    val (g, h, i) = m[2].toList()
    val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    return arrayOf(
        doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
        doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
        doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det),
    )
}

private fun mul(
    m: Matrix,
    v: DoubleArray,
) = DoubleArray(3) { r -> m[r][0] * v[0] + m[r][1] * v[1] + m[r][2] * v[2] }

/** [h] in degrees as a value in [0, 360). */
internal fun normalizeHue(h: Double): Double {
    val r = h % 360.0
    val positive = if (r < 0) r + 360.0 else r
    // A tiny negative remainder plus 360 rounds to exactly 360.0.
    return if (positive >= 360.0) {
        0.0
    } else {
        positive
    }
}

private fun gammaEncode(v: Double): Double {
    val a = abs(v)
    return if (a <= 0.0031308) 12.92 * v else sign(v) * (1.055 * a.pow(1 / 2.4) - 0.055)
}

private fun gammaDecode(v: Double): Double {
    val a = abs(v)
    return if (a <= 0.04045) v / 12.92 else sign(v) * ((a + 0.055) / 1.055).pow(2.4)
}

private fun labF(t: Double) = if (t > EPSILON) cbrt(t) else (KAPPA * t + 16) / 116

private fun xyz50ToLab(xyz: DoubleArray): DoubleArray {
    val f = DoubleArray(3) { labF(xyz[it] / D50_WHITE[it]) }
    return doubleArrayOf(116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2]))
}

private fun labToXyz50(lab: DoubleArray): DoubleArray {
    val f1 = (lab[0] + 16) / 116
    val f0 = lab[1] / 500 + f1
    val f2 = f1 - lab[2] / 200
    val x = if (f0 * f0 * f0 > EPSILON) f0 * f0 * f0 else (116 * f0 - 16) / KAPPA
    val y = if (lab[0] > KAPPA * EPSILON) f1 * f1 * f1 else lab[0] / KAPPA
    val z = if (f2 * f2 * f2 > EPSILON) f2 * f2 * f2 else (116 * f2 - 16) / KAPPA
    return doubleArrayOf(x * D50_WHITE[0], y * D50_WHITE[1], z * D50_WHITE[2])
}

private fun xyz65ToOklab(xyz: DoubleArray): DoubleArray {
    val lms = mul(XYZ_TO_LMS, xyz)
    return mul(LMS_TO_OKLAB, doubleArrayOf(cbrt(lms[0]), cbrt(lms[1]), cbrt(lms[2])))
}

private fun oklabToXyz65(lab: DoubleArray): DoubleArray {
    val lms = mul(OKLAB_TO_LMS, lab)
    return mul(LMS_TO_XYZ, doubleArrayOf(lms[0] * lms[0] * lms[0], lms[1] * lms[1] * lms[1], lms[2] * lms[2] * lms[2]))
}

private fun toPolar(
    lab: DoubleArray,
    threshold: Double,
): DoubleArray {
    val chroma = hypot(lab[1], lab[2])
    val hue = if (chroma < threshold) 0.0 else normalizeHue(Math.toDegrees(atan2(lab[2], lab[1])))
    return doubleArrayOf(lab[0], chroma, hue)
}

private fun fromPolar(lch: DoubleArray): DoubleArray {
    val rad = Math.toRadians(lch[2])
    return doubleArrayOf(lch[0], lch[1] * cos(rad), lch[1] * sin(rad))
}

private fun hueOf(
    c: DoubleArray,
    max: Double,
    d: Double,
): Double {
    if (d < ACHROMATIC_RGB) return 0.0
    val h =
        when (max) {
            c[0] -> (c[1] - c[2]) / d + (if (c[1] < c[2]) 6 else 0)
            c[1] -> (c[2] - c[0]) / d + 2
            else -> (c[0] - c[1]) / d + 4
        }
    return normalizeHue(h * 60)
}

/** Gamma-encoded sRGB (0..1) to [h, s, l] with s, l 0..100. */
private fun srgbToHsl(c: DoubleArray): DoubleArray {
    val mx = max(c[0], max(c[1], c[2]))
    val mn = min(c[0], min(c[1], c[2]))
    val d = mx - mn
    val l = (mx + mn) / 2
    var s = if (d < ACHROMATIC_RGB || l == 0.0 || l == 1.0) 0.0 else (mx - l) / min(l, 1 - l)
    var h = hueOf(c, mx, d)
    if (s < 0) {
        // Out-of-gamut colors with l outside 0..1 (csswg-drafts#9222): negative saturation flips the hue instead.
        s = -s
        h = normalizeHue(h + 180)
    }
    return doubleArrayOf(h, s * 100, l * 100)
}

private fun hslToSrgb(hsl: DoubleArray): DoubleArray {
    val h = normalizeHue(hsl[0])
    val s = hsl[1] / 100
    val l = hsl[2] / 100
    val a = s * min(l, 1 - l)
    val offsets = intArrayOf(0, 8, 4)
    return DoubleArray(3) { i ->
        val k = (offsets[i] + h / 30) % 12
        l - a * max(-1.0, min(k - 3, min(9 - k, 1.0)))
    }
}

private fun srgbToHwb(c: DoubleArray): DoubleArray {
    val w = min(c[0], min(c[1], c[2]))
    val b = 1 - max(c[0], max(c[1], c[2]))
    return doubleArrayOf(srgbToHsl(c)[0], w * 100, b * 100)
}

private fun hwbToSrgb(hwb: DoubleArray): DoubleArray {
    val w = hwb[1] / 100
    val b = hwb[2] / 100
    if (w + b >= 1) {
        val gray = w / (w + b)
        return doubleArrayOf(gray, gray, gray)
    }
    val rgb = hslToSrgb(doubleArrayOf(hwb[0], 100.0, 50.0))
    return DoubleArray(3) { rgb[it] * (1 - w - b) + w }
}

private fun srgbToHsv(c: DoubleArray): DoubleArray {
    val mx = max(c[0], max(c[1], c[2]))
    val d = mx - min(c[0], min(c[1], c[2]))
    val s = if (mx == 0.0 || d < ACHROMATIC_RGB) 0.0 else d / mx
    return doubleArrayOf(hueOf(c, mx, d), s * 100, mx * 100)
}

private fun hsvToSrgb(hsv: DoubleArray): DoubleArray {
    val s = hsv[1] / 100
    val v = hsv[2] / 100
    val l = v * (1 - s / 2)
    val sl = if (l == 0.0 || l == 1.0) 0.0 else (v - l) / min(l, 1 - l)
    return hslToSrgb(doubleArrayOf(hsv[0], sl * 100, l * 100))
}

private fun isSrgbFamily(space: ColorSpace) =
    space == ColorSpace.SRGB || space == ColorSpace.HSL || space == ColorSpace.HWB || space == ColorSpace.HSV

private fun toSrgbVec(c: CssColor): DoubleArray {
    val v = doubleArrayOf(c.c0, c.c1, c.c2)
    return when (c.space) {
        ColorSpace.SRGB -> v
        ColorSpace.HSL -> hslToSrgb(v)
        ColorSpace.HWB -> hwbToSrgb(v)
        ColorSpace.HSV -> hsvToSrgb(v)
        else -> error("${c.space} is not an sRGB-based space")
    }
}

private fun fromSrgbVec(
    space: ColorSpace,
    v: DoubleArray,
): DoubleArray =
    when (space) {
        ColorSpace.SRGB -> v
        ColorSpace.HSL -> srgbToHsl(v)
        ColorSpace.HWB -> srgbToHwb(v)
        ColorSpace.HSV -> srgbToHsv(v)
        else -> error("$space is not an sRGB-based space")
    }

private fun toXyz65(c: CssColor): DoubleArray {
    val v = doubleArrayOf(c.c0, c.c1, c.c2)
    return when (c.space) {
        ColorSpace.XYZ_D65 -> {
            v
        }

        ColorSpace.XYZ_D50 -> {
            mul(D50_TO_D65, v)
        }

        ColorSpace.SRGB_LINEAR -> {
            mul(LIN_SRGB_TO_XYZ, v)
        }

        ColorSpace.LAB -> {
            mul(D50_TO_D65, labToXyz50(v))
        }

        ColorSpace.LCH -> {
            mul(D50_TO_D65, labToXyz50(fromPolar(v)))
        }

        ColorSpace.OKLAB -> {
            oklabToXyz65(v)
        }

        ColorSpace.OKLCH -> {
            oklabToXyz65(fromPolar(v))
        }

        ColorSpace.SRGB, ColorSpace.HSL, ColorSpace.HWB, ColorSpace.HSV -> {
            val srgb = toSrgbVec(c)
            mul(LIN_SRGB_TO_XYZ, DoubleArray(3) { gammaDecode(srgb[it]) })
        }
    }
}

private fun fromXyz65(
    space: ColorSpace,
    xyz: DoubleArray,
): DoubleArray =
    when (space) {
        ColorSpace.XYZ_D65 -> {
            xyz
        }

        ColorSpace.XYZ_D50 -> {
            mul(D65_TO_D50, xyz)
        }

        ColorSpace.SRGB_LINEAR -> {
            mul(XYZ_TO_LIN_SRGB, xyz)
        }

        ColorSpace.LAB -> {
            xyz50ToLab(mul(D65_TO_D50, xyz))
        }

        ColorSpace.LCH -> {
            toPolar(xyz50ToLab(mul(D65_TO_D50, xyz)), ACHROMATIC_LCH)
        }

        ColorSpace.OKLAB -> {
            xyz65ToOklab(xyz)
        }

        ColorSpace.OKLCH -> {
            toPolar(xyz65ToOklab(xyz), ACHROMATIC_OKLCH)
        }

        ColorSpace.SRGB, ColorSpace.HSL, ColorSpace.HWB, ColorSpace.HSV -> {
            val lin = mul(XYZ_TO_LIN_SRGB, xyz)
            fromSrgbVec(space, DoubleArray(3) { gammaEncode(lin[it]) })
        }
    }

internal fun convert(
    color: CssColor,
    target: ColorSpace,
): CssColor {
    if (color.space == target) return withNormalizedHue(color)
    val out =
        if (isSrgbFamily(color.space) && isSrgbFamily(target)) {
            fromSrgbVec(target, toSrgbVec(color))
        } else {
            fromXyz65(target, toXyz65(color))
        }
    return withNormalizedHue(CssColor(target, out[0], out[1], out[2], color.alpha))
}

/** Whether the hue of a color in [space] carries no information: it is achromatic. */
private fun isHuePowerless(
    space: ColorSpace,
    c1: Double,
    c2: Double,
): Boolean =
    when (space) {
        ColorSpace.HSL, ColorSpace.HSV -> abs(c1) <= ACHROMATIC_RGB * 100
        ColorSpace.HWB -> c1 + c2 >= 100.0
        ColorSpace.LCH -> c1 < ACHROMATIC_LCH
        ColorSpace.OKLCH -> c1 < ACHROMATIC_OKLCH
        else -> false
    }

/** [color] with its hue in [0, 360), or 0 and marked missing when powerless; unchanged if it has no hue. */
private fun withNormalizedHue(color: CssColor): CssColor {
    val index = color.space.hueIndex ?: return color
    val hue = listOf(color.c0, color.c1, color.c2)[index]
    val powerless = isHuePowerless(color.space, color.c1, color.c2)
    val newHue = if (powerless) 0.0 else normalizeHue(hue)
    val newMissing = if (powerless) color.missing or (1 shl index) else color.missing
    if (newHue == hue && newMissing == color.missing) return color
    return when (index) {
        0 -> color.copy(c0 = newHue, missing = newMissing)
        else -> color.copy(c2 = newHue, missing = newMissing)
    }
}
