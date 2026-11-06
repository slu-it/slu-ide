package dev.sluide.games.doubles.ui

import dev.sluide.games.doubles.BoardSize
import dev.sluide.games.doubles.Cell
import dev.sluide.games.doubles.Direction
import dev.sluide.games.doubles.DoublesGame
import dev.sluide.games.doubles.DoublesModel
import dev.sluide.games.doubles.DoublesPreferences
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.RadioMenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The connections of the Doubles view to its model that need a scene: key events with the focus, the size menu, the
 * guard dialog's stub and mouse drags. A swipe has no test: Xvfb cannot produce one. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class DoublesViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: DoublesModel
    private lateinit var view: DoublesView
    private lateinit var stage: Stage
    private val asked = mutableListOf<Triple<String, String, Int>>()
    private var answer = false

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = DoublesPreferences(store, java.time.Duration.ZERO) { it.run() }
        var first = true
        model =
            DoublesModel(preferences, Random(1)) { size, random ->
                if (first && size == BoardSize.FOUR) {
                    first = false
                    DoublesGame.of(
                        size,
                        listOf(listOf(2, 2, 0, 0), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0), listOf(0, 0, 0, 0)),
                        random,
                    )
                } else {
                    DoublesGame(size, random)
                }
            }
        view = DoublesView(model)
        view.board.animationDuration = Duration.ZERO
        view.confirmAbandon = { title, verb, score ->
            asked += Triple(title, verb, score)
            answer
        }
        stage.scene = Scene(view.node as Parent, 600.0, 700.0)
        // The primary stage is shared by the UI test classes; a size set by an earlier test would outlive the scene.
        stage.sizeToScene()
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun press(
        robot: FxRobot,
        code: KeyCode,
    ) = robot.interact {
        Event.fireEvent(view.board, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    }

    private fun focusBoard(robot: FxRobot) = robot.interact { view.board.requestFocus() }

    private fun texts() = fx { view.board.tileTexts() }

    @Test
    fun `an arrow key merges the tiles, updates the score and leaves the focus on the board`(robot: FxRobot) {
        focusBoard(robot)

        press(robot, KeyCode.LEFT)

        assertEquals("4", texts()[Cell(0, 0)])
        assertEquals(2, texts().size)
        assertEquals("4", fx { view.scoreValue.text })
        // The arrow was consumed: it never reached the root (where focus traversal would see it).
        assertSame(view.board, fx { stage.scene.focusOwner })
        val afterLeft = texts()
        val leaked = mutableListOf<KeyCode>()
        robot.interact { view.node.addEventHandler(KeyEvent.KEY_PRESSED) { leaked += it.code } }
        press(robot, KeyCode.RIGHT)
        assertTrue(leaked.isEmpty())
        assertNotEquals(afterLeft, texts())
        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `U undoes the move and disables the undo button`(robot: FxRobot) {
        focusBoard(robot)
        assertTrue(fx { view.undoButton.isDisable })
        press(robot, KeyCode.LEFT)
        assertFalse(fx { view.undoButton.isDisable })

        press(robot, KeyCode.U)

        assertEquals("0", fx { view.scoreValue.text })
        assertEquals(mapOf(Cell(0, 0) to "2", Cell(0, 1) to "2"), texts())
        assertTrue(fx { view.undoButton.isDisable })
    }

    @Test
    fun `F2 with a score asks first and only a yes starts a new game`(robot: FxRobot) {
        focusBoard(robot)
        press(robot, KeyCode.LEFT)
        val before = texts()

        press(robot, KeyCode.F2)

        assertEquals(listOf(Triple("New Game", "New Game", 4)), asked)
        assertEquals(before, texts())
        assertEquals("4", fx { view.scoreValue.text })

        answer = true
        press(robot, KeyCode.F2)

        assertEquals(2, asked.size)
        assertEquals("0", fx { view.scoreValue.text })
    }

    @Test
    fun `the size menu changes the board unless the question is refused`(robot: FxRobot) {
        focusBoard(robot)
        press(robot, KeyCode.LEFT)
        val before = texts()
        val four = view.sizeMenu.items[BoardSize.FOUR.ordinal] as RadioMenuItem
        val six = view.sizeMenu.items[BoardSize.SIX.ordinal] as RadioMenuItem

        // The real menu selects the item, then fires it.
        robot.interact {
            six.isSelected = true
            six.fire()
        }

        assertEquals("Change Size", asked.single().first)
        assertEquals(BoardSize.FOUR, fx { model.size })
        assertEquals(before, texts())
        assertTrue(fx { four.isSelected })
        assertFalse(fx { six.isSelected })
        assertEquals("4×4", fx { view.sizeMenu.text })

        answer = true
        robot.interact {
            six.isSelected = true
            six.fire()
        }

        assertEquals(BoardSize.SIX, fx { model.size })
        assertEquals("6×6", fx { view.sizeMenu.text })
        assertEquals(36, fx { view.board.children.count { "dbl-cell" in it.styleClass } })
        assertNotEquals(before, texts())
        assertEquals(2, texts().size)
    }

    @Test
    fun `choosing the current size during a game does nothing`(robot: FxRobot) {
        focusBoard(robot)
        press(robot, KeyCode.LEFT)
        val before = texts()
        val four = view.sizeMenu.items[BoardSize.FOUR.ordinal] as RadioMenuItem

        robot.interact {
            four.isSelected = true
            four.fire()
        }

        assertTrue(asked.isEmpty())
        assertEquals(before, texts())
        assertEquals("4", fx { view.scoreValue.text })
    }

    @Test
    fun `a disposed view leaves both callbacks to the view that took over`(robot: FxRobot) {
        lateinit var second: DoublesView
        robot.interact {
            second = DoublesView(model)
            second.board.animationDuration = Duration.ZERO
            view.dispose()
            stage.scene.root = second.node as Parent
            model.move(Direction.LEFT)
        }
        assertEquals("4", fx { second.board.tileTexts()[Cell(0, 0)] })
        assertEquals("4", fx { second.scoreValue.text })
        assertEquals("0", fx { view.scoreValue.text })

        // Undo goes through onChange, which the disposed first view must not have reset.
        robot.interact { model.undo() }
        assertEquals("0", fx { second.scoreValue.text })
        assertEquals(mapOf(Cell(0, 0) to "2", Cell(0, 1) to "2"), fx { second.board.tileTexts() })

        robot.interact { second.dispose() }
        robot.interact { model.move(Direction.LEFT) }
        assertEquals("0", fx { second.scoreValue.text })
    }

    @Test
    fun `a key with a modifier is left to the menu accelerators`(robot: FxRobot) {
        focusBoard(robot)
        val before = texts()
        val leaked = mutableListOf<KeyCode>()
        robot.interact { view.node.addEventHandler(KeyEvent.KEY_PRESSED) { leaked += it.code } }
        val event = KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.LEFT, false, true, false, false)

        robot.interact { Event.fireEvent(view.board, event) }

        // Not consumed by the board: the event bubbled on to the root, where the menu accelerators see it.
        assertEquals(listOf(KeyCode.LEFT), leaked)
        assertEquals(before, texts())
    }

    @Test
    fun `focusing the tab content hands the focus to the board`(robot: FxRobot) {
        robot.interact { view.sizeMenu.requestFocus() }
        assertFalse(fx { view.board.isFocused })

        robot.interact { view.node.requestFocus() }

        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `the tiles follow the size of the window`(robot: FxRobot) {
        val large = fx { view.board.prefWidth }

        robot.interact {
            stage.width = 380.0
            stage.height = 520.0
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertTrue(fx { view.board.prefWidth } < large)
    }

    @Test
    fun `a drag to the left moves the tiles`(robot: FxRobot) {
        robot.interact {
            Event.fireEvent(view.board, mouse(MouseEvent.MOUSE_PRESSED, 200.0))
            Event.fireEvent(view.board, mouse(MouseEvent.MOUSE_RELEASED, 140.0))
        }

        assertEquals("4", texts()[Cell(0, 0)])
        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    private fun mouse(
        type: javafx.event.EventType<MouseEvent>,
        x: Double,
    ) = MouseEvent(
        type,
        x,
        50.0,
        x,
        50.0,
        MouseButton.PRIMARY,
        1,
        false,
        false,
        false,
        false,
        true,
        false,
        false,
        false,
        false,
        true,
        null,
    )

    @Test
    fun `a move followed at once by another ends in the final state with no leftover nodes`(robot: FxRobot) {
        robot.interact { view.board.animationDuration = Duration.millis(100.0) }
        focusBoard(robot)

        robot.interact {
            Event.fireEvent(
                view.board,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.LEFT, false, false, false, false),
            )
            Event.fireEvent(
                view.board,
                KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, false, false, false, false),
            )
        }

        // Both animations end by themselves; poll until the board shows exactly the model's tiles, at rest.
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) {
            fx { view.board.nodeCount() == model.tiles.size && view.board.nodesSettled() }
        }
        assertEquals(fx { model.tiles }.associate { it.cell to it.value.toString() }, texts())
    }
}
