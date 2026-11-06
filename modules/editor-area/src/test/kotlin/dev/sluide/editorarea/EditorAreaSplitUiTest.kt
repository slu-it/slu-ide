package dev.sluide.editorarea

import javafx.beans.property.SimpleStringProperty
import javafx.css.PseudoClass
import javafx.event.Event
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.SplitPane
import javafx.scene.control.TextField
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.stage.WindowEvent
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * UI tests of the two sides of the editor area: moving tabs with the context menu, the right pane appearing and
 * disappearing, the current side, the focus after moves. The rules are unit-tested in `EditorTabsTest`. Needs a
 * display, see the `uiTest` Gradle task.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class EditorAreaSplitUiTest : EditorAreaUiFixture() {
    @Start
    fun start(stage: Stage) = startView(stage)

    private val selectedState = PseudoClass.getPseudoClass("selected")

    /** A tab whose content is a text field, as terminals and tools have focusable content. */
    private class FieldTab(
        name: String,
        override val focusOnShow: Boolean,
    ) : EditorTab {
        override val title = SimpleStringProperty(name)
        override val tooltip = name
        override val content = TextField()
    }

    private fun <T> fx(action: () -> T): T = WaitForAsyncUtils.asyncFx(action).get()

    private fun splitPane(): SplitPane = fx { editor.lookup(".split-pane") as SplitPane }

    private fun isSplit() = fx { splitPane().items.size == 2 }

    /** The entries of the tab's menu as showing the menu sets them, without opening a popup. */
    private fun menuOf(
        robot: FxRobot,
        tab: EditorTab,
    ): List<MenuItem> {
        val menu = editor.stripOf(editor.editorTabs.sideOf(tab)).contextMenuOf(tab)
        robot.interact { Event.fireEvent(menu, WindowEvent(menu, WindowEvent.WINDOW_SHOWING)) }
        return fx { menu.items.toList() }
    }

    private fun moveVia(
        robot: FxRobot,
        tab: EditorTab,
        text: String,
    ) {
        val item = menuOf(robot, tab).single { it.text == text }
        robot.interact { item.fire() }
    }

    private fun tabOf(name: String) = editor.tabs.single { it.title.value == name }

    private fun current() = fx { editor.editorTabs.current.get() }

    private fun shownIn(
        tab: EditorTab,
        side: Side,
    ) = fx { generateSequence<Node>(tab.content) { it.parent }.any { it === editor.stripOf(side).parent } }

    /** Whether the focus is in [node], e.g. the editor of a file tab. */
    private fun focusIn(node: Node) = fx { stage.scene.focusOwner?.isInside(node) == true }

    private fun editorOf(tab: EditorTab): Node = (tab as OpenFile).editor.node

    /** Opens a.txt, b.txt and c.txt and moves c.txt to the right side, which opens it. */
    private fun openThreeAndMoveLast(robot: FxRobot): List<EditorTab> {
        open(robot, textFile("a.txt"), textFile("b.txt"), textFile("c.txt"))
        val tabs = listOf("a.txt", "b.txt", "c.txt").map(::tabOf)
        moveVia(robot, tabs.last(), "Move to Right Side")
        return tabs
    }

    @Test
    fun `Move to Right Side opens the split with the tab selected and focused there`(robot: FxRobot) {
        val (a, b, c) = openThreeAndMoveLast(robot)

        assertTrue(isSplit())
        assertEquals(1, robot.lookup(".split-pane").queryAll<Node>().size)
        assertTrue(shownIn(c, Side.RIGHT))
        assertTrue(shownIn(b, Side.LEFT), "the left shows its neighbour")
        assertSame(c, editor.editorTabs.selectedOf(Side.RIGHT).get())
        assertSame(c, editor.selectedTab)
        assertEquals(Side.RIGHT, current())
        assertEquals(listOf(a, b), editor.editorTabs.tabsOf(Side.LEFT).toList())
        assertEquals(0.5, fx { splitPane().dividerPositions.single() }, 0.05)
        eventually(robot) { assertTrue(focusIn(editorOf(c)), "the moved tab, not the left neighbour") }
    }

    @ParameterizedTest
    @EnumSource(Side::class)
    internal fun `the divider stays in the middle when a side's content is wider than half the area`(
        wideSide: Side,
        robot: FxRobot,
    ) {
        // Like a view with a wide toolbar: its minimum width is more than half the 800 px wide area.
        val wide = FieldTab("wide", focusOnShow = false).apply { content.minWidth = 600.0 }
        robot.interact { editor.open(wide) }
        open(robot, textFile("a.txt"))

        moveVia(robot, if (wideSide == Side.RIGHT) wide else tabOf("a.txt"), "Move to Right Side")

        assertEquals(0.5, fx { splitPane().dividerPositions.single() }, 0.05)
        val side = editor.stripOf(wideSide).parent as Region
        assertTrue(fx { side.width } < 600.0)
        // Cut off at the right edge only: the content starts where its side starts.
        assertEquals(fx { side.localToScene(0.0, 0.0).x }, fx { wide.content.localToScene(0.0, 0.0).x }, 1.0)

        // Without a split, the area needs its content's width again, as it did before the split.
        robot.interact { editor.editorTabs.close(editor.editorTabs.tabsOf(Side.RIGHT).single()) }
        if (wideSide == Side.LEFT) {
            assertEquals(Region.USE_COMPUTED_SIZE, fx { (editor.stripOf(Side.LEFT).parent as Region).minWidth })
        }
    }

    @Test
    fun `moving or closing the last right tab removes the right pane`(robot: FxRobot) {
        val (_, _, c) = openThreeAndMoveLast(robot)

        moveVia(robot, c, "Move to Left Side")

        assertFalse(isSplit())
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), tabTitles(robot))
        assertEquals(1, robot.lookup(".tab-strip").queryAll<Node>().size)
        assertEquals(1, robot.lookup(".editor-content").queryAll<Node>().size)

        moveVia(robot, c, "Move to Right Side")
        assertTrue(isSplit())
        robot.interact { editor.editorTabs.close(c) }
        assertFalse(isSplit())
    }

    @Test
    fun `closing the last left tab leaves one pane showing the former right tabs`(robot: FxRobot) {
        val (a, b, c) = openThreeAndMoveLast(robot)

        robot.interact {
            editor.editorTabs.close(a)
            editor.editorTabs.close(b)
        }

        assertFalse(isSplit())
        assertEquals(listOf("c.txt"), tabTitles(robot))
        assertTrue(shownIn(c, Side.LEFT))
        assertEquals("content of c.txt", shownText(robot))
    }

    @Test
    fun `a right tab has Move to Left Side only`(robot: FxRobot) {
        val (_, _, c) = openThreeAndMoveLast(robot)

        val entries = menuOf(robot, c).filterNot { it is SeparatorMenuItem }.map { it.text }

        assertEquals(listOf("Close", "Move to Left Side"), entries.take(2))
        assertFalse("Move to Right Side" in entries)
    }

    @Test
    fun `a mouse press or the focus in a side makes it current`(robot: FxRobot) {
        val (a, b, c) = openThreeAndMoveLast(robot)
        assertEquals(Side.RIGHT, current())

        robot.interact { fireMouse(b.content, MouseEvent.MOUSE_PRESSED) }

        assertEquals(Side.LEFT, current())
        assertSame(b, editor.selectedTab)
        robot.interact { editor.closeSelected() }
        assertEquals(listOf(a, c), editor.tabs)

        // The focus has to enter the right side from outside of it, so it first goes to a field of the left side.
        val leftField = TextField()
        val field = TextField()
        robot.interact {
            (a.content as Pane).children.add(leftField)
            (c.content as Pane).children.add(field)
            leftField.requestFocus()
        }
        assertEquals(Side.LEFT, current())
        robot.interact { field.requestFocus() }
        assertEquals(Side.RIGHT, current())
    }

    @Test
    fun `the selected header is muted on the side that is not current, and only while split`(robot: FxRobot) {
        open(robot, textFile("a.txt"), textFile("b.txt"))
        assertEquals(Headers(selected = listOf("b.txt")), headersOf(Side.LEFT))

        moveVia(robot, tabOf("b.txt"), "Move to Right Side")
        assertEquals(Headers(muted = listOf("a.txt")), headersOf(Side.LEFT))
        assertEquals(Headers(selected = listOf("b.txt")), headersOf(Side.RIGHT))

        robot.interact { editor.editorTabs.makeCurrent(Side.LEFT) }
        assertEquals(Headers(selected = listOf("a.txt")), headersOf(Side.LEFT))
        assertEquals(Headers(muted = listOf("b.txt")), headersOf(Side.RIGHT))

        robot.interact { editor.editorTabs.close(tabOf("b.txt")) }
        assertEquals(Headers(selected = listOf("a.txt")), headersOf(Side.LEFT))

        // The left side empties: the right tab becomes the single area's, selected and not muted.
        open(robot, textFile("c.txt"))
        moveVia(robot, tabOf("c.txt"), "Move to Right Side")
        robot.interact { editor.editorTabs.close(tabOf("a.txt")) }
        assertEquals(Headers(selected = listOf("c.txt")), headersOf(Side.LEFT))
    }

    /** The titles of a strip's headers marked selected and muted. */
    private data class Headers(
        val selected: List<String> = emptyList(),
        val muted: List<String> = emptyList(),
    )

    private fun headersOf(side: Side): Headers =
        fx {
            val headers = editor.stripOf(side).lookupAll(".editor-tab").filterIsInstance<Region>()

            fun title(header: Region) = (header.lookup(".label") as Label).text
            Headers(
                selected = headers.filter { selectedState in it.pseudoClassStates }.map(::title),
                muted = headers.filter { "editor-tab-muted" in it.styleClass }.map(::title),
            )
        }

    @Test
    fun `the selected header of the inactive side is drawn differently from the active one`(robot: FxRobot) {
        open(robot, textFile("a.txt"), textFile("b.txt"))
        moveVia(robot, tabOf("b.txt"), "Move to Right Side")

        fun selectedFill(side: Side): Any? =
            fx {
                val header =
                    editor.stripOf(side).lookupAll(".editor-tab").filterIsInstance<Region>().single {
                        PseudoClass.getPseudoClass("selected") in it.pseudoClassStates ||
                            "editor-tab-muted" in it.styleClass
                    }
                header.applyCss()
                header.background
                    ?.fills
                    ?.firstOrNull()
                    ?.fill
            }

        val active = selectedFill(Side.RIGHT)
        val muted = selectedFill(Side.LEFT)

        assertNotEquals(active, muted)
        // The light colour: a dark rule written as a descendant selector would apply in both themes (ADR 0009).
        assertEquals(Color.web("#f4f4f4"), muted)
    }

    @Test
    fun `moving a left tab while the right side has the focus focuses the moved tab`(robot: FxRobot) {
        val (_, b, _) = openThreeAndMoveLast(robot)
        eventually(robot) { assertEquals(Side.RIGHT, current()) }

        moveVia(robot, b, "Move to Right Side")

        eventually(robot) {
            assertTrue(focusIn(editorOf(b)))
            assertEquals(Side.RIGHT, current())
        }
    }

    @Test
    fun `moving a tab that asks for the focus focuses it, on the first open and with the split open`(robot: FxRobot) {
        open(robot, textFile("a.txt"))
        val first = FieldTab("first", focusOnShow = true)
        val second = FieldTab("second", focusOnShow = true)
        robot.interact { editor.open(first) }

        moveVia(robot, first, "Move to Right Side")
        eventually(robot) {
            assertTrue(focusIn(first.content))
            assertEquals(Side.RIGHT, current())
        }

        robot.interact { editor.open(second) }
        eventually(robot) { assertEquals(Side.LEFT, current()) }
        moveVia(robot, second, "Move to Right Side")
        eventually(robot) {
            assertTrue(focusIn(second.content))
            assertEquals(Side.RIGHT, current())
        }
    }

    @Test
    fun `a moved tab without focusOnShow keeps its focus and the left replacement does not take it`(robot: FxRobot) {
        val file = textFile("a.txt")
        open(robot, file)
        val tab = FieldTab("plain", focusOnShow = false)
        robot.interact {
            editor.open(tab)
            tab.content.requestFocus()
        }

        moveVia(robot, tab, "Move to Right Side")

        eventually(robot) {
            assertTrue(focusIn(tab.content))
            assertEquals(Side.RIGHT, current())
            assertFalse(focusIn(editorOf(tabOf("a.txt"))))
        }
    }

    @Test
    fun `closing the last right tab while it has the focus focuses the left selected tab`(robot: FxRobot) {
        // A field ahead of the area takes the initial focus, which would hide a missing refocus.
        fieldAbove(robot)
        val (_, _, c) = openThreeAndMoveLast(robot)
        eventually(robot) { assertTrue(focusIn(editorOf(c))) }

        robot.interact { editor.editorTabs.close(c) }

        eventually(robot) {
            assertEquals(Side.LEFT, current())
            assertTrue(focusIn(editor.selectedTab!!.let(::editorOf)))
        }
    }

    @Test
    fun `closing the last right tab keeps the focus on the left selected tab when left was made current by a press`(
        robot: FxRobot,
    ) {
        fieldAbove(robot)
        val (_, b, c) = openThreeAndMoveLast(robot)
        eventually(robot) { assertTrue(focusIn(editorOf(c))) }
        robot.interact { fireMouse(b.content, MouseEvent.MOUSE_PRESSED) }
        assertEquals(Side.LEFT, current())

        robot.interact { editor.editorTabs.close(c) }

        eventually(robot) { assertTrue(focusIn(editorOf(b))) }
    }

    private fun visibleCount(
        robot: FxRobot,
        selector: String,
    ) = robot.lookup(selector).queryAll<Node>().count { fx { it.isVisible } }

    /** The scene point at the fractions ([px], [py]) of [node]'s layout bounds. */
    private fun scenePoint(
        node: Node,
        px: Double,
        py: Double,
    ) = fx { node.layoutBounds.let { node.localToScene(it.minX + it.width * px, it.minY + it.height * py) } }

    /** A point in the right half of the left content, below the strip. */
    private fun rightHalfPoint() = scenePoint(editor.stripOf(Side.LEFT).parent, 0.75, 0.6)

    @Test
    fun `dragging a header onto the right half shows the drop zone and releasing opens the split`(robot: FxRobot) {
        open(robot, textFile("a.txt"), textFile("b.txt"))
        val (a, b) = listOf("a.txt", "b.txt").map(::tabOf)
        val target = rightHalfPoint()

        dragTo(robot, tabLabel(robot, "a.txt"), target.x, target.y, release = false)

        assertEquals(1, visibleCount(robot, ".tab-drop-zone"))
        assertFalse(isSplit())

        releaseOn(robot, tabLabel(robot, "a.txt"), target.x, target.y)

        assertTrue(isSplit())
        assertEquals(0, visibleCount(robot, ".tab-drop-zone"))
        assertEquals(listOf(a), editor.editorTabs.tabsOf(Side.RIGHT).toList())
        assertEquals(listOf(b), editor.editorTabs.tabsOf(Side.LEFT).toList())
        assertEquals(Side.RIGHT, current())
        assertSame(a, editor.selectedTab)
        eventually(robot) { assertTrue(focusIn(editorOf(a))) }
    }

    @Test
    fun `dropping a header between two tabs of the other row inserts it there and hides the marker`(robot: FxRobot) {
        open(robot, textFile("a.txt"), textFile("b.txt"), textFile("c.txt"), textFile("d.txt"))
        val (a, b, c, d) = listOf("a.txt", "b.txt", "c.txt", "d.txt").map(::tabOf)
        moveVia(robot, c, "Move to Right Side")
        moveVia(robot, d, "Move to Right Side")
        val header = tabLabel(robot, "c.txt").parent
        val gap = fx { header.localToScene(header.layoutBounds.maxX, header.layoutBounds.centerY) }

        dragTo(robot, tabLabel(robot, "a.txt"), gap.x, gap.y, release = false)

        assertEquals(1, visibleCount(robot, ".tab-insert-marker"))

        releaseOn(robot, tabLabel(robot, "a.txt"), gap.x, gap.y)

        assertEquals(listOf(c, a, d), editor.editorTabs.tabsOf(Side.RIGHT).toList())
        assertEquals(listOf(b), editor.editorTabs.tabsOf(Side.LEFT).toList())
        assertEquals(0, visibleCount(robot, ".tab-insert-marker"))
        assertEquals(Side.RIGHT, current())
    }

    @Test
    fun `dragging the last right tab onto the left row removes the split`(robot: FxRobot) {
        val (a, b, c) = openThreeAndMoveLast(robot)
        val left = scenePoint(editor.stripOf(Side.LEFT), 0.9, 0.5)

        dragTo(robot, tabLabel(robot, "c.txt"), left.x, left.y)

        assertFalse(isSplit())
        assertEquals(listOf(a, b, c), editor.editorTabs.tabs.toList())
        assertEquals(Side.LEFT, current())
    }

    @Test
    fun `a single left tab dragged onto the right half shows no zone and nothing moves`(robot: FxRobot) {
        open(robot, textFile("a.txt"))
        val target = rightHalfPoint()

        dragTo(robot, tabLabel(robot, "a.txt"), target.x, target.y, release = false)

        assertEquals(0, visibleCount(robot, ".tab-drop-zone"))

        releaseOn(robot, tabLabel(robot, "a.txt"), target.x, target.y)

        assertFalse(isSplit())
        assertEquals(listOf("a.txt"), tabTitles(robot))
    }

    @Test
    fun `a drag that leaves the other row hides its marker`(robot: FxRobot) {
        val (a, b, c) = openThreeAndMoveLast(robot)
        val label = tabLabel(robot, "a.txt")
        val right = scenePoint(editor.stripOf(Side.RIGHT), 0.9, 0.5)
        val own = scenePoint(editor.stripOf(Side.LEFT), 0.9, 0.5)

        dragTo(robot, label, right.x, right.y, release = false)
        assertEquals(1, visibleCount(robot, ".tab-insert-marker"))

        robot.interact { fireMouse(label, MouseEvent.MOUSE_DRAGGED, sceneX = own.x, sceneY = own.y) }

        assertEquals(0, visibleCount(robot, ".tab-insert-marker"))
        releaseOn(robot, label, own.x, own.y)
        assertEquals(listOf(c), editor.editorTabs.tabsOf(Side.RIGHT).toList())
        assertEquals(listOf(b, a), editor.editorTabs.tabsOf(Side.LEFT).toList(), "reordered in its own row")
    }

    @Test
    fun `when the left side empties while the right has the focus, the former right tab is focused`(robot: FxRobot) {
        fieldAbove(robot)
        open(robot, textFile("a.txt"), textFile("b.txt"))
        val (a, b) = listOf("a.txt", "b.txt").map(::tabOf)
        moveVia(robot, b, "Move to Right Side")
        eventually(robot) { assertTrue(focusIn(editorOf(b))) }

        robot.interact { editor.editorTabs.close(a) }

        eventually(robot) {
            assertFalse(isSplit())
            assertEquals(Side.LEFT, current())
            assertTrue(focusIn(editorOf(b)))
        }
    }
}
