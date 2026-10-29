package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.toRgb8
import javafx.scene.paint.Color

/** This color as an 8-bit sRGB JavaFX color with alpha, gamut-mapped if it lies outside sRGB. */
internal fun CssColor.toFxColor(): Color {
    val c = toRgb8()
    return Color.rgb(c.r, c.g, c.b, c.a / 255.0)
}
