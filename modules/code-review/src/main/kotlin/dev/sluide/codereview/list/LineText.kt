package dev.sluide.codereview.list

import dev.sluide.fileviewers.HighlightSpan
import javafx.geometry.Orientation
import javafx.scene.layout.Region
import javafx.scene.shape.Path
import javafx.scene.text.Text
import javafx.scene.text.TextFlow

/**
 * The text of one line: a [TextFlow] with a `Text` per token (the token's style class) and per unstyled part, which
 * wraps at the width it is given. Ranges of columns can be marked in named layers ([setRanges]): each layer is a
 * [Path] behind the text with the layer's name as style class, redrawn after every layout. Layers added first are
 * drawn first.
 */
internal class LineText(
    private val text: String,
    tokens: List<HighlightSpan>,
) : Region() {
    private val flow = TextFlow()
    private val layers = LinkedHashMap<String, List<IntRange>>()
    private val paths = HashMap<String, Path>()
    private var styled = false

    init {
        styleClass += "review-line-text"
        flow.styleClass += "review-text-flow"
        fillFlow(tokens)
        children += flow
    }

    /** Marks [ranges] (first and last column, inclusive) in the layer [layer]; no ranges clear it. */
    fun setRanges(
        layer: String,
        ranges: List<IntRange>,
    ) {
        if (ranges.isEmpty()) layers.remove(layer) else layers[layer] = ranges
        requestLayout()
    }

    override fun getContentBias(): Orientation = Orientation.HORIZONTAL

    // A small width, so that the row never asks for more than the viewport: the text wraps instead (Flowless lays a
    // cell out at the larger of the viewport and the cells' preferred widths).
    override fun computePrefWidth(height: Double): Double = snappedLeftInset() + snappedRightInset() + MIN_WIDTH

    override fun computeMinWidth(height: Double): Double = computePrefWidth(height)

    override fun computePrefHeight(width: Double): Double {
        val inner = if (width < 0) -1.0 else maxOf(1.0, width - snappedLeftInset() - snappedRightInset())
        return snappedTopInset() + flow.prefHeight(inner) + snappedBottomInset()
    }

    override fun computeMinHeight(width: Double): Double = computePrefHeight(width)

    override fun layoutChildren() {
        val left = snappedLeftInset()
        val inner = maxOf(1.0, width - left - snappedRightInset())
        flow.resizeRelocate(left, snappedTopInset(), inner, flow.prefHeight(inner))
        // The fonts the wrapping depends on come from the stylesheet; once is enough, later changes re-apply it.
        if (!styled && scene != null) {
            flow.applyCss()
            styled = true
        }
        flow.layout()
        updateLayers()
    }

    private fun updateLayers() {
        val stale = paths.keys.filter { it !in layers }
        for (layer in stale) paths.remove(layer)?.let { children.remove(it) }
        for ((layer, ranges) in layers) {
            val path =
                paths.getOrPut(layer) {
                    Path().apply {
                        styleClass += layer
                        isManaged = false
                        isMouseTransparent = true
                    }
                }
            // Behind the text, in the order the layers were added.
            if (path.parent == null) children.add(paths.size - 1, path)
            path.layoutX = flow.layoutX
            path.layoutY = flow.layoutY
            path.elements.setAll(
                ranges
                    .filter { it.first < text.length && it.first <= it.last }
                    .flatMap { flow.rangeShape(it.first, minOf(it.last + 1, text.length)).toList() },
            )
        }
    }

    private fun fillFlow(tokens: List<HighlightSpan>) {
        var at = 0
        for (span in tokens) {
            val start = span.start.coerceIn(at, text.length)
            val end = span.end.coerceIn(start, text.length)
            if (start > at) flow.children += plain(text.substring(at, start))
            if (end > start) {
                flow.children += Text(text.substring(start, end)).apply { styleClass += span.style.styleClass }
            }
            at = end
        }
        if (at < text.length) flow.children += plain(text.substring(at))
        // An empty line is still one line high.
        if (text.isEmpty()) flow.children += plain(" ")
    }

    private fun plain(part: String) = Text(part).apply { styleClass += "review-plain" }

    private companion object {
        const val MIN_WIDTH = 40.0
    }
}
