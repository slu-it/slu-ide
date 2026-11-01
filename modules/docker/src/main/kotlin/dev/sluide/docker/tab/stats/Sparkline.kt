package dev.sluide.docker.tab.stats

import javafx.scene.control.Tooltip
import javafx.scene.layout.Pane
import javafx.scene.shape.Polyline

/**
 * A small line chart of one or two series over the last [SPARKLINE_SAMPLES] samples, scaled to a shared maximum. The
 * colours are looked-up colours of the theme (accent, text), so it reads in light and dark.
 */
internal class Sparkline(
    seriesCount: Int = 1,
    /** What the lines are, for a tooltip (two lines need it). */
    legend: String? = null,
) : Pane() {
    private val lines =
        List(seriesCount) { index ->
            Polyline().apply {
                styleClass += "sparkline-line"
                style =
                    "-fx-stroke: ${if (index == 0) "-fx-accent" else "-fx-mark-color"}; " +
                    "-fx-stroke-width: 1.5; -fx-fill: transparent;"
            }
        }
    private var series: List<List<Double>> = emptyList()
    private var max = 0.0

    init {
        styleClass += "sparkline"
        // A baseline, so that an empty chart still shows where it is.
        style = "-fx-border-color: transparent transparent -fx-text-box-border transparent;"
        prefHeight = HEIGHT
        minHeight = HEIGHT
        maxHeight = HEIGHT
        children.addAll(lines)
        legend?.let { Tooltip.install(this, Tooltip(it)) }
        widthProperty().addListener { _, _, _ -> redraw() }
    }

    /** Shows [series] (one list per line) with [max] at the top. */
    fun update(
        series: List<List<Double>>,
        max: Double,
    ) {
        this.series = series
        this.max = max
        redraw()
    }

    private fun redraw() {
        lines.forEachIndexed { index, line ->
            line.points.setAll(
                sparklinePoints(series.getOrElse(index) { emptyList() }, SPARKLINE_SAMPLES, width, HEIGHT - 2, max),
            )
            line.layoutY = 1.0
        }
    }

    private companion object {
        const val HEIGHT = 36.0
    }
}
