package dev.sluide.todos.view

import dev.sluide.todos.Todo
import dev.sluide.todos.TodoFolder
import dev.sluide.todos.TodoPanel
import dev.sluide.todos.TodoScope
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** UI tests of moving TODOs and folders by a mouse drag, with fired mouse events (no OS input). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class ItemDragUiTest {
    private val fixture = PanelFixture()
    private lateinit var scene: Scene
    private lateinit var panel: TodoPanel

    @Start
    fun start(stage: Stage) {
        panel = fixture.newPanel()
        scene = Scene(HBox(BorderPane(), panel), 500.0, 400.0)
        stage.scene = scene
        stage.show()
    }

    @AfterEach
    fun clean() = fixture.cleanup()

    private val list get() = panel.lists.getValue(TodoScope.SHARED)

    private fun threeCards(robot: FxRobot): List<TodoCard> = cards(robot, "a", "b", "c")

    private fun cards(
        robot: FxRobot,
        vararg summaries: String,
    ): List<TodoCard> {
        robot.interact { summaries.forEach { fixture.todos.add(TodoScope.SHARED, null, it, "") } }
        layout(robot)
        return fixture.fx(robot) { list.cards }
    }

    private fun layout(robot: FxRobot) =
        robot.interact {
            panel.expanded.set(true)
            panel.applyCss()
            panel.layout()
        }

    /** Root items, top first: a TODO by its summary, a folder by name, then [summaries] of its TODOs. */
    private fun setUp(
        robot: FxRobot,
        vararg items: Any,
    ) {
        robot.interact {
            items.reversed().forEach { item ->
                if (item is String) {
                    fixture.todos.add(TodoScope.SHARED, null, item, "")
                } else {
                    val (name, summaries) = item as Pair<*, *>
                    val folder = assertNotNull(fixture.todos.addFolder(TodoScope.SHARED, null, name as String))
                    (summaries as List<*>).reversed().forEach {
                        fixture.todos.add(TodoScope.SHARED, null, it as String, "", folder.id)
                    }
                }
            }
        }
        layout(robot)
    }

    private fun tree(robot: FxRobot): List<String> =
        fixture.fx(robot) {
            fixture.todos.items(TodoScope.SHARED, null).map { item ->
                when (item) {
                    is Todo -> item.summary
                    is TodoFolder -> "${item.name}${item.todos.map(Todo::summary)}"
                }
            }
        }

    private fun card(
        robot: FxRobot,
        summary: String,
    ) = fixture.fx(robot) { list.allCards.single { it.summary.text == summary } }

    private fun folder(
        robot: FxRobot,
        name: String,
    ) = fixture.fx(robot) { list.folders.single { it.folder.name == name } }

    private fun mouse(
        node: Node,
        type: EventType<MouseEvent>,
        sceneY: Double,
    ) {
        val x = node.localToScene(node.boundsInLocal).minX + 30
        Event.fireEvent(
            node,
            MouseEvent(
                type,
                x,
                sceneY,
                x,
                sceneY,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                type != MouseEvent.MOUSE_RELEASED,
                false,
                false,
                false,
                false,
                false,
                null,
            ),
        )
    }

    private fun centerY(node: Node) = at(node, 0.5)

    /** The scene y at [fraction] of [node]'s height. */
    private fun at(
        node: Node,
        fraction: Double,
    ): Double {
        val bounds = node.localToScene(node.layoutBounds)
        return bounds.minY + bounds.height * fraction
    }

    /** Presses on [header], drags to [toY] and, if [release], releases there. */
    private fun drag(
        robot: FxRobot,
        header: Node,
        toY: () -> Double,
        release: Boolean = true,
    ) = robot.interact {
        val y = toY()
        mouse(header, MouseEvent.MOUSE_PRESSED, centerY(header))
        mouse(header, MouseEvent.MOUSE_DRAGGED, y)
        if (release) mouse(header, MouseEvent.MOUSE_RELEASED, y)
    }

    private fun escape() =
        Event.fireEvent(scene, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))

    private fun order(robot: FxRobot) = fixture.fx(robot) { list.cards.map { it.summary.text } }

    @Test
    fun `dragging a card header reorders the cards on release and shows the insertion line meanwhile`(robot: FxRobot) {
        val cards = threeCards(robot)
        assertEquals(listOf("c", "b", "a"), order(robot))
        val top = cards[0]
        robot.interact {
            val startY = centerY(top.header)
            mouse(top.header, MouseEvent.MOUSE_PRESSED, startY)
            mouse(top.header, MouseEvent.MOUSE_DRAGGED, startY + 2)
            assertFalse(top.styleClass.contains("dragging"))
            assertFalse(list.dropLine.isVisible)
            mouse(top.header, MouseEvent.MOUSE_DRAGGED, at(cards[2], 0.8))
            assertTrue(top.styleClass.contains("dragging"))
            assertTrue(top.translateY > 0, "follows the pointer: ${top.translateY}")
            assertTrue(list.dropLine.isVisible)
            val line = list.dropLine.localToScene(list.dropLine.layoutBounds)
            assertEquals(cards[2].localToScene(cards[2].layoutBounds).maxY, line.centerY, 0.5)
            assertEquals(0.0, cards[1].translateY, "the others stay")
            mouse(top.header, MouseEvent.MOUSE_RELEASED, at(cards[2], 0.8))
            assertEquals(0.0, top.translateY)
            assertFalse(list.dropLine.isVisible)
        }
        assertEquals(listOf("b", "a", "c"), fixture.summaries(robot, TodoScope.SHARED, null))
        assertEquals(listOf("b", "a", "c"), order(robot))
        assertFalse(top.styleClass.contains("dragging"))
    }

    @Test
    fun `a tall expanded card lands where the pointer is, not where its centre is`(robot: FxRobot) {
        robot.interact {
            fixture.todos.add(TodoScope.SHARED, null, "tall", (1..20).joinToString("\n\n") { "line $it" })
        }
        val cards = cards(robot, "a")
        val tall = cards.single { it.summary.text == "tall" }
        robot.interact {
            tall.toggle.fire()
            panel.applyCss()
            panel.layout()
        }
        val first = cards.single { it.summary.text == "a" }
        drag(robot, tall.header, { at(first, 0.2) })
        assertEquals(listOf("tall", "a"), fixture.summaries(robot, TodoScope.SHARED, null))
    }

    @Test
    fun `a root card dragged past a folder lands at the root index after it`(robot: FxRobot) {
        setUp(robot, "x", "F" to listOf("f1", "f2"), "y")
        val y = card(robot, "y")
        drag(robot, card(robot, "x").header, { at(y, 0.8) })
        assertEquals(listOf("F[f1, f2]", "y", "x"), tree(robot))
    }

    @Test
    fun `a root card dragged between two cards of an expanded folder lands between them`(robot: FxRobot) {
        setUp(robot, "x", "F" to listOf("f1", "f2"), "y")
        val f1 = card(robot, "f1")
        val x = card(robot, "x")
        val body = folder(robot, "F").body
        drag(robot, x.header, { at(f1, 0.8) }, release = false)
        robot.interact {
            val line = list.dropLine.localToScene(list.dropLine.layoutBounds)
            assertEquals(body.localToScene(body.insets.left, 0.0).x, line.minX, 0.5, "indented like the folder's cards")
            mouse(x.header, MouseEvent.MOUSE_RELEASED, at(f1, 0.8))
        }
        assertEquals(listOf("F[f1, x, f2]", "y"), tree(robot))
    }

    @Test
    fun `a card dropped on a collapsed folder's header goes to the folder's top, which is highlighted meanwhile`(
        robot: FxRobot,
    ) {
        setUp(robot, "x", "F" to listOf("f1", "f2"), "y")
        val folder = folder(robot, "F")
        robot.interact { fixture.todos.setCollapsed(TodoScope.SHARED, null, folder.folder.id, true) }
        layout(robot)
        val x = card(robot, "x")
        drag(robot, x.header, { centerY(folder.header) }, release = false)
        robot.interact {
            assertTrue(folder.header.styleClass.contains("todo-drop-target"))
            assertFalse(list.dropLine.isVisible)
            mouse(x.header, MouseEvent.MOUSE_RELEASED, centerY(folder.header))
            assertFalse(folder.header.styleClass.contains("todo-drop-target"))
        }
        assertEquals(listOf("F[x, f1, f2]", "y"), tree(robot))
    }

    @Test
    fun `a card dropped on an empty folder's hint goes into the folder`(robot: FxRobot) {
        setUp(robot, "x", "E" to emptyList<String>())
        val folder = folder(robot, "E")
        drag(robot, card(robot, "x").header, { centerY(folder.emptyHint) }, release = false)
        robot.interact {
            assertTrue(folder.emptyHint.styleClass.contains("todo-drop-target"))
            escape()
            assertFalse(folder.emptyHint.styleClass.contains("todo-drop-target"))
        }
        drag(robot, card(robot, "x").header, { centerY(folder.emptyHint) })
        assertEquals(listOf("E[x]"), tree(robot))
    }

    @Test
    fun `a card dragged out of a folder lands at the root`(robot: FxRobot) {
        setUp(robot, "x", "F" to listOf("f1", "f2"), "y")
        val x = card(robot, "x")
        drag(robot, card(robot, "f2").header, { at(x, 0.2) })
        assertEquals(listOf("f2", "x", "F[f1]", "y"), tree(robot))
    }

    @Test
    fun `a card dragged within its folder is reordered there and raises its folder meanwhile`(robot: FxRobot) {
        setUp(robot, "F" to listOf("f1", "f2"), "y")
        val f1 = card(robot, "f1")
        val f2 = card(robot, "f2")
        val folder = folder(robot, "F")
        drag(robot, f1.header, { at(f2, 0.8) }, release = false)
        robot.interact {
            assertTrue(folder.viewOrder < 0, "the folder is painted above the root items")
            mouse(f1.header, MouseEvent.MOUSE_RELEASED, at(f2, 0.8))
            assertEquals(0.0, folder.viewOrder)
        }
        assertEquals(listOf("F[f2, f1]", "y"), tree(robot))
    }

    @Test
    fun `a folder dragged by its header is reordered among the root items`(robot: FxRobot) {
        setUp(robot, "x", "F" to listOf("f1", "f2"), "y")
        val folder = folder(robot, "F")
        val y = card(robot, "y")
        drag(robot, folder.header, { at(y, 0.8) }, release = false)
        robot.interact {
            assertTrue(folder.styleClass.contains("dragging"))
            assertTrue(list.dropLine.isVisible)
            mouse(folder.header, MouseEvent.MOUSE_RELEASED, at(y, 0.8))
            assertFalse(folder.styleClass.contains("dragging"))
        }
        assertEquals(listOf("x", "y", "F[f1, f2]"), tree(robot))
    }

    @Test
    fun `Escape cancels a drag and hides the insertion line`(robot: FxRobot) {
        val cards = threeCards(robot)
        val top = cards[0]
        robot.interact {
            mouse(top.header, MouseEvent.MOUSE_PRESSED, centerY(top.header))
            mouse(top.header, MouseEvent.MOUSE_DRAGGED, at(cards[2], 0.8))
            assertTrue(top.styleClass.contains("dragging"))
            assertTrue(list.dropLine.isVisible)
            escape()
            assertFalse(top.styleClass.contains("dragging"))
            assertEquals(0.0, top.translateY)
            assertFalse(list.dropLine.isVisible)
            mouse(top.header, MouseEvent.MOUSE_RELEASED, at(cards[2], 0.8))
        }
        assertEquals(listOf("c", "b", "a"), fixture.summaries(robot, TodoScope.SHARED, null))
    }

    @Test
    fun `a change of the list from elsewhere ends a drag in progress and the release moves nothing`(robot: FxRobot) {
        val cards = threeCards(robot)
        val top = cards[0]
        drag(robot, top.header, { at(cards[2], 0.8) }, release = false)
        robot.interact {
            assertTrue(list.dropLine.isVisible)
            fixture.todos.add(TodoScope.SHARED, null, "new", "")
            assertFalse(list.dropLine.isVisible)
            assertEquals(0.0, top.translateY)
            assertFalse(top.styleClass.contains("dragging"))
            mouse(top.header, MouseEvent.MOUSE_RELEASED, at(cards[2], 0.8))
        }
        assertEquals(listOf("new", "c", "b", "a"), fixture.summaries(robot, TodoScope.SHARED, null))
    }

    @Test
    fun `a press on a header button does not start a drag, on a card or a folder`(robot: FxRobot) {
        setUp(robot, "x", "F" to listOf("f1"), "y")
        val x = card(robot, "x")
        val folder = folder(robot, "F")
        val y = card(robot, "y")
        robot.interact {
            mouse(x.edit, MouseEvent.MOUSE_PRESSED, centerY(x.header))
            mouse(x.edit, MouseEvent.MOUSE_DRAGGED, at(y, 0.8))
            mouse(folder.edit, MouseEvent.MOUSE_PRESSED, centerY(folder.header))
            mouse(folder.edit, MouseEvent.MOUSE_DRAGGED, at(y, 0.8))
            assertFalse(x.styleClass.contains("dragging"))
            assertFalse(folder.styleClass.contains("dragging"))
            assertFalse(list.dropLine.isVisible)
        }
    }

    @Test
    fun `closing the panel ends a drag in progress`(robot: FxRobot) {
        val cards = threeCards(robot)
        val top = cards[0]
        robot.interact {
            mouse(top.header, MouseEvent.MOUSE_PRESSED, centerY(top.header))
            mouse(top.header, MouseEvent.MOUSE_DRAGGED, at(cards[2], 0.8))
            panel.close()
            assertFalse(top.styleClass.contains("dragging"))
            assertFalse(list.dropLine.isVisible)
            // The Escape filter is gone: this one is no longer consumed by the drag.
            val escape = KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)
            Event.fireEvent(scene, escape)
            assertFalse(escape.isConsumed)
        }
    }

    @Test
    fun `dragging near the bottom edge scrolls the list`(robot: FxRobot) {
        val cards = cards(robot, *(1..15).map { "t$it" }.toTypedArray())
        val top = cards[0]
        robot.interact {
            val startY = centerY(top.header)
            mouse(top.header, MouseEvent.MOUSE_PRESSED, startY)
            val bottom = list.scroll.localToScene(list.scroll.boundsInLocal).maxY
            mouse(top.header, MouseEvent.MOUSE_DRAGGED, startY + 10)
            mouse(top.header, MouseEvent.MOUSE_DRAGGED, bottom - 5)
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var scrolled = 0.0
        while (scrolled <= 0.0 && System.nanoTime() < deadline) {
            robot.interact { scrolled = list.scroll.vvalue }
            // Polling for the animation timeline, bounded by the deadline.
            if (scrolled <= 0.0) Thread.sleep(30)
        }
        assertTrue(scrolled > 0.0, "scrolled to $scrolled")
        robot.interact { escape() }
    }
}
