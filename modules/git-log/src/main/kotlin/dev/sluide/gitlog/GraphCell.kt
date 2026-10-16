package dev.sluide.gitlog

import javafx.scene.control.TableCell
import javafx.scene.layout.Region
import javafx.scene.shape.Circle
import javafx.scene.shape.LineTo
import javafx.scene.shape.MoveTo
import javafx.scene.shape.Path
import javafx.scene.shape.PathElement
import javafx.scene.shape.Rectangle

/** The width of a lane of the commit graph, in pixels. */
internal const val LANE_WIDTH = 14.0

private const val DOT_RADIUS = 4.0

/** How much wider than a line its halo in selected rows is (`git-log.css`), in pixels. */
private const val HALO_WIDTH = 2.5

/**
 * The subject of a commit with its part of the commit graph left of it (ADR 0036); the text starts right after the
 * row's own graph. The graph spans the cell's full height, so the lines of neighbouring rows meet.
 */
internal class GraphCell : TableCell<GitLogRow, GitLogRow>() {
    internal val graph = GraphPane()

    init {
        styleClass += "git-graph-cell"
    }

    override fun updateItem(
        item: GitLogRow?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        if (empty || item == null) {
            text = null
            graphic = null
            graph.row = null
        } else {
            text = item.commit.subject
            graph.row = item.graph
            graphic = graph
        }
    }

    override fun layoutChildren() {
        super.layoutChildren()
        if (graphic === graph) {
            // The skin centres the graphic at its preferred height; the graph takes the whole height instead.
            graph.layoutY = 0.0
            graph.resize(graph.prefWidth(-1.0), height)
            graph.draw()
        }
    }
}

/**
 * Draws a [GraphRow] as one path per colour and a dot; colours come from the style classes (`git-log.css`). At most
 * [MAX_LANES] positions are drawn: lines with one end beyond them are cut off at the right edge, dots there left out.
 *
 * Underneath, the same lines and dot again as a wider halo, which `git-log.css` shows only in selected rows, so that
 * every lane colour stands out against the selection bar.
 */
internal class GraphPane : Region() {
    private val edges = Array(GRAPH_COLOURS) { colour -> Path().apply { styleClass += "git-graph-edge-$colour" } }
    private val dot = Circle(DOT_RADIUS)
    internal val halo = Path().apply { styleClass += "git-graph-halo" }
    private val haloDot = Circle(DOT_RADIUS + HALO_WIDTH / 2).apply { styleClass += "git-graph-halo-dot" }

    /** The row drawn; the shapes are rebuilt by [draw] only when it or the height changed. */
    var row: GraphRow? = null
        set(value) {
            if (field === value) return
            field = value
            val lanes = minOf(value?.width ?: 0, MAX_LANES)
            prefWidth = lanes * LANE_WIDTH
            minWidth = prefWidth
            maxWidth = prefWidth
            drawn = false
            // The cell may not need a layout itself (same subject, same width); this one does.
            requestLayout()
        }

    private var drawn = false
    private var drawnHeight = -1.0

    init {
        styleClass += "git-graph"
        minHeight = 0.0
        prefHeight = 0.0
        isMouseTransparent = true
        for (path in edges) {
            path.styleClass += "git-graph-edge"
            path.fill = null
        }
        halo.fill = null
        children.addAll(halo, haloDot)
        children.addAll(*edges)
        children += dot
        clip =
            Rectangle().also {
                it.widthProperty().bind(widthProperty())
                it.heightProperty().bind(heightProperty())
            }
    }

    override fun layoutChildren() = draw()

    /** Rebuilds the shapes for the current [row] and height, if they changed since the last time. */
    fun draw() {
        val row = row
        if (drawn && drawnHeight == height) return
        drawn = true
        drawnHeight = height
        val elements = Array(GRAPH_COLOURS) { ArrayList<PathElement>() }
        val haloElements = ArrayList<PathElement>()
        val middle = height / 2

        fun line(
            from: Int,
            fromY: Double,
            to: Int,
            toY: Double,
            colour: Int,
        ) {
            // The clip cuts off lines with one end beyond the drawn positions.
            if (from >= MAX_LANES && to >= MAX_LANES) return
            elements[colour] += MoveTo(x(from), fromY)
            elements[colour] += LineTo(x(to), toY)
            haloElements += MoveTo(x(from), fromY)
            haloElements += LineTo(x(to), toY)
        }
        if (row != null) {
            for (edge in 0 until row.upperEdges) {
                line(row.upperFrom(edge), 0.0, row.upperTo(edge), middle, row.upperColour(edge))
            }
            for (edge in 0 until row.lowerEdges) {
                line(row.lowerFrom(edge), middle, row.lowerTo(edge), height, row.lowerColour(edge))
            }
        }
        for (colour in 0 until GRAPH_COLOURS) {
            val path = edges[colour].elements
            if (elements[colour].isNotEmpty() || path.isNotEmpty()) path.setAll(elements[colour])
        }
        halo.elements.setAll(haloElements)
        // The halo dot's visibility comes from CSS; beyond the drawn positions the clip hides it.
        dot.isVisible = row != null && row.dot < MAX_LANES
        if (row != null) {
            dot.centerX = x(row.dot)
            dot.centerY = middle
            haloDot.centerX = dot.centerX
            haloDot.centerY = middle
            dot.styleClass.setAll("git-graph-dot", "git-graph-dot-${row.colour}")
        }
    }

    private fun x(position: Int) = (position + 0.5) * LANE_WIDTH
}
