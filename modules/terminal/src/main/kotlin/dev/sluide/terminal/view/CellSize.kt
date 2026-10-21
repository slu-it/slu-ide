package dev.sluide.terminal.view

import com.jediterm.core.util.TermSize
import dev.sluide.settings.FontSpec
import dev.sluide.settings.FontStyle
import javafx.scene.text.Font
import javafx.scene.text.Text
import kotlin.math.floor

/** The variants of [spec] the terminal draws with: text that is bold or italic adds that to the spec's style. */
internal class Fonts(
    val spec: FontSpec,
) {
    private val variants = mutableMapOf<Pair<Boolean, Boolean>, Font>()

    val cell = CellSize.of(of(bold = false, italic = false))

    fun of(
        bold: Boolean,
        italic: Boolean,
    ): Font =
        variants.getOrPut(bold to italic) {
            val wantBold = spec.style.bold || bold
            val wantItalic = spec.style.italic || italic
            val style = FontStyle.entries.single { it.bold == wantBold && it.italic == wantItalic }
            spec.copy(style = style).toFont()
        }
}

/** The size of one character cell of a monospaced font, and the grid of cells that fits into an area. */
internal data class CellSize(
    val width: Double,
    val height: Double,
    val baseline: Double,
) {
    fun gridSize(
        areaWidth: Double,
        areaHeight: Double,
    ) = TermSize(
        floor(areaWidth / width).toInt().coerceAtLeast(MIN_COLUMNS),
        floor(areaHeight / height).toInt().coerceAtLeast(MIN_ROWS),
    )

    companion object {
        private const val MIN_COLUMNS = 5
        private const val MIN_ROWS = 2

        fun of(font: Font): CellSize {
            val text = Text("M").apply { this.font = font }
            return CellSize(text.layoutBounds.width, text.layoutBounds.height, text.baselineOffset)
        }
    }
}
