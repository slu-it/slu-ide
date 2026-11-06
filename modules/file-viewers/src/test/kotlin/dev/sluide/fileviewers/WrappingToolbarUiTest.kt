package dev.sluide.fileviewers

import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Separator
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [WrappingToolbar] laid out by its parents (ADR 0135): the height a `BorderPane` and a `VBox` give it for a width, CSS
 * and the separators' look need a scene; where the rows put each unit is [ToolbarRowsTest]'s.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class WrappingToolbarUiTest {
    private val root = StackPane()

    @TempDir
    lateinit var dir: Path

    @Start
    fun start(stage: Stage) {
        stage.scene = Scene(root, 500.0, 300.0)
        stage.show()
    }

    private fun box(
        width: Double,
        minWidth: Double = width,
    ) = Region().apply {
        setPrefSize(width, 20.0)
        setMinSize(minWidth, 20.0)
    }

    private fun toolbar(
        leading: List<Node>,
        trailing: List<Node> = emptyList(),
    ) = WrappingToolbar().apply {
        padding = Insets(2.0, 6.0, 2.0, 6.0)
        this.leading.setAll(leading)
        this.trailing.setAll(trailing)
    }

    /** Shows [pane] at most [width] wide and lays it out. */
    private fun show(
        robot: FxRobot,
        pane: Region,
        width: Double,
    ) = robot.interact {
        pane.maxWidth = width
        root.children.setAll(pane)
        root.applyCss()
        root.layout()
    }

    @Test
    fun `in a BorderPane, also inside a VBox, a narrow toolbar takes a second row and the content moves down`(
        robot: FxRobot,
    ) {
        for (inVBox in listOf(false, true)) {
            val trailing = box(80.0)
            val toolbar = toolbar(listOf(box(100.0), box(100.0)), listOf(trailing))
            val content = Region()
            val pane = BorderPane(content).apply { top = if (inVBox) VBox(toolbar) else toolbar }

            show(robot, pane, 400.0)
            assertEquals(24.0, toolbar.height, "one row, inVBox=$inVBox")
            assertEquals(24.0, content.layoutY)
            assertEquals(394.0, trailing.boundsInParent.maxX, "at the right inset")
            assertEquals(2.0, trailing.boundsInParent.minY)

            show(robot, pane, 250.0)
            assertEquals(46.0, toolbar.height, "two rows 2 apart, inVBox=$inVBox")
            assertEquals(46.0, content.layoutY, "below the second row")
            assertEquals(244.0, trailing.boundsInParent.maxX, "still at the right end of the first row")
            assertEquals(2.0, trailing.boundsInParent.minY)
        }
    }

    @Test
    fun `unmanaged nodes take no place and the minimum width is the widest node's`(robot: FxRobot) {
        val first = box(100.0, minWidth = 30.0)
        val hidden = box(200.0, minWidth = 200.0).apply { isManaged = false }
        val second = box(100.0, minWidth = 50.0)
        val toolbar = toolbar(listOf(first, hidden), listOf(second))

        show(robot, toolbar, 400.0)

        val (minWidth, prefWidth) =
            WaitForAsyncUtils.asyncFx<Pair<Double, Double>> { toolbar.minWidth(-1.0) to toolbar.prefWidth(-1.0) }.get()
        assertEquals(6.0 + 50.0 + 6.0, minWidth, "the widest managed node's, plus the insets")
        assertEquals(6.0 + 100.0 + 12.0 + 100.0 + 6.0, prefWidth, "one row, the groups 12 apart")
        assertEquals(6.0, first.layoutX)
        assertEquals(294.0, second.layoutX)
    }

    @Test
    fun `gaps come from the stylesheet, items added later are laid out in the rows, margins apply`(robot: FxRobot) {
        val sheet = dir.resolve("toolbar.css")
        sheet.writeText(".test-toolbar { -fx-hgap: 7; -fx-vgap: 5; }")
        val first = box(100.0)
        val toolbar =
            toolbar(listOf(first)).apply {
                styleClass += "test-toolbar"
                stylesheets += sheet.toUri().toString()
            }
        show(robot, toolbar, 400.0)
        assertEquals(7.0, toolbar.hgap)
        assertEquals(5.0, toolbar.vgap)

        val added = box(100.0)
        robot.interact {
            toolbar.leading += added
            toolbar.layout()
        }
        assertEquals(listOf<Node>(first, added), toolbar.childrenUnmodifiable.toList())
        assertEquals(6.0 + 100.0 + 7.0, added.layoutX)

        show(robot, toolbar, 200.0)
        assertEquals(6.0, added.layoutX, "wrapped")
        assertEquals(2.0 + 20.0 + 5.0, added.layoutY, "the vgap below the first row")

        val margined = box(50.0)
        robot.interact {
            WrappingToolbar.setMargin(margined, Insets(0.0, 0.0, 0.0, 12.0))
            toolbar.leading += margined
        }
        show(robot, toolbar, 400.0)
        assertEquals(6.0 + 100.0 + 7.0 + 100.0 + 7.0 + 12.0, margined.layoutX, "the left margin after the hgap")
        show(robot, toolbar, 130.0)
        assertEquals(6.0, margined.layoutX, "starting a row, without its left margin")
        assertEquals(2.0 + 2 * (20.0 + 5.0), margined.layoutY, "on the third row")
    }

    @Test
    fun `a separator at the end of a row is hidden, one between nodes shows`(robot: FxRobot) {
        val between = Separator(Orientation.VERTICAL)
        val atEnd = Separator(Orientation.VERTICAL)
        val toolbar = toolbar(listOf(box(100.0), between, box(100.0), atEnd, box(100.0)))

        show(robot, toolbar, 260.0)

        assertTrue(between.width > 0.0)
        assertEquals(1.0, between.opacity)
        assertEquals(0.0, atEnd.width)
        assertEquals(0.0, atEnd.opacity, "hidden by the user-agent stylesheet")
        assertTrue(atEnd.isVisible && atEnd.isManaged, "visible and managed stay the callers'")

        show(robot, toolbar, 400.0)
        assertTrue(atEnd.width > 0.0)
        assertEquals(1.0, atEnd.opacity, "shown again between nodes")

        show(robot, toolbar, 260.0)
        assertEquals(0.0, atEnd.opacity)
        robot.interact { toolbar.leading -= atEnd }
        show(robot, VBox(atEnd), 400.0)
        assertTrue(atEnd.pseudoClassStates.none { it.pseudoClassName == "row-edge" }, "cleared when removed")
        assertEquals(1.0, atEnd.opacity, "shown elsewhere")
    }

    @Test
    fun `toggle buttons of one group wrap together, with margins and the hgap, and are not shrunk`(robot: FxRobot) {
        val group = ToggleGroup()
        val toggles = List(3) { ToggleButton("T$it").apply { toggleGroup = group } }
        val separator = Separator(Orientation.VERTICAL)
        val toolbar = toolbar(listOf(separator), toggles).apply { hgap = 2.0 }
        robot.interact { WrappingToolbar.setMargin(toggles[1], Insets(0.0, 3.0, 0.0, 5.0)) }
        show(robot, toolbar, 400.0)
        val widths = toggles.map { it.width }
        val total = widths.sum() + 2 * 2.0 + 5.0 + 3.0

        show(robot, toolbar, 12.0 + total - widths.last() / 2)

        assertEquals(1, toggles.map { it.layoutY }.distinct().size, "one row, though two would fit")
        assertEquals(widths, toggles.map { it.width }, "not shrunk")
        assertEquals(toggles[0].layoutX + widths[0] + 2.0 + 5.0, toggles[1].layoutX)
        assertEquals(toggles[1].layoutX + widths[1] + 3.0 + 2.0, toggles[2].layoutX)

        show(robot, toolbar, 12.0 + total / 2)
        assertEquals(widths, toggles.map { it.width }, "not shrunk on a toolbar narrower than the group")
    }
}
