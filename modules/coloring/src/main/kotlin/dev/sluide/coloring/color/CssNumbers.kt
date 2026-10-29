package dev.sluide.coloring.color

import kotlin.math.PI
import kotlin.math.abs

/** One argument of a color function: `none`, or a number with an optional unit (`%`, `deg`, `grad`, `rad`, `turn`). */
internal data class Tok(
    val none: Boolean,
    val value: Double,
    val unit: String?,
) {
    val isPercent: Boolean get() = unit == "%"
}

/** Larger magnitudes are rejected: no color needs them and they overflow the conversions. */
private const val MAX_MAGNITUDE = 1e6

private val NUMBER = Regex("^([+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:e[+-]?\\d+)?)(%|deg|grad|rad|turn)?$")

/** Parses one argument; `null` if it is no number, percentage, angle or `none`. */
internal fun tok(text: String): Tok? {
    if (text == "none") return Tok(true, 0.0, null)
    val m = NUMBER.matchEntire(text) ?: return null
    val v = m.groupValues[1].toDoubleOrNull() ?: return null
    if (!v.isFinite() || abs(v) > MAX_MAGNITUDE) return null
    return Tok(false, v, m.groups[2]?.value)
}

/**
 * How a non-hue component maps to its coordinate: a plain number is divided by [numDiv], a percentage means
 * [pctFull] at 100%; the result is clamped to [lo]..[hi] where given.
 */
internal class Spec(
    private val numDiv: Double,
    private val pctFull: Double,
    private val lo: Double? = null,
    private val hi: Double? = null,
) {
    fun apply(t: Tok): Double? {
        val v =
            when (t.unit) {
                null -> t.value / numDiv
                "%" -> t.value / 100 * pctFull
                else -> return null
            }
        return v.coerceIn(lo ?: Double.NEGATIVE_INFINITY, hi ?: Double.POSITIVE_INFINITY)
    }
}

/** A hue in degrees, normalized to [0, 360); `null` for a percentage. */
internal fun hueDegrees(t: Tok): Double? {
    val deg =
        when (t.unit) {
            null, "deg" -> t.value
            "grad" -> t.value * 0.9
            "rad" -> t.value * 180 / PI
            "turn" -> t.value * 360
            else -> return null
        }
    return ((deg % 360) + 360) % 360
}
