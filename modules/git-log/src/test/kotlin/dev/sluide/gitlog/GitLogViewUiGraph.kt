package dev.sluide.gitlog

import dev.sluide.gitlog.data.GraphRow
import dev.sluide.gitlog.data.MAX_LANES
import dev.sluide.gitlog.table.GraphCell
import dev.sluide.gitlog.table.GraphPane
import dev.sluide.gitlog.table.LANE_WIDTH
import javafx.scene.Node
import javafx.scene.shape.Circle
import javafx.scene.shape.LineTo
import javafx.scene.shape.MoveTo
import org.testfx.api.FxRobot
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import javafx.scene.shape.Path as ShapePath

// Asserts on the commit graph a [GitLogViewUiFixture] shows.

/** A line of the graph: from (x, y) to (x, y) in a colour index. */
internal data class Segment(
    val fromX: Double,
    val fromY: Double,
    val toX: Double,
    val toY: Double,
    val colour: Int,
)

/** The coloured lines [pane] draws (not its halo). */
internal fun GitLogViewUiFixture.drawn(pane: GraphPane): Set<Segment> =
    pane.childrenUnmodifiable
        .filterIsInstance<ShapePath>()
        .filter { "git-graph-edge" in it.styleClass }
        .flatMap { path ->
            val colour =
                path.styleClass
                    .single { it.matches(Regex("git-graph-edge-\\d")) }
                    .last()
                    .digitToInt()
            path.elements.chunked(2).map { (move, line) ->
                move as MoveTo
                line as LineTo
                Segment(move.x, move.y, line.x, line.y, colour)
            }
        }.toSet()

/** The lines [row] should be drawn with at [height]: all but those with both ends beyond the lane cap. */
internal fun GitLogViewUiFixture.expected(
    row: GraphRow,
    height: Double,
): Set<Segment> {
    fun x(position: Int) = (position + 0.5) * LANE_WIDTH
    val upper = (0 until row.upperEdges).map { Triple(row.upperFrom(it), row.upperTo(it), row.upperColour(it)) }
    val lower = (0 until row.lowerEdges).map { Triple(row.lowerFrom(it), row.lowerTo(it), row.lowerColour(it)) }
    return (
        upper.map { (from, to, colour) -> Segment(x(from), 0.0, x(to), height / 2, colour) to (from to to) } +
            lower.map { (from, to, colour) -> Segment(x(from), height / 2, x(to), height, colour) to (from to to) }
    ).filter { (_, ends) -> ends.first < MAX_LANES || ends.second < MAX_LANES }
        .map { it.first }
        .toSet()
}

/** Asserts that [cell] shows exactly the graph of its row, or none when it is empty. */
internal fun GitLogViewUiFixture.assertDrawn(cell: GraphCell) {
    val item = cell.item
    if (item == null) {
        assertNull(cell.graphic, "empty cell ${cell.index}")
        assertNull(cell.content.parent, "empty cell ${cell.index}")
        return
    }
    val graph = item.graph
    assertSame<Node?>(cell.content, cell.graphic)
    assertEquals(minOf(graph.width, MAX_LANES) * LANE_WIDTH, cell.graph.width, "row ${cell.index}")
    assertEquals(expected(graph, cell.height), drawn(cell.graph), "row ${cell.index}")
    val dot =
        cell.graph.childrenUnmodifiable
            .filterIsInstance<Circle>()
            .single { "git-graph-dot" in it.styleClass }
    assertEquals((graph.dot + 0.5) * LANE_WIDTH, dot.centerX, "row ${cell.index}")
    assertEquals(cell.height / 2, dot.centerY, "row ${cell.index}")
    assertTrue("git-graph-dot-${graph.colour}" in dot.styleClass, "row ${cell.index}: ${dot.styleClass}")
    assertEquals(
        1,
        dot.styleClass.count { it.startsWith("git-graph-dot-") },
        "row ${cell.index}: ${dot.styleClass}",
    )
}

internal fun GitLogViewUiFixture.graphCells(robot: FxRobot) =
    robot
        .lookup(".git-graph-cell")
        .queryAllAs(GraphCell::class.java)
        .filter { it.tableRow?.isVisible == true && it.index >= 0 }

internal fun GitLogViewUiFixture.visibleIcons(cell: GraphCell) =
    cell.labels
        .lookupAll(".git-ref-icon")
        .filter { it.isVisible && it.isManaged }
        .map { icon -> icon.styleClass.single { it.startsWith("git-ref-") && it != "git-ref-icon" } }
