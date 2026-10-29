package dev.sluide.coloring.color

/**
 * A color in one [ColorSpace] with an alpha, as CSS Color 4 describes it. Immutable; no JavaFX involved.
 *
 * Coordinate scales per space (the scales CSS writes, so a parsed notation maps one to one):
 * - [ColorSpace.SRGB], [ColorSpace.SRGB_LINEAR]: r, g, b in 0..1 (values outside mean outside the sRGB gamut)
 * - [ColorSpace.HSL]: hue in degrees, saturation and lightness 0..100
 * - [ColorSpace.HWB]: hue in degrees, whiteness and blackness 0..100
 * - [ColorSpace.HSV]: hue in degrees, saturation and value 0..100
 * - [ColorSpace.LAB] (D50): L 0..100, a and b about -160..160
 * - [ColorSpace.LCH] (D50): L 0..100, chroma 0..about 230, hue in degrees
 * - [ColorSpace.OKLAB]: L 0..1, a and b about -0.4..0.4
 * - [ColorSpace.OKLCH]: L 0..1, chroma 0..about 0.4, hue in degrees
 * - [ColorSpace.XYZ_D65], [ColorSpace.XYZ_D50]: X, Y, Z with Y = 1 for white
 *
 * [alpha] is 0..1.
 *
 * Missing components: CSS carries `none` and powerless components (the hue of a gray) forward when colors are mixed
 * or interpolated. [missing] is a bit mask for that: bit i is coordinate `ci` (0..2), bit 3 the alpha. A missing
 * coordinate is stored as 0 and all conversion math treats it as 0, as the spec says. The parser sets the bits for
 * `none`; interpolation (`color-mix()`, gradients) reads them. [to] into another space returns a mask of 0, except
 * that a hue which is powerless in the result (achromatic: HSL and HSV saturation 0, HWB whiteness plus blackness of
 * 100 or more, LCH chroma below 0.0015, OKLCH chroma below 4e-6) is stored as 0 with its bit set. [to] into the
 * same space keeps the mask.
 *
 * The constructor keeps the values as given; [to] (also into the same space) returns hues in [0, 360).
 */
data class CssColor(
    val space: ColorSpace,
    val c0: Double,
    val c1: Double,
    val c2: Double,
    val alpha: Double = 1.0,
    val missing: Int = 0,
) {
    /** `true` if the component [index] (0..2 for the coordinates, [ALPHA] for the alpha) is missing. */
    fun isMissing(index: Int): Boolean = missing and (1 shl index) != 0

    /** `true` if this space has a hue and it is missing (`none` or powerless). */
    val isHueMissing: Boolean get() = space.hueIndex?.let(::isMissing) ?: false

    /** This color expressed in [target]; no gamut mapping, so coordinates may leave the usual ranges. */
    fun to(target: ColorSpace): CssColor = convert(this, target)

    /** This color in sRGB, not gamut-mapped: coordinates outside 0..1 mean outside the gamut. */
    fun toSrgb(): CssColor = to(ColorSpace.SRGB)

    companion object {
        /** The [missing] bit index of the alpha. */
        const val ALPHA = 3

        /** An sRGB color with components 0..1. */
        fun srgb(
            r: Double,
            g: Double,
            b: Double,
            a: Double = 1.0,
        ): CssColor = CssColor(ColorSpace.SRGB, r, g, b, a)

        /** An sRGB color from 8-bit components 0..255; [a8] is the alpha 0..255. */
        fun fromRgb8(
            r: Int,
            g: Int,
            b: Int,
            a8: Int = 255,
        ): CssColor = srgb(r / 255.0, g / 255.0, b / 255.0, a8 / 255.0)
    }
}
