package dev.sluide.todos.view

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
import kotlin.test.assertTrue

/** UI tests of reordering the cards by a mouse drag, with fired mouse events (no OS input). */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class CardDragUiTest {
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

    private fun threeCards(robot: FxRobot): List<TodoCard> = cards(robot, "a", "b", "c")

    private fun cards(
        robot: FxRobot,
        vararg summaries: String,
    ): List<TodoCard> {
        robot.interact {
            summaries.forEach { fixture.todos.add(TodoScope.SHARED, null, it, "") }
            panel.expanded.set(true)
            panel.applyCss()
            panel.layout()
        }
        return fixture.fx(robot) { panel.lists.getValue(TodoScope.SHARED).cards }
    }

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

    private fun centerY(node: Node) = node.localToScene(node.boundsInLocal).centerY

    private fun escape() =
        Event.fireEvent(scene, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false))

    private fun order(robot: FxRobot) =
        fixture.fx(robot) {
            panel.lists
                .getValue(TodoScope.SHARED)
                .cards
                .map { it.summary.text }
        }

    @Test
    fun `dragging a card header reorders the cards on release and shifts the cards it passes`(robot: FxRobot) {
        val cards = threeCards(robot)
        assertEquals(listOf("c", "b", "a"), order(robot))
        val top = cards[0]
        robot.interact {
            val startY = centerY(top.header)
            mouse(top.header, MouseEvent.MOUSE_PRESSED, startY)
            mouse(top.header, MouseEvent.MOUSE_DRAGGED, startY + 2)
            assertFalse(top.styleClass.contains("dragging"))
            mouse(top.header, MouseEvent.MOUSE_DRAGGED, centerY(cards[2].header) + 5)
            assertTrue(top.styleClass.contains("dragging"))
            assertTrue(cards[1].translateY < 0, "neighbour moved up: ${cards[1].translateY}")
            assertTrue(cards[2].translateY < 0)
            mouse(top.header, MouseEvent.MOUSE_RELEASED, centerY(cards[2].header) + 5)
            assertEquals(0.0, cards[1].translateY)
            assertEquals(0.0, top.translateY)
        }
        assertEquals(listOf("b", "a", "c"), fixture.summaries(robot, TodoScope.SHARED, null))
        assertEquals(listOf("b", "a", "c"), order(robot))
        assertFalse(top.styleClass.contains("dragging"))
    }

    @Test
    fun `dragging a card up shifts the cards it passes down`(robot: FxRobot) {
        val cards = threeCards(robot)
        val bottom = cards[2]
        robot.interact {
            mouse(bottom.header, MouseEvent.MOUSE_PRESSED, centerY(bottom.header))
            mouse(bottom.header, MouseEvent.MOUSE_DRAGGED, centerY(cards[0].header) - 5)
            assertTrue(cards[0].translateY > 0, "first moved down: ${cards[0].translateY}")
            assertTrue(cards[1].translateY > 0)
            mouse(bottom.header, MouseEvent.MOUSE_RELEASED, centerY(cards[0].header) - 5)
            assertEquals(0.0, cards[0].translateY)
            assertEquals(0.0, cards[1].translateY)
        }
        assertEquals(listOf("a", "c", "b"), fixture.summaries(robot, TodoScope.SHARED, null))
    }

    @Test
    fun `Escape cancels a drag and the shifted cards return`(robot: FxRobot) {
        val cards = threeCards(robot)
        val top = cards[0]
        robot.interact {
            val startY = centerY(top.header)
            mouse(top.header, MouseEvent.MOUSE_PRESSED, startY)
            mouse(top.header, MouseEvent.MOUSE_DRAGGED, centerY(cards[2].header) + 5)
            assertTrue(top.styleClass.contains("dragging"))
            assertTrue(cards[1].translateY < 0)
            escape()
            assertFalse(top.styleClass.contains("dragging"))
            assertEquals(0.0, top.translateY)
            assertEquals(0.0, cards[1].translateY)
            mouse(top.header, MouseEvent.MOUSE_RELEASED, centerY(cards[2].header) + 5)
        }
        assertEquals(listOf("c", "b", "a"), fixture.summaries(robot, TodoScope.SHARED, null))
    }

    @Test
    fun `a press on a header button does not start a drag`(robot: FxRobot) {
        val cards = threeCards(robot)
        val top = cards[0]
        robot.interact {
            mouse(top.edit, MouseEvent.MOUSE_PRESSED, centerY(top.header))
            mouse(top.edit, MouseEvent.MOUSE_DRAGGED, centerY(cards[2].header) + 5)
        }
        assertFalse(top.styleClass.contains("dragging"))
    }

    @Test
    fun `closing the panel ends a drag in progress`(robot: FxRobot) {
        val cards = threeCards(robot)
        val top = cards[0]
        robot.interact {
            mouse(top.header, MouseEvent.MOUSE_PRESSED, centerY(top.header))
            mouse(top.header, MouseEvent.MOUSE_DRAGGED, centerY(cards[2].header) + 5)
            panel.close()
            assertFalse(top.styleClass.contains("dragging"))
            // The Escape filter is gone: this one is no longer consumed by the drag.
            val escape = KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)
            Event.fireEvent(scene, escape)
            assertFalse(escape.isConsumed)
        }
    }

    @Test
    fun `dragging near the bottom edge scrolls the list`(robot: FxRobot) {
        val cards = cards(robot, *(1..15).map { "t$it" }.toTypedArray())
        val list = fixture.fx(robot) { panel.lists.getValue(TodoScope.SHARED) }
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
