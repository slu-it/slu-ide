package dev.sluide.coloring.color

/**
 * Parses color notations into a [CssColor] in the space the text is written in, so formats round-trip.
 *
 * Supported: hex, `rgb()`/`rgba()`, `hsl()`/`hsla()`, `hwb()`, `lab()`, `lch()`, `oklab()`, `oklch()`,
 * `color()` with `srgb`, `srgb-linear`, `xyz`, `xyz-d65` and `xyz-d50`, the named colors and `transparent`, and the
 * non-CSS `hsv()`/`hsb()`, `cmyk()` and `device-cmyk()`. `none` in modern syntax sets the missing bit.
 * Not supported (null): other `color()` spaces such as `display-p3`, `calc()`, `var()`, relative colors,
 * `currentcolor`.
 */
object CssColorParser {
    private val RGB = Spec(255.0, 1.0, 0.0, 1.0)
    private val ALPHA = Spec(1.0, 1.0, 0.0, 1.0)
    private val UNIT = Spec(1.0, 1.0)
    private val HUNDRED = Spec(1.0, 100.0)
    private val SATURATION = Spec(1.0, 100.0, 0.0)
    private val ALPHA00 = Spec(1.0, 100.0, 0.0, 100.0)
    private val LAB_AB = Spec(1.0, 125.0)
    private val LCH_C = Spec(1.0, 150.0, 0.0)
    private val OKLAB_AB = Spec(1.0, 0.4)
    private val OKLCH_C = Spec(1.0, 0.4, 0.0)
    private val WHITESPACE = Regex("\\s+")
    private val COLOR_SPACES =
        mapOf(
            "srgb" to ColorSpace.SRGB,
            "srgb-linear" to ColorSpace.SRGB_LINEAR,
            "xyz" to ColorSpace.XYZ_D65,
            "xyz-d65" to ColorSpace.XYZ_D65,
            "xyz-d50" to ColorSpace.XYZ_D50,
        )

    /**
     * The color in [text], or `null` if it is not a supported notation. Case and surrounding whitespace are ignored.
     * With [lenient] (for typed input) a bare hex without `#` and a trailing `;` are accepted too.
     */
    fun parse(
        text: String,
        lenient: Boolean = false,
    ): CssColor? {
        var s = text.trim().lowercase()
        if (lenient && s.endsWith(";")) s = s.dropLast(1).trim()
        if (s.isEmpty()) return null
        if (s.startsWith("#")) return hex(s.substring(1))
        if (s.endsWith(")")) {
            val open = s.indexOf('(')
            if (open <= 0) return null
            return function(s.substring(0, open), s.substring(open + 1, s.length - 1))
        }
        if (s == "transparent") return CssColor.srgb(0.0, 0.0, 0.0, 0.0)
        NamedColors.byName(s)?.let { return CssColor.fromRgb8(it.r, it.g, it.b) }
        return if (lenient) hex(s) else null
    }

    private fun hex(digits: String): CssColor? {
        if (digits.length !in intArrayOf(3, 4, 6, 8) || !digits.all { it in '0'..'9' || it in 'a'..'f' }) return null
        val full = if (digits.length <= 4) digits.map { "$it$it" }.joinToString("") else digits
        val n = (0 until full.length / 2).map { full.substring(it * 2, it * 2 + 2).toInt(16) }
        return CssColor.fromRgb8(n[0], n[1], n[2], n.getOrElse(3) { 255 })
    }

    /** The component texts, the alpha text if any, and whether the legacy comma syntax was used. */
    private class Args(
        val parts: List<String>,
        val alpha: String?,
        val comma: Boolean,
    )

    private fun splitArgs(
        inner: String,
        count: Int,
    ): Args? {
        if (',' in inner) {
            val parts = inner.split(',').map { it.trim() }
            if (parts.any { p -> p.isEmpty() || p.any { it.isWhitespace() || it == '/' } }) return null
            if (parts.size != count && parts.size != count + 1) return null
            return Args(parts.take(count), parts.getOrNull(count), true)
        }
        val toks = inner.replace("/", " / ").trim().split(WHITESPACE)
        val slashes = toks.count { it == "/" }
        if (slashes == 0) return if (toks.size == count) Args(toks, null, false) else null
        if (slashes != 1 || toks.size != count + 2 || toks[count] != "/") return null
        return Args(toks.take(count), toks.last(), false)
    }

    private fun function(
        name: String,
        inner: String,
    ): CssColor? {
        if (name == "color") return colorFunction(inner.trim())
        val args = splitArgs(inner, if (name == "cmyk" || name == "device-cmyk") 4 else 3) ?: return null
        val toks = args.parts.map { tok(it) ?: return null }
        val legacy = args.comma
        return when (name) {
            "rgb", "rgba" -> {
                val pct = toks.count { it.isPercent }
                if (legacy && (toks.any { it.none } || (pct != 0 && pct != 3))) return null
                build(ColorSpace.SRGB, args, listOf(RGB, RGB, RGB))
            }

            "hsl", "hsla" -> {
                if (legacy && !(toks[1].isPercent && toks[2].isPercent)) return null
                build(ColorSpace.HSL, args, listOf(null, SATURATION, HUNDRED))
            }

            "hsv", "hsb" -> {
                build(ColorSpace.HSV, args, listOf(null, SATURATION, HUNDRED))
            }

            "cmyk", "device-cmyk" -> {
                cmyk(args)
            }

            else -> {
                if (legacy) null else modern(name, args)
            }
        }
    }

    private fun modern(
        name: String,
        args: Args,
    ): CssColor? =
        when (name) {
            "hwb" -> build(ColorSpace.HWB, args, listOf(null, HUNDRED, HUNDRED))
            "lab" -> build(ColorSpace.LAB, args, listOf(ALPHA00, LAB_AB, LAB_AB))
            "lch" -> build(ColorSpace.LCH, args, listOf(ALPHA00, LCH_C, null))
            "oklab" -> build(ColorSpace.OKLAB, args, listOf(ALPHA, OKLAB_AB, OKLAB_AB))
            "oklch" -> build(ColorSpace.OKLCH, args, listOf(ALPHA, OKLCH_C, null))
            else -> null
        }

    private fun colorFunction(inner: String): CssColor? {
        val word = inner.takeWhile { !it.isWhitespace() }
        val space = COLOR_SPACES[word] ?: return null
        val args = splitArgs(inner.substring(word.length), 3) ?: return null
        return if (args.comma) null else build(space, args, listOf(UNIT, UNIT, UNIT))
    }

    private fun cmyk(args: Args): CssColor? {
        val v =
            args.parts.map { p ->
                tok(p)?.takeUnless { it.none }?.let(UNIT::apply)?.coerceIn(0.0, 1.0)
                    ?: return null
            }
        val alpha = args.alpha?.let { a -> tok(a)?.takeUnless { it.none }?.let(ALPHA::apply) ?: return null } ?: 1.0
        return fromCmyk(Cmyk(v[0], v[1], v[2], v[3]), alpha)
    }

    /** Builds the color; a `null` spec marks the hue. `none` is allowed in modern syntax only. */
    private fun build(
        space: ColorSpace,
        args: Args,
        specs: List<Spec?>,
    ): CssColor? {
        val values = DoubleArray(3)
        var missing = 0
        for (i in 0..2) {
            val t = tok(args.parts[i]) ?: return null
            if (t.none) {
                if (args.comma) return null
                missing = missing or (1 shl i)
            } else {
                val spec = specs[i]
                values[i] = (if (spec == null) hueDegrees(t) else spec.apply(t)) ?: return null
            }
        }
        var alpha = 1.0
        if (args.alpha != null) {
            val t = tok(args.alpha) ?: return null
            if (t.none) {
                if (args.comma) return null
                missing = missing or (1 shl CssColor.ALPHA)
                alpha = 0.0
            } else {
                alpha = ALPHA.apply(t) ?: return null
            }
        }
        return CssColor(space, values[0], values[1], values[2], alpha, missing)
    }
}
