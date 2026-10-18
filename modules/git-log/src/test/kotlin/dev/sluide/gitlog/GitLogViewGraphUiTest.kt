package dev.sluide.gitlog

import dev.sluide.git.GitCommit
import dev.sluide.gitlog.data.GRAPH_COLOURS
import dev.sluide.gitlog.data.GraphLayout
import dev.sluide.gitlog.data.MAX_LANES
import dev.sluide.gitlog.table.GraphCell
import dev.sluide.gitlog.table.GraphPane
import dev.sluide.gitlog.table.LANE_WIDTH
import dev.sluide.gitlog.table.SUBJECT_MIN_WIDTH
import dev.sluide.gitlog.table.column
import dev.sluide.gitlog.table.refGroups
import dev.sluide.gitlog.table.refLabelText
import dev.sluide.gitlog.table.refStyleClass
import javafx.application.ColorScheme
import javafx.scene.control.Label
import javafx.scene.effect.DropShadow
import javafx.scene.paint.Paint
import javafx.scene.text.Text
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import javafx.scene.shape.Path as ShapePath

/** UI tests of the git log view: the commit graph and the ref labels. Needs a display, see the `uiTest` Gradle task. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class GitLogViewGraphUiTest : GitLogViewUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    @Test
    fun `draws the commit graph left of the subject, in the colours of the theme`(robot: FxRobot) {
        fun commit(
            hash: String,
            vararg parents: String,
        ) = GitCommit(hash, parents.toList(), "A", "a@example.com", Instant.EPOCH, "Subject $hash")
        history = listOf(commit("m", "b", "f"), commit("f", "a"), commit("b", "a"), commit("a"))
        openAndExpand(robot)

        val cells = robot.lookup(".git-graph-cell").queryAllAs(GraphCell::class.java).filter { it.item != null }
        val merge = cells.single { it.item.commit.hash == "m" }
        assertEquals(2, merge.item.graph.width)
        assertEquals(2 * LANE_WIDTH, merge.graph.width)
        assertEquals(merge.height, merge.graph.height, "the graph spans the row")
        assertEquals("Subject m", merge.text)
        assertEquals("Subject m", merge.subject.text)
        assertTrue(
            merge.subject.boundsInParent.minX >= merge.graph.boundsInParent.maxX,
            "the text starts after the graph",
        )
        assertTrue(
            merge
                .lookupAll(".text")
                .filterIsInstance<Text>()
                .filter { it.isVisible && it.text == "Subject m" }
                .size ==
                1,
            "the subject is shown once",
        )
        // The lane continues down, the merged one opens to the right; nothing above the first commit.
        val edges = merge.graph.childrenUnmodifiable.filterIsInstance<ShapePath>()
        assertEquals(2, edges.single { "git-graph-edge-0" in it.styleClass }.elements.size)
        assertEquals(2, edges.single { "git-graph-edge-1" in it.styleClass }.elements.size)

        // The fork row is only one lane wide below, but two above.
        val fork = cells.single { it.item.commit.hash == "a" }
        assertEquals(2 * LANE_WIDTH, fork.graph.width)

        fun laneColour(scheme: ColorScheme): Paint {
            robot.interact { view.scene.preferences.colorScheme = scheme }
            WaitForAsyncUtils.waitForFxEvents()
            return edges.single { "git-graph-edge-0" in it.styleClass }.stroke
        }
        val light = laneColour(ColorScheme.LIGHT)
        val dark = laneColour(ColorScheme.DARK)
        assertNotEquals(light, dark)
        assertEquals(light, laneColour(ColorScheme.LIGHT))
    }

    @Test
    fun `selected rows draw a halo in the table's background colour under the graph`(robot: FxRobot) {
        history = listOf(commit("m", "b", "f"), commit("f", "a"), commit("b", "a"), commit("a"))
        openAndExpand(robot)
        robot.interact { view.table.selectionModel.select(1) }
        WaitForAsyncUtils.waitForFxEvents()

        val cells = graphCells(robot).filter { it.item != null }.associateBy { it.index }
        val selected = cells.getValue(1)
        val halo = selected.graph.halo
        assertTrue(halo.isVisible)
        // Modena: -fx-box-border, -fx-control-inner-background
        assertEquals(
            view.table.background.fills
                .last()
                .fill,
            halo.stroke,
            "the table's background",
        )
        assertEquals(drawn(selected.graph).size * 2, halo.elements.size, "under every line")
        val haloDot = selected.graph.childrenUnmodifiable.single { "git-graph-halo-dot" in it.styleClass }
        assertTrue(haloDot.isVisible)
        for (index in listOf(0, 2, 3)) {
            assertFalse(
                cells
                    .getValue(index)
                    .graph.halo.isVisible,
                "row $index",
            )
            assertFalse(
                cells
                    .getValue(index)
                    .graph.childrenUnmodifiable
                    .single { "git-graph-halo-dot" in it.styleClass }
                    .isVisible,
                "row $index",
            )
        }
    }

    @Test
    fun `lines with one end beyond 30 lanes are drawn up to the edge, those with both ends are left out`(
        robot: FxRobot,
    ) {
        val layout = GraphLayout()
        repeat(36) { layout.next("t$it", listOf("p$it")) }
        val merge = layout.next("p3", listOf("q", "p35"))
        val root = layout.next("p0", emptyList())

        fun x(position: Int) = (position + 0.5) * LANE_WIDTH

        robot.interact {
            for (row in listOf(merge, root)) {
                val pane = GraphPane()
                pane.row = row
                pane.resize(pane.prefWidth(-1.0), 24.0)
                pane.draw()
                assertEquals(MAX_LANES * LANE_WIDTH, pane.width)
                assertEquals(expected(row, 24.0), drawn(pane))
                assertTrue(drawn(pane).none { it.fromX > x(MAX_LANES - 1) && it.toX > x(MAX_LANES - 1) })
            }
        }
        assertTrue(Segment(x(3), 12.0, x(35), 24.0, 35 % GRAPH_COLOURS) in expected(merge, 24.0), "the merge line")
        assertTrue(Segment(x(30), 12.0, x(29), 24.0, 30 % GRAPH_COLOURS) in expected(root, 24.0), "the shifting lane")
    }

    @Test
    fun `recycled cells draw the graph of their new row`(robot: FxRobot) {
        // Branches merged now and then, so that the rows' graphs differ.
        history =
            (1..1200).map { i ->
                val parents =
                    listOfNotNull(
                        if (i <
                            1200
                        ) {
                            "h${i + 1}"
                        } else {
                            null
                        },
                        if (i % 5 == 0 && i < 1190) "h${i + 3 + i % 4}" else null,
                    )
                commit("h$i", *parents.toTypedArray())
            }
        openAndExpand(robot)
        WaitForAsyncUtils.waitForFxEvents()

        val before = graphCells(robot).associateWith { it.item?.commit?.hash }
        robot.interact { for (cell in graphCells(robot)) assertDrawn(cell) }
        for (target in listOf(203, 37, 451)) {
            robot.interact { view.table.scrollTo(target) }
            WaitForAsyncUtils.waitForFxEvents()
            robot.interact { for (cell in graphCells(robot)) assertDrawn(cell) }
        }
        assertTrue(
            before.any { (cell, hash) ->
                cell.item != null && cell.item.commit.hash != hash
            },
            "cells were reused",
        )
    }

    @Test
    fun `cells emptied by a reload with fewer rows show no graph`(robot: FxRobot) {
        history = listOf(commit("m", "b", "f"), commit("f", "a"), commit("b", "a"), commit("a"))
        openAndExpand(robot)
        robot.interact { assertEquals(4, graphCells(robot).count { it.item != null }) }

        history = listOf(commit("x", "y"), commit("y"))
        robot.interact { view.refresh() }
        eventually { assertEquals(listOf("x", "y"), view.table.items.map { it.commit.hash }) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            val cells = graphCells(robot)
            assertEquals(2, cells.count { it.item != null })
            assertTrue(cells.any { it.index in 2..3 && it.item == null })
            for (cell in cells) assertDrawn(cell)
        }
    }

    @Test
    fun `shows ref labels at the right end of the subject, none on rows without refs`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        hideDetails(robot)
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            val cells = graphCells(robot).filter { it.item != null }.associateBy { it.item.commit.hash }
            val first = cells.getValue("h1")
            assertTrue(first.labels.isVisible)
            assertEquals("origin & main +1", first.labels.text.text)
            assertEquals(listOf("git-ref-head", "git-ref-local", "git-ref-remote", "git-ref-tag"), visibleIcons(first))
            val labels = first.labels.boundsInParent
            assertTrue(labels.minX >= first.subject.boundsInParent.maxX, "right of the subject")
            assertEquals(first.content.width, labels.maxX, 1.0, "at the right end")
            assertTrue(labels.width <= first.content.width / 3 + 0.5, "at most a third")

            val tagged = cells.getValue("h3")
            assertEquals("v0.9", tagged.labels.text.text)
            assertEquals(listOf("git-ref-tag"), visibleIcons(tagged))

            val plain = cells.getValue("h2")
            assertFalse(plain.labels.isVisible)
            assertEquals("Subject 2", (plain.subject.lookup(".text") as Text).text, "not cut off")
            assertEquals("Subject 1", (first.subject.lookup(".text") as Text).text, "not cut off")
        }

        // A narrower column: the labels take a third of it, their text is ellipsized.
        robot.interact { view.scene.window.width = 560.0 }
        try {
            // macOS resizes the window, and lays out the cells, some time later.
            eventually {
                val first = graphCells(robot).single { it.item?.commit?.hash == "h1" }
                assertTrue(first.subject.width >= SUBJECT_MIN_WIDTH - 0.5, "${first.subject.width}")
                assertEquals(first.content.width / 3, first.labels.boundsInParent.width, 1.0)
                val shown = (first.labels.text.lookup(".text") as Text).text
                assertTrue(shown.endsWith("...") && shown.length > 3 && shown != first.labels.text.text, shown)
            }
        } finally {
            // The stage is shared by the tests.
            robot.interact { view.scene.window.width = 800.0 }
        }
    }

    @Test
    fun `recycled cells replace their labels`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        WaitForAsyncUtils.waitForFxEvents()
        val before = graphCells(robot).associateWith { it.item?.commit?.hash }

        for (target in listOf(30, 0, 39)) {
            robot.interact { view.table.scrollTo(target) }
            WaitForAsyncUtils.waitForFxEvents()
            robot.interact {
                for (cell in graphCells(robot).filter { it.item != null }) {
                    val expected = someRefs.at(cell.item.commit.hash)
                    assertEquals(expected.isNotEmpty(), cell.labels.isVisible, "row ${cell.index}")
                    assertEquals(expected, cell.labels.refs, "row ${cell.index}")
                    val text = refLabelText(refGroups(expected, someRefs.currentBranch))
                    assertEquals(text, cell.labels.text.text, "row ${cell.index}")
                    assertEquals(expected.map { refStyleClass(it.kind) }.distinct(), visibleIcons(cell))
                }
            }
        }
        assertTrue(
            before.any { (cell, hash) ->
                cell.item != null && cell.item.commit.hash != hash
            },
            "cells were reused",
        )
    }

    @Test
    fun `selected rows outline the ref icons in the table's background colour`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        robot.interact { view.table.selectionModel.select(0) }
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            val cells = graphCells(robot).filter { it.item != null }.associateBy { it.item.commit.hash }
            // Modena: -fx-box-border, -fx-control-inner-background
            val background =
                view.table.background.fills
                    .last()
                    .fill
            val selected = cells.getValue("h1").labels.lookupAll(".git-ref-icon")
            assertEquals(4, selected.size)
            for (icon in selected) assertEquals(background, (icon.effect as DropShadow).color, "$icon")
            for (icon in cells.getValue("h3").labels.lookupAll(".git-ref-icon")) assertNull(icon.effect, "unselected")
        }
    }

    @Test
    fun `the tooltip of the labels lists every ref, and the colours follow the theme`(robot: FxRobot) {
        refs = someRefs
        openAndExpand(robot)
        WaitForAsyncUtils.waitForFxEvents()
        val first = graphCells(robot).single { it.item?.commit?.hash == "h1" }

        robot.interact {
            first.labels.tooltip.show(view.scene.window)
        }
        WaitForAsyncUtils.waitForFxEvents()
        robot.interact {
            val lines = (first.labels.tooltip.graphic as javafx.scene.Parent).childrenUnmodifiable.map { it as Label }
            assertEquals(listOf("HEAD", "main", "origin/main", "v1.0"), lines.map { it.text })
            assertEquals(
                listOf("git-ref-head", "git-ref-local", "git-ref-remote", "git-ref-tag"),
                lines.map { line ->
                    line.graphic.styleClass.single { it.startsWith("git-ref-") && it != "git-ref-icon" }
                },
            )
            first.labels.tooltip.hide()
        }

        fun headColour(scheme: ColorScheme): Paint {
            robot.interact { view.scene.preferences.colorScheme = scheme }
            WaitForAsyncUtils.waitForFxEvents()
            return (first.labels.lookup(".git-ref-head") as org.kordamp.ikonli.javafx.FontIcon).iconColor
        }
        val light = headColour(ColorScheme.LIGHT)
        val dark = headColour(ColorScheme.DARK)
        assertNotEquals(light, dark)
        assertEquals(
            javafx.scene.paint.Color
                .web("#d9a000"),
            light,
        )
        assertEquals(light, headColour(ColorScheme.LIGHT))
    }

    @Test
    fun `rows still show without labels when the refs cannot be read`(robot: FxRobot) {
        refs = null
        openAndExpand(robot)
        WaitForAsyncUtils.waitForFxEvents()

        robot.interact {
            val cells = graphCells(robot).filter { it.item != null }
            assertTrue(cells.isNotEmpty())
            assertTrue(cells.none { it.labels.isVisible })
        }
    }
}
