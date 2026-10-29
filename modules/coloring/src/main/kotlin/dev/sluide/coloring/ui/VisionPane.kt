package dev.sluide.coloring.ui

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.cssText
import dev.sluide.coloring.ui.vision.VisionRow
import dev.sluide.coloring.ui.vision.visionRows
import dev.sluide.coloring.ui.vision.warning
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ChangeListener
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

/**
 * The Contrast pair (Text and Background) as people with color vision deficiencies see them. The slot views at the top
 * select the shared Contrast slots; the Vision tab remembers its own choice of them.
 */
internal class VisionPane(
    private val state: ColoringState,
) {
    private val textSlot = SlotView(state.contrastText, state, ColoringState.VISION).apply { field.id = "vision-text" }
    private val backgroundSlot =
        SlotView(state.contrastBackground, state, ColoringState.VISION).apply { field.id = "vision-background" }

    /** The nodes of one row; their colors and texts are updated in place. */
    private class RowView(
        val text: SimpleObjectProperty<CssColor>,
        val background: SimpleObjectProperty<CssColor>,
        val textSwatch: Swatch,
        val backgroundSwatch: Swatch,
        val textCss: Label,
        val backgroundCss: Label,
        val sample: StackPane,
        val aa: Label,
        val ratio: Label,
        val badge: Label,
    )

    private val rowsBox = VBox(10.0).apply { styleClass += "vision-grid" }
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
        val initial = visionRows(state.contrastText.color.value, state.contrastBackground.color.value)
        views = initial.map(::build)
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
                textSlot.node,
                backgroundSlot.node,
                intro,
                rowsBox,
                warningLabel,
            ).apply {
                styleClass += "vision-pane"
                padding = Insets(12.0)
            }
        update()
        state.contrastText.color.addListener(listener)
        state.contrastBackground.color.addListener(listener)
    }

    private fun build(row: VisionRow): RowView {
        val text = SimpleObjectProperty(row.text)
        val background = SimpleObjectProperty(row.background)
        val textSwatch = Swatch.of(text, state, size = 28.0)
        val backgroundSwatch = Swatch.of(background, state, size = 28.0)
        val textCss = Label().apply { styleClass += "vision-css" }
        val backgroundCss = Label().apply { styleClass += "vision-css" }
        val aa = Label("Aa").apply { styleClass += "vision-sample-text" }
        val sample = StackPane(aa).apply { styleClass += "vision-sample" }
        val ratio = Label()
        val badge = Label().apply { styleClass += "contrast-badge" }
        val label =
            Label(row.label).apply {
                styleClass += "vision-row-label"
                tooltip = Tooltip(row.description)
            }
        rowsBox.children +=
            VBox(
                4.0,
                label,
                FlowPane(
                    12.0,
                    6.0,
                    sample,
                    HBox(4.0, textSwatch, textCss).apply { alignment = Pos.CENTER_LEFT },
                    HBox(4.0, backgroundSwatch, backgroundCss).apply { alignment = Pos.CENTER_LEFT },
                    HBox(8.0, ratio, badge).apply { alignment = Pos.CENTER_LEFT },
                ).apply { alignment = Pos.CENTER_LEFT },
            )
        return RowView(text, background, textSwatch, backgroundSwatch, textCss, backgroundCss, sample, aa, ratio, badge)
    }

    private fun update() {
        val rows = visionRows(state.contrastText.color.value, state.contrastBackground.color.value)
        rows.zip(views).forEach { (row, view) ->
            view.text.set(row.text)
            view.background.set(row.background)
            view.textCss.text = cssText(row.text)
            view.backgroundCss.text = cssText(row.background)
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

    /** Releases the listeners on the window's state, the slot views and the swatches. */
    fun dispose() {
        state.contrastText.color.removeListener(listener)
        state.contrastBackground.color.removeListener(listener)
        textSlot.dispose()
        backgroundSlot.dispose()
        views.forEach {
            it.textSwatch.dispose()
            it.backgroundSwatch.dispose()
        }
    }
}
