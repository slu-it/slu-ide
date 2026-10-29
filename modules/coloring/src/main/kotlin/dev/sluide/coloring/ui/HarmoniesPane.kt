package dev.sluide.coloring.ui

import dev.sluide.coloring.analysis.Shade
import dev.sluide.coloring.analysis.cssVariablesText
import dev.sluide.coloring.analysis.harmonies
import dev.sluide.coloring.analysis.shades
import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

/** Shades, tints and color harmonies of the current color. */
internal class HarmoniesPane(
    private val state: ColoringState,
) {
    /** A swatch with its caption; the nodes stay, only the color and the caption change. */
    private inner class Slot(
        size: Double,
    ) {
        val color = SimpleObjectProperty(state.current.value)
        val swatch = Swatch.of(color, state, size)
        val caption = Label().apply { styleClass += "coloring-caption" }
        val cell =
            VBox(2.0, swatch, caption).apply {
                alignment = Pos.CENTER
                styleClass += "coloring-cell"
            }
    }

    private val shadeSlots = List(SHADE_COUNT) { Slot(SIZE) }
    private val harmonySlots = HARMONY_ROWS.map { (_, count) -> List(count + 1) { Slot(SIZE) } }

    /** The field of the variable prefix. */
    internal val prefixField = TextField("color").apply { prefColumnCount = 8 }

    /** The button copying the CSS variables. */
    internal val copyButton: Button =
        copyButton("Copy as CSS variables") { cssVariablesText(shown, prefixField.text.ifBlank { "color" }) }
    private var shown: List<Shade> = emptyList()

    /** The shades now shown. */
    internal val shownShades: List<Shade> get() = shown

    /** The shade cells marked as the current color. */
    internal val sourceCells: List<Node> get() = shadeSlots.map { it.cell }.filter { "shade-source" in it.styleClass }

    val node: Node =
        VBox(
            10.0,
            Label("Shades").apply { styleClass += "coloring-section-title" },
            HBox(6.0, *shadeSlots.map { it.cell }.toTypedArray()),
            HBox(
                6.0,
                copyButton,
                Label("Variable prefix"),
                prefixField,
            ).apply { alignment = Pos.CENTER_LEFT },
            Label("Harmonies").apply { styleClass += "coloring-section-title" },
            VBox(
                10.0,
                *HARMONY_ROWS
                    .mapIndexed { i, (title, _) ->
                        VBox(4.0, Label(title), HBox(6.0, *harmonySlots[i].map { it.cell }.toTypedArray()))
                    }.toTypedArray(),
            ),
        ).apply { padding = Insets(12.0) }

    private val subscription = state.current.subscribe { color -> show(color) }

    private fun show(color: CssColor) {
        shown = shades(color)
        shown.forEachIndexed { i, shade ->
            val slot = shadeSlots[i]
            slot.color.set(shade.color)
            slot.caption.text = shade.step.toString()
            val marked = "shade-source" in slot.cell.styleClass
            if (shade.isSource && !marked) slot.cell.styleClass += "shade-source"
            if (!shade.isSource && marked) slot.cell.styleClass -= "shade-source"
        }
        val h = harmonies(color)
        val rows = listOf(h.complementary, h.analogous, h.triadic, h.splitComplementary)
        rows.forEachIndexed { r, colors ->
            (listOf(color) + colors).forEachIndexed { i, c ->
                harmonySlots[r][i].color.set(c)
                harmonySlots[r][i].caption.text = cssText(c)
            }
        }
    }

    /** The swatches of the harmony rows, current color first in each row. */
    internal val harmonySwatches: List<Swatch> get() = harmonySlots.flatten().map { it.swatch }

    /** Releases the listeners on the window's state. */
    fun dispose() {
        subscription.unsubscribe()
        (shadeSlots + harmonySlots.flatten()).forEach { it.swatch.dispose() }
    }

    private companion object {
        const val SIZE = 36.0
        const val SHADE_COUNT = 11
        val HARMONY_ROWS = listOf("Complementary" to 1, "Analogous" to 2, "Triadic" to 2, "Split complementary" to 2)
    }
}
