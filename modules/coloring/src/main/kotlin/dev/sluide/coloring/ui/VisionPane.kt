package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import dev.sluide.coloring.ui.vision.VisionRow
import dev.sluide.coloring.ui.vision.visionRows
import dev.sluide.coloring.ui.vision.warning
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ChangeListener
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.GridPane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/** The current color and the contrast pair as people with color vision deficiencies see them. */
internal class VisionPane(
    private val state: ColoringState,
) {
    /** The nodes of one row; their colors and texts are updated in place. */
    private class RowView(
        val color: SimpleObjectProperty<CssColor>,
        val swatch: Swatch,
        val css: Label,
        val sample: StackPane,
        val aa: Label,
        val ratio: Label,
        val badge: Label,
    )

    private val grid = GridPane().apply { styleClass += "vision-grid" }
    private val warningLabel =
        Label().apply {
            styleClass += "vision-warning"
            isWrapText = true
            isManaged = false
            isVisible = false
        }
    private val views: List<RowView>
    private val listener = ChangeListener<Any?> { _, _, _ -> update() }

    val node: Node

    init {
        val initial = visionRows(state.current.value, state.second.value, state.currentIsText.value)
        views = initial.mapIndexed { index, row -> build(index, row) }
        val intro =
            Label(
                "How people with color-vision deficiencies see these colors " +
                    "(simulation, Machado et al. 2009, full severity).",
            ).apply {
                styleClass += "contrast-muted"
                isWrapText = true
            }
        node =
            VBox(
                6.0,
                Label("Vision").apply { styleClass += "coloring-section-title" },
                intro,
                grid,
                warningLabel,
            ).apply { styleClass += "vision-pane" }
        update()
        state.current.addListener(listener)
        state.second.addListener(listener)
        state.currentIsText.addListener(listener)
    }

    private fun build(
        index: Int,
        row: VisionRow,
    ): RowView {
        val color = SimpleObjectProperty(row.current)
        val swatch = Swatch.of(color, state, size = 36.0)
        val css = Label().apply { styleClass += "vision-css" }
        val aa = Label("Aa").apply { styleClass += "vision-sample-text" }
        val sample = StackPane(aa).apply { styleClass += "vision-sample" }
        val ratio = Label()
        val badge = Label().apply { styleClass += "contrast-badge" }
        val label =
            Label(row.label).apply {
                styleClass += "vision-row-label"
                tooltip = Tooltip(row.description)
            }
        grid.add(label, 0, index)
        grid.add(VBox(2.0, swatch, css).apply { alignment = Pos.CENTER_LEFT }, 1, index)
        grid.add(sample, 2, index)
        grid.add(VBox(2.0, ratio, badge), 3, index)
        return RowView(color, swatch, css, sample, aa, ratio, badge)
    }

    private fun update() {
        val rows = visionRows(state.current.value, state.second.value, state.currentIsText.value)
        rows.zip(views).forEach { (row, view) ->
            view.color.set(row.current)
            view.css.text = cssText(row.current)
            view.sample.background = Background(BackgroundFill(row.background.toFxColor(), null, null))
            view.aa.textFill = row.text.toFxColor()
            view.ratio.text = row.ratioText
            view.badge.text = if (row.passes) "Pass AA" else "Fail AA"
            view.badge.styleClass.removeAll("badge-pass", "badge-fail")
            view.badge.styleClass += if (row.passes) "badge-pass" else "badge-fail"
        }
        val text = warning(rows)
        warningLabel.text = text
        warningLabel.isVisible = text != null
        warningLabel.isManaged = text != null
    }

    /** Releases the listeners on the window's state and the swatches'. */
    fun dispose() {
        state.current.removeListener(listener)
        state.second.removeListener(listener)
        state.currentIsText.removeListener(listener)
        views.forEach { it.swatch.dispose() }
    }
}
