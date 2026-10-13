package dev.sluide.terminal

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.emulator.ColorPaletteImpl
import javafx.scene.paint.Color

/** The colors a cell is painted with. */
internal data class CellColors(
    val foreground: Color,
    val background: Color,
)

/** Resolves the colors of JediTerm text styles: the 16 ANSI colors, the xterm 256-color table and true color. */
internal class TerminalColors(
    val defaultForeground: Color = Color.rgb(0xd4, 0xd4, 0xd4),
    val defaultBackground: Color = Color.rgb(0x1e, 0x1e, 0x1e),
    private val palette: ColorPalette = ColorPaletteImpl.XTERM_PALETTE,
) {
    fun of(style: TextStyle): CellColors {
        var foreground = style.foreground?.let { resolve(it, foreground = true) } ?: defaultForeground
        var background = style.background?.let { resolve(it, foreground = false) } ?: defaultBackground
        if (style.hasOption(TextStyle.Option.INVERSE)) {
            foreground = background.also { background = foreground }
        }
        if (style.hasOption(TextStyle.Option.DIM)) foreground = foreground.interpolate(background, 0.4)
        if (style.hasOption(TextStyle.Option.HIDDEN)) foreground = background
        return CellColors(foreground, background)
    }

    private fun resolve(
        color: TerminalColor,
        foreground: Boolean,
    ): Color {
        val resolved =
            when {
                !color.isIndexed -> {
                    color.toColor()
                }

                color.colorIndex < 16 -> {
                    if (foreground) palette.getForeground(color) else palette.getBackground(color)
                }

                else -> {
                    ColorPalette.getIndexedTerminalColor(color.colorIndex)?.toColor()
                }
            } ?: return if (foreground) defaultForeground else defaultBackground
        return Color.rgb(resolved.red, resolved.green, resolved.blue)
    }
}
