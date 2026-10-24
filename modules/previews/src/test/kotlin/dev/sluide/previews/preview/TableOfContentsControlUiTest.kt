package dev.sluide.previews.preview

import dev.sluide.previews.press
import dev.sluide.previews.primaryMouseEvent
import dev.sluide.previews.scrollEvent
import javafx.event.Event
import javafx.geometry.Orientation
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ScrollBar
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [TableOfContents] on its own, with fixed headings and no preview view, editor or page: what it shows, sizes and
 * chooses. `TableOfContentsUiTest` checks it inside a Markdown view. Needs a display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class TableOfContentsControlUiTest {
    private val container = StackPane()
    private lateinit var toc: TableOfContents

    private var headings = emptyList<Heading>()
    private var line = 0
    private val navigated = mutableListOf<Heading>()
    private var restored = 0

    @Start
    fun start(stage: Stage) {
        toc = TableOfContents({ headings }, { line }, { navigated += it }, { restored++ })
        container.children.setAll(Region(), toc.overlay)
        container.stylesheets +=
            checkNotNull(TableOfContentsControlUiTest::class.java.getResource("/dev/sluide/previews/preview-view.css"))
                .toExternalForm()
        stage.scene = Scene(container, 1000.0, 600.0)
        stage.show()
    }

    private val titles =
        listOf("Alpha" to 1, "Beta" to 2, "Gamma" to 3, "Delta" to 1, "Epsilon" to 2, "Zeta" to 3)

    /** The six titles, the heading of index `i` on line `42 * i`. */
    private val sixHeadings get() = titles.mapIndexed { i, (text, level) -> Heading(level, text, 42 * i) }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx<T> { block() }.get()

    private fun open(
        robot: FxRobot,
        all: List<Heading>,
        currentLine: Int = 0,
    ) {
        headings = all
        line = currentLine
        robot.interact { toc.open() }
        WaitForAsyncUtils.waitForFxEvents()
    }

    private fun cell(text: String): ListCell<*> =
        toc.list
            .lookupAll(".list-cell")
            .filterIsInstance<ListCell<*>>()
            .single { (it.item as? Heading)?.text == text }

    private fun fireMouse(
        robot: FxRobot,
        node: javafx.scene.Node,
        type: javafx.event.EventType<MouseEvent>,
    ) = robot.interact { Event.fireEvent(node, primaryMouseEvent(type)) }

    @Test
    fun `the overlay starts hidden and closed`() {
        assertFalse(fx { toc.overlay.isVisible })
        assertFalse(fx { toc.button.isSelected })
        assertFalse(fx { toc.isOpen })
    }

    @Test
    fun `opening lists the headings, selects the section of the current line and focuses the list`(robot: FxRobot) {
        open(robot, sixHeadings, currentLine = 42 * 2 + 10)

        assertTrue(fx { toc.isOpen && toc.overlay.isVisible && toc.button.isSelected })
        assertEquals(2, fx { toc.list.selectionModel.selectedIndex })
        assertSame(toc.list, fx { toc.list.scene.focusOwner })
        assertEquals(sixHeadings, fx { toc.list.items.toList() })
    }

    @Test
    fun `the entries are indented by their depth among the levels in use`(robot: FxRobot) {
        open(robot, sixHeadings)

        val depths = listOf(0, 1, 2, 0, 1, 2)
        titles.forEachIndexed { i, (title, _) ->
            assertEquals(8.0 + depths[i] * 14.0, fx { cell(title).padding.left }, 0.5, "indent of $title")
        }
    }

    @Test
    fun `Enter chooses the selected entry, closes the overlay and navigates`(robot: FxRobot) {
        open(robot, sixHeadings)
        robot.interact { toc.list.selectionModel.select(4) }

        robot.press(toc.list, KeyCode.ENTER)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(sixHeadings[4]), navigated)
        assertFalse(fx { toc.isOpen })
        assertFalse(fx { toc.button.isSelected })
    }

    @Test
    fun `clicking an entry chooses it`(robot: FxRobot) {
        open(robot, sixHeadings)

        fireMouse(robot, fx { cell("Epsilon") }, MouseEvent.MOUSE_CLICKED)
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(listOf(sixHeadings[4]), navigated)
        assertFalse(fx { toc.isOpen })
    }

    @Test
    fun `Escape and unselecting the button close the overlay and give the focus back`(robot: FxRobot) {
        open(robot, sixHeadings)
        robot.press(toc.list, KeyCode.ESCAPE)
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(fx { toc.isOpen })
        assertFalse(fx { toc.button.isSelected })
        assertEquals(1, restored)

        robot.interact { toc.open() }
        robot.interact { toc.button.isSelected = false }
        assertFalse(fx { toc.isOpen })
        assertEquals(2, restored)
        assertEquals(emptyList(), navigated)
    }

    @Test
    fun `a document without headings shows an empty list`(robot: FxRobot) {
        open(robot, emptyList())

        assertTrue(fx { toc.isOpen })
        assertTrue(fx { toc.list.items.isEmpty() })
        assertEquals(-1, fx { toc.list.selectionModel.selectedIndex })
        assertEquals("No headings", fx { (toc.list.placeholder as Label).text })
        robot.press(toc.list, KeyCode.ENTER)
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(fx { toc.isOpen }, "Enter without an entry does nothing")
        assertEquals(emptyList(), navigated)
        robot.press(toc.list, KeyCode.ESCAPE)
        WaitForAsyncUtils.waitForFxEvents()
        assertFalse(fx { toc.isOpen })
    }

    @Test
    fun `the first open without headings is tall and wide enough for the placeholder`(robot: FxRobot) {
        open(robot, emptyList())

        val placeholder = toc.list.placeholder as Label
        fx {
            val height = placeholder.prefHeight(-1.0)
            val width = placeholder.prefWidth(-1.0)
            assertTrue(toc.overlay.height >= height, "overlay ${toc.overlay.height} is too low")
            assertTrue(toc.list.height >= height, "list ${toc.list.height} is too low")
            assertTrue(toc.list.width >= width, "list ${toc.list.width} is too narrow")
            assertTrue(placeholder.width >= width, "placeholder is clipped: ${placeholder.width}")
            assertTrue(placeholder.height >= height, "placeholder is clipped: ${placeholder.height}")
        }
    }

    private fun visibleScrollBars(orientation: Orientation) =
        fx {
            toc.list.lookupAll(".scroll-bar").filterIsInstance<ScrollBar>().filter {
                it.orientation == orientation && it.isVisible
            }
        }

    @Test
    fun `a very long heading does not give the list a horizontal scroll bar`(robot: FxRobot) {
        val long = "A very long heading that goes on and on ".repeat(8).trim()
        open(robot, listOf(Heading(1, "Short", 0), Heading(2, long, 4), Heading(2, "Another", 8)))

        assertEquals(3, fx { toc.list.items.size })
        assertTrue(visibleScrollBars(Orientation.HORIZONTAL).isEmpty(), "horizontal scroll bar is shown")
    }

    @Test
    fun `five short headings do not give the list a scroll bar`(robot: FxRobot) {
        open(robot, (1..5).map { Heading(2, "Part $it", it * 4) })

        assertEquals(5, fx { toc.list.items.size })
        assertTrue(visibleScrollBars(Orientation.VERTICAL).isEmpty(), "vertical scroll bar is shown")
        assertTrue(visibleScrollBars(Orientation.HORIZONTAL).isEmpty(), "horizontal scroll bar is shown")
    }

    @Test
    fun `more headings than fit scroll inside the overlay, down to the last`(robot: FxRobot) {
        open(robot, (1..80).map { Heading(2, "Part $it", it * 4) })

        val overlay = fx { toc.overlay.localToScene(toc.overlay.layoutBounds) }
        val list = fx { toc.list.localToScene(toc.list.layoutBounds) }
        assertTrue(overlay.maxY <= fx { container.height }, "overlay ends below the view: ${overlay.maxY}")
        assertTrue(list.maxY <= overlay.maxY + 0.5, "list ${list.maxY} ends below its overlay ${overlay.maxY}")
        assertFalse(visibleScrollBars(Orientation.VERTICAL).isEmpty(), "no vertical scroll bar")

        // A wheel turned down (a negative delta) over the list, a few times as the real wheel does.
        val flow = fx { toc.list.lookup(".virtual-flow") }
        repeat(40) { robot.interact { Event.fireEvent(flow, scrollEvent(-400.0, shortcut = false)) } }
        WaitForAsyncUtils.waitForFxEvents()
        val lastBottom =
            fx {
                val last =
                    toc.list
                        .lookupAll(".list-cell")
                        .filterIsInstance<ListCell<*>>()
                        .single { it.isVisible && it.item == toc.list.items.last() }
                last.localToScene(last.layoutBounds).maxY
            }
        assertTrue(lastBottom <= list.maxY + 0.5, "the last heading is cut off")
    }
}
