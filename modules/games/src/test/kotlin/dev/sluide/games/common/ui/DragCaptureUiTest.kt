package dev.sluide.games.common.ui

import javafx.event.EventType
import javafx.scene.Scene
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

// UI test: the capture is made of scene event filters, which need event dispatch through a real scene.
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DragCaptureUiTest {
    private lateinit var board: Pane
    private lateinit var other: Pane
    private lateinit var root: HBox

    /** What reached the board's handlers and what reached the other node's filter and handler. */
    private val boardGot = mutableListOf<MouseEvent>()
    private val otherFilter = mutableListOf<MouseEvent>()
    private val otherGot = mutableListOf<MouseEvent>()

    @Start
    fun start(stage: Stage) {
        board = Pane().apply { setPrefSize(200.0, 200.0) }
        other = Pane().apply { setPrefSize(200.0, 200.0) }
        board.children += Pane().apply { setPrefSize(20.0, 20.0) }
        for (type in listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED)) {
            board.addEventHandler(type) { boardGot += it }
            other.addEventFilter(type) { otherFilter += it }
            other.addEventHandler(type) { otherGot += it }
        }
        keepDragsOn(board)
        // `other` first: the board starts at scene x 200
        root = HBox(other, board)
        stage.scene = Scene(root, 400.0, 200.0)
        stage.show()
    }

    private fun fire(
        robot: FxRobot,
        type: EventType<MouseEvent>,
        onOther: Boolean,
        x: Double = 50.0,
        button: MouseButton = MouseButton.PRIMARY,
    ) = robot.interact { fireMouse(type, if (onOther) other else board, if (onOther) x else 200 + x, 50.0, button) }

    private fun types(events: List<MouseEvent>) = events.map { it.eventType }

    @Test
    fun `a drag and release at another node go to the board in board coordinates and not to that node`(robot: FxRobot) {
        fire(robot, MouseEvent.MOUSE_PRESSED, onOther = false)
        boardGot.clear()

        fire(robot, MouseEvent.MOUSE_DRAGGED, onOther = true, x = 50.0)
        fire(robot, MouseEvent.MOUSE_RELEASED, onOther = true, x = 60.0)

        assertEquals(listOf(MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED), types(boardGot))
        assertEquals(listOf(-150.0, -140.0), boardGot.map { it.x })
        assertEquals(listOf(50.0, 60.0), boardGot.map { it.sceneX })
        assertTrue(otherFilter.isEmpty())
        assertTrue(otherGot.isEmpty())
    }

    @Test
    fun `events at the board and its descendants are delivered once, unchanged`(robot: FxRobot) {
        fire(robot, MouseEvent.MOUSE_PRESSED, onOther = false)
        val child = board.children[0]
        robot.interact { fireMouse(MouseEvent.MOUSE_DRAGGED, child, 210.0, 10.0) }
        fire(robot, MouseEvent.MOUSE_DRAGGED, onOther = false, x = 70.0)

        assertEquals(
            listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_DRAGGED),
            types(boardGot),
        )
        // the child sits at the board's origin: its event keeps the child as target and the x it has without a capture
        assertSame(child, boardGot[1].target)
        assertEquals(10.0, boardGot[1].x)
        assertEquals(70.0, boardGot.last().x)
    }

    @Test
    fun `the release ends the capture`(robot: FxRobot) {
        fire(robot, MouseEvent.MOUSE_PRESSED, onOther = false)
        fire(robot, MouseEvent.MOUSE_RELEASED, onOther = false)

        fire(robot, MouseEvent.MOUSE_DRAGGED, onOther = true)

        assertEquals(listOf(MouseEvent.MOUSE_DRAGGED), types(otherGot))
        assertEquals(listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED), types(boardGot))
    }

    @Test
    fun `a redirected release ends the capture`(robot: FxRobot) {
        fire(robot, MouseEvent.MOUSE_PRESSED, onOther = false)
        fire(robot, MouseEvent.MOUSE_RELEASED, onOther = true)

        fire(robot, MouseEvent.MOUSE_DRAGGED, onOther = true)

        assertEquals(listOf(MouseEvent.MOUSE_DRAGGED), types(otherGot))
    }

    @Test
    fun `a press outside the board ends a capture whose release was lost`(robot: FxRobot) {
        fire(robot, MouseEvent.MOUSE_PRESSED, onOther = false)
        fire(robot, MouseEvent.MOUSE_PRESSED, onOther = true)

        fire(robot, MouseEvent.MOUSE_DRAGGED, onOther = true)

        assertEquals(listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED), types(otherGot))
        assertEquals(listOf(MouseEvent.MOUSE_PRESSED), types(boardGot))
    }

    @Test
    fun `a secondary press does not start a capture`(robot: FxRobot) {
        fire(robot, MouseEvent.MOUSE_PRESSED, onOther = false, button = MouseButton.SECONDARY)

        fire(robot, MouseEvent.MOUSE_DRAGGED, onOther = true)

        assertEquals(listOf(MouseEvent.MOUSE_DRAGGED), types(otherGot))
    }

    @Test
    fun `a press on the board during a capture keeps the capture`(robot: FxRobot) {
        fire(robot, MouseEvent.MOUSE_PRESSED, onOther = false)
        fire(robot, MouseEvent.MOUSE_PRESSED, onOther = false)

        fire(robot, MouseEvent.MOUSE_DRAGGED, onOther = true)

        assertTrue(otherGot.isEmpty())
        assertEquals(MouseEvent.MOUSE_DRAGGED, boardGot.last().eventType)
    }

    @Test
    fun `a secondary press outside the board during the drag goes to the board and the capture stays`(robot: FxRobot) {
        fire(robot, MouseEvent.MOUSE_PRESSED, onOther = false)
        boardGot.clear()

        robot.interact {
            fireMouse(MouseEvent.MOUSE_PRESSED, other, 50.0, 50.0, MouseButton.SECONDARY, primaryDown = true)
        }
        fire(robot, MouseEvent.MOUSE_DRAGGED, onOther = true)

        assertEquals(listOf(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED), types(boardGot))
        assertEquals(MouseButton.SECONDARY, boardGot.first().button)
        assertTrue(otherFilter.isEmpty())
    }

    @Test
    fun `removing the board from the scene ends the capture`(robot: FxRobot) {
        fire(robot, MouseEvent.MOUSE_PRESSED, onOther = false)
        robot.interact { root.children.remove(board) }

        fire(robot, MouseEvent.MOUSE_DRAGGED, onOther = true)

        assertEquals(listOf(MouseEvent.MOUSE_DRAGGED), types(otherGot))
        assertEquals(listOf(MouseEvent.MOUSE_PRESSED), types(boardGot))
    }
}
