package dev.sluide.games.minesweeper.ui

import dev.sluide.games.common.ui.firePress
import dev.sluide.games.minesweeper.BoardSize
import dev.sluide.games.minesweeper.CellKind
import dev.sluide.games.minesweeper.MinesweeperGame
import dev.sluide.games.minesweeper.MinesweeperModel
import dev.sluide.games.minesweeper.MinesweeperPreferences
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.scene.Group
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
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
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Each connection of the Minesweeper view to its model needs a scene: mouse clicks on cells, key presses with the
 * focus, the pause when the content leaves the scene and the size menu. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MinesweeperViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: MinesweeperModel
    private lateinit var view: MinesweeperView
    private lateinit var stage: Stage

    /** A column of mines at x = 4 and one at the top left corner. */
    private val columnMines = (0..8).map { 4 to it }.toSet() + (0 to 0)

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = MinesweeperPreferences(store, Duration.ZERO, Executor { it.run() })
        model =
            MinesweeperModel(
                preferences,
                gameFactory = { size ->
                    if (size.mines ==
                        10
                    ) {
                        MinesweeperGame(size, columnMines)
                    } else {
                        MinesweeperGame(size)
                    }
                },
            )
        view = MinesweeperView(model)
        stage.scene = Scene(view.node as Parent, 700.0, 600.0)
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun click(
        robot: FxRobot,
        x: Int,
        y: Int,
        button: MouseButton,
    ) = robot.interact {
        val at = view.board.localToScene(view.board.cellCenter(x, y))
        val secondary = button == MouseButton.SECONDARY
        Event.fireEvent(
            view.board,
            MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                at.x,
                at.y,
                at.x,
                at.y,
                button,
                1,
                false,
                false,
                false,
                false,
                !secondary,
                false,
                secondary,
                false,
                secondary,
                true,
                null,
            ),
        )
    }

    /** Fires the key at the board; Ctrl is the Shortcut key on Linux. */
    private fun press(
        robot: FxRobot,
        code: KeyCode,
        shortcut: Boolean = false,
    ): KeyEvent {
        val event = KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut, false, false)
        robot.interact { Event.fireEvent(view.board, event) }
        return event
    }

    @Test
    fun `a primary click opens a cell and a secondary click flags another`(robot: FxRobot) {
        val scrollPane = view.node.lookup(".scroll-pane")
        var scrollPaneFocused = false
        robot.interact {
            scrollPane.focusedProperty().addListener { _, _, focused ->
                scrollPaneFocused =
                    scrollPaneFocused || focused
            }
        }

        click(robot, 8, 8, MouseButton.PRIMARY)
        click(robot, 0, 8, MouseButton.SECONDARY)

        // A press the board consumes never reaches the scroll pane, which would take the focus and hand it back.
        assertFalse(scrollPaneFocused)
        assertSame(view.board, fx { stage.scene.focusOwner })
        assertEquals(CellKind.OPENED, fx { model.game.cell(8, 8) })
        assertEquals(CellKind.FLAGGED, fx { model.game.cell(0, 8) })
        assertEquals("9", fx { model.counterText })
    }

    @Test
    fun `key presses reach the model`(robot: FxRobot) {
        click(robot, 8, 8, MouseButton.PRIMARY)

        press(robot, KeyCode.RIGHT)
        press(robot, KeyCode.SPACE)

        assertEquals(5, fx { model.cursorX })
        assertEquals(CellKind.OPENED, fx { model.game.cell(5, 4) })

        // Shortcut+P belongs to the menu accelerators.
        assertFalse(press(robot, KeyCode.P, shortcut = true).isConsumed)
        assertFalse(fx { model.isPaused })
    }

    @Test
    fun `focusing the tab content hands the focus to the board`(robot: FxRobot) {
        robot.interact { view.node.requestFocus() }

        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a mouse press on the empty area around the board leaves the focus on the board`(robot: FxRobot) {
        robot.interact { view.sizeMenu.requestFocus() }
        assertFalse(fx { view.board.isFocused })

        robot.interact { firePress(view.node.lookup(".scroll-pane")) }

        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `focusing the content of a new view focuses its board once the scroll pane has put it in the scene`(
        robot: FxRobot,
    ) {
        val other = TextField()
        val root = VBox(other)
        robot.interact {
            stage.scene.root = root
            other.requestFocus()
        }
        val fresh = MinesweeperView(model)

        // Added and focused in one turn, before the layout pulse: the board is not in the scene yet.
        robot.interact {
            root.children += fresh.node
            assertNull(fresh.board.scene)
            fresh.node.requestFocus()
        }

        assertSame(fresh.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a disposed view leaves the model to the view that took it over`(robot: FxRobot) {
        lateinit var second: MinesweeperView
        robot.interact {
            second = MinesweeperView(model)
            view.dispose()
            stage.scene.root = second.node as Parent
        }
        robot.interact {
            model.reveal(8, 8)
            model.togglePause()
        }

        assertTrue(fx { second.overlay.isVisible })
        assertFalse(fx { view.overlay.isVisible })

        robot.interact { second.dispose() }
        assertTrue(fx { model.hidden })
    }

    @Test
    fun `the game pauses with a cover while the view is out of the scene and resumes when it is back`(robot: FxRobot) {
        click(robot, 8, 8, MouseButton.PRIMARY)
        assertFalse(fx { view.overlay.isVisible })

        robot.interact { stage.scene.root = Group() }
        assertTrue(fx { model.isPaused })
        assertTrue(fx { view.overlay.isVisible })

        robot.interact { stage.scene.root = view.node as Parent }
        assertFalse(fx { model.isPaused })
        assertFalse(fx { view.overlay.isVisible })
    }

    @Test
    fun `the size menu starts a new board, also a custom one`(robot: FxRobot) {
        fun choose(label: String) =
            robot.interact {
                view.sizeMenu.items
                    .first { it.text == label }
                    .fire()
            }
        var answer: BoardSize? = null
        view.askCustomSize = { _, _ -> answer }

        choose("Expert")
        assertEquals(480, fx { view.board.childrenUnmodifiable.size })
        assertEquals("Expert", fx { view.sizeMenu.text })

        choose("Custom…")
        assertEquals(480, fx { view.board.childrenUnmodifiable.size })
        assertEquals("Expert", fx { view.sizeMenu.text })

        answer = BoardSize(6, 7, 5)
        choose("Custom…")
        assertEquals(42, fx { view.board.childrenUnmodifiable.size })
        assertEquals("Custom 6×7/5", fx { view.sizeMenu.text })
    }
}
