package dev.sluide.coloring.color

/**
 * The color spaces [CssColor] can be expressed in: the CSS Color 4 spaces plus HSV, which CSS lacks but color pickers
 * use. The coordinate scales are documented on [CssColor].
 *
 * @property hueIndex index (0..2) of the coordinate that is a hue angle in degrees, or `null` if the space has none
 */
enum class ColorSpace(
    val hueIndex: Int? = null,
) {
    SRGB,
    SRGB_LINEAR,
    HSL(0),
    HWB(0),
    HSV(0),
    LAB,
    LCH(2),
    OKLAB,
    OKLCH(2),
    XYZ_D65,
    XYZ_D50,
    ;

    /** `true` if one coordinate of this space is a hue angle. */
    val hasHue: Boolean get() = hueIndex != null
}
