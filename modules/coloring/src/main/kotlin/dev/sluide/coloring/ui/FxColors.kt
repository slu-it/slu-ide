package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.clipToSrgb
import dev.sluide.coloring.color.toRgb8
import javafx.scene.paint.Color

/** This color as an 8-bit sRGB JavaFX color with alpha, gamut-mapped if it lies outside sRGB. */
internal fun CssColor.toFxColor(): Color {
    val c = toRgb8()
    return Color.rgb(c.r, c.g, c.b, c.a / 255.0)
}

/**
 * This color as the browser paints it inside a gradient: out-of-sRGB colors are clipped per channel, not gamut-mapped
 * (the mapping of [toFxColor] is for a single color).
 */
internal fun CssColor.toFxColorClipped(): Color = clipToSrgb().toFxColor()

/** [toFxColorClipped] as a non-premultiplied ARGB int. */
internal fun CssColor.toArgbClipped(): Int {
    val c = clipToSrgb().toRgb8()
    return (c.a shl 24) or (c.r shl 16) or (c.g shl 8) or c.b
}
