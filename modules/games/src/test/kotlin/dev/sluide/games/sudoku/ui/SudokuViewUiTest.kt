package dev.sluide.games.sudoku.ui

import dev.sluide.games.common.GameClock
import dev.sluide.games.common.ui.firePress
import dev.sluide.games.sudoku.Cell
import dev.sluide.games.sudoku.Generator
import dev.sluide.games.sudoku.Grid
import dev.sluide.games.sudoku.Level
import dev.sluide.games.sudoku.Puzzle
import dev.sluide.games.sudoku.SudokuModel
import dev.sluide.games.sudoku.SudokuPreferences
import dev.sluide.games.sudoku.SudokuSize
import dev.sluide.games.sudoku.Tier
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.TextField
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
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
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The connections of the Sudoku view to its model that need a scene: clicks and key events with the focus, the pad,
 * the toolbar menus, the guard dialog's stub, the overlay and the arrangement of board and pad. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class SudokuViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: SudokuModel
    private lateinit var view: SudokuView
    private lateinit var stage: Stage
    private val asked = mutableListOf<List<String>>()
    private var answer = false
    private lateinit var decoy: TextField

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        decoy = TextField()
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = SudokuPreferences(store, java.time.Duration.ZERO) { it.run() }
        preferences.save(SudokuSize.FOUR, Level.MEDIUM)
        val real = Generator(Random(1))
        val sync = Executor { it.run() }
        model =
            SudokuModel(
                preferences,
                { size, level -> if (size == SudokuSize.FOUR) fixedPuzzle(level) else real.generate(size, level) },
                sync,
                sync,
                GameClock(),
            )
        view = SudokuView(model)
        view.flashDuration = Duration.ZERO
        view.confirm = { title, header, verb, content ->
            asked += listOf(title, header, verb, content)
            answer
        }
        VBox.setVgrow(view.node, Priority.ALWAYS)
        stage.scene = Scene(VBox(decoy, view.node), 600.0, 700.0)
        // The primary stage is shared by the UI test classes; a size set by an earlier test would outlive the scene.
        stage.sizeToScene()
        stage.show()
    }

    private fun fixedPuzzle(level: Level) =
        Puzzle(
            SudokuSize.FOUR,
            level,
            Grid.of(SudokuSize.FOUR, listOf("12.4", "34.2", ".1.3", "4.2.")),
            Grid.of(SudokuSize.FOUR, listOf("1234", "3412", "2143", "4321")),
            Tier.T1,
        )

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun press(
        robot: FxRobot,
        code: KeyCode,
        shift: Boolean = false,
        shortcut: Boolean = false,
    ) = robot.interact {
        val event =
            KeyEvent(
                KeyEvent.KEY_PRESSED,
                "",
                "",
                code,
                shift,
                shortcut && !shortcutIsMeta,
                false,
                shortcut && shortcutIsMeta,
            )
        Event.fireEvent(view.board, event)
    }

    private fun focusDecoy(robot: FxRobot) {
        robot.interact { decoy.requestFocus() }
        assertSame(decoy, fx { stage.scene.focusOwner })
    }

    private fun click(
        robot: FxRobot,
        cell: Cell,
    ) = robot.interact {
        val event =
            MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                5.0,
                5.0,
                5.0,
                5.0,
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
        Event.fireEvent(view.board.cellRoot(cell), event)
    }

    private fun digit(cell: Cell) = fx { view.board.digitText(cell) }

    private fun styles(cell: Cell) =
        fx {
            view.board
                .cellRoot(cell)
                .styleClass
                .toList()
        }

    private val empty = Cell(0, 2)

    /** Whether Shortcut is Cmd (macOS) rather than Ctrl. */
    private val shortcutIsMeta = System.getProperty("os.name").startsWith("Mac")

    @Test
    fun `a click selects a cell and a digit key places the digit`(robot: FxRobot) {
        focusDecoy(robot)
        click(robot, empty)

        assertTrue("sdk-selected" in styles(empty))
        assertSame(view.board, fx { stage.scene.focusOwner })
        press(robot, KeyCode.DIGIT3)

        assertEquals("3", digit(empty))
        assertTrue("sdk-entry" in styles(empty))
    }

    @Test
    fun `a mouse press on the empty area around the board leaves the focus on the board`(robot: FxRobot) {
        focusDecoy(robot)

        robot.interact { firePress(view.node.lookup(".scroll-pane")) }

        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `Shift and a digit adds a note`(robot: FxRobot) {
        click(robot, empty)

        press(robot, KeyCode.DIGIT2, shift = true)

        assertEquals(setOf(2), fx { view.board.noteDigits(empty) })
        assertEquals("", digit(empty))
    }

    @Test
    fun `a pad click places the digit and counts it`(robot: FxRobot) {
        click(robot, empty)
        focusDecoy(robot)
        assertEquals("2", fx { view.pad.remainingText(3) })

        robot.interact { view.pad.digitButton(3).fire() }

        assertEquals("3", digit(empty))
        assertEquals("1", fx { view.pad.remainingText(3) })
        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a digit that clashes gets the conflict style on both cells`(robot: FxRobot) {
        click(robot, empty)

        press(robot, KeyCode.DIGIT1)

        assertTrue("sdk-conflict" in styles(empty))
        assertTrue("sdk-conflict" in styles(Cell(0, 0)))
        assertFalse("sdk-conflict" in styles(Cell(1, 0)))
    }

    @Test
    fun `H fills the selected cell and adds the penalty to the timer`(robot: FxRobot) {
        click(robot, empty)

        press(robot, KeyCode.H)

        assertEquals("3", digit(empty))
        assertEquals("0:30", fx { view.timer.text })
    }

    @Test
    fun `H on a wrong entry flashes the cell and says so`(robot: FxRobot) {
        robot.interact { view.flashDuration = Duration.seconds(5.0) }
        click(robot, empty)
        press(robot, KeyCode.DIGIT2)

        press(robot, KeyCode.H)

        assertEquals("This 2 was wrong", fx { view.status.text })
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { fx { view.board.flashOpacity(empty) } > 0.0 }
    }

    @Test
    fun `U undoes and so does the shortcut with Z`(robot: FxRobot) {
        click(robot, empty)
        press(robot, KeyCode.DIGIT3)
        assertFalse(fx { view.undoButton.isDisable })

        press(robot, KeyCode.U)

        assertEquals("", digit(empty))
        assertTrue(fx { view.undoButton.isDisable })
        assertFalse(fx { view.redoButton.isDisable })
        press(robot, KeyCode.DIGIT3)

        val leaked = mutableListOf<KeyCode>()
        robot.interact { view.node.addEventHandler(KeyEvent.KEY_PRESSED) { leaked += it.code } }
        press(robot, KeyCode.Z, shortcut = true)

        assertEquals("", digit(empty))
        // The board consumed the event: it never bubbled up to the root.
        assertTrue(leaked.isEmpty())
    }

    @Test
    fun `F2 with an entry asks first and only accepting gives a fresh puzzle`(robot: FxRobot) {
        click(robot, empty)
        press(robot, KeyCode.DIGIT3)

        press(robot, KeyCode.F2)

        assertEquals(1, asked.size)
        assertEquals(listOf("New Game", "Abandon this puzzle?", "New Game"), asked.single().take(3))
        assertTrue(asked.single()[3].startsWith("Its time of 0:0"))
        assertEquals("3", digit(empty))

        answer = true
        press(robot, KeyCode.F2)

        assertEquals(2, asked.size)
        assertEquals("", digit(empty))
    }

    @Test
    fun `the size menu switches to 6x6`(robot: FxRobot) {
        val six = view.sizeMenu.items[SudokuSize.SIX.ordinal] as RadioMenuItem

        // The real menu selects the item, then fires it.
        robot.interact {
            six.isSelected = true
            six.fire()
        }

        assertTrue(asked.isEmpty())
        assertEquals(SudokuSize.SIX, fx { model.size })
        assertEquals("6×6", fx { view.sizeMenu.text })
        assertEquals(36, fx { view.board.children.count { "sdk-cell" in it.styleClass } })
    }

    @Test
    fun `P shows the pause overlay and the next P hides it`(robot: FxRobot) {
        assertFalse(fx { view.overlay.isVisible })
        click(robot, empty)

        press(robot, KeyCode.P)

        assertTrue(fx { view.overlay.isVisible })
        press(robot, KeyCode.P)

        assertFalse(fx { view.overlay.isVisible })
    }

    @Test
    fun `a wide stage puts the pad beside the board and a narrow and tall one below it`(robot: FxRobot) {
        robot.interact {
            stage.width = 900.0
            stage.height = 500.0
        }
        WaitForAsyncUtils.waitForFxEvents()
        assertTrue(fx { view.padBeside })
        assertTrue(fx { view.pad.parent is HBox })

        robot.interact {
            stage.width = 360.0
            stage.height = 800.0
        }
        WaitForAsyncUtils.waitForFxEvents()

        assertFalse(fx { view.padBeside })
        assertTrue(fx { view.pad.parent is VBox })
    }

    @Test
    fun `Restart asks only with entries and a refusal keeps them`(robot: FxRobot) {
        robot.interact { view.restartButton.fire() }
        assertTrue(asked.isEmpty())
        click(robot, empty)
        press(robot, KeyCode.DIGIT3)

        robot.interact { view.restartButton.fire() }

        assertEquals(
            listOf(
                "Restart",
                "Clear all entries?",
                "Restart",
                "The puzzle starts over from its givens; the clock keeps running.",
            ),
            asked.single(),
        )
        assertEquals("3", digit(empty))

        answer = true
        robot.interact { view.restartButton.fire() }

        assertEquals("", digit(empty))
    }

    @Test
    fun `refusing a size change restores the menu and a level change asks the same way`(robot: FxRobot) {
        click(robot, empty)
        press(robot, KeyCode.DIGIT3)
        val four = view.sizeMenu.items[SudokuSize.FOUR.ordinal] as RadioMenuItem
        val six = view.sizeMenu.items[SudokuSize.SIX.ordinal] as RadioMenuItem

        robot.interact {
            six.isSelected = true
            six.fire()
        }

        assertEquals("Change Size", asked.single()[0])
        assertEquals("Abandon this puzzle?", asked.single()[1])
        assertEquals(SudokuSize.FOUR, fx { model.size })
        assertTrue(fx { four.isSelected })
        assertFalse(fx { six.isSelected })
        assertEquals("4×4", fx { view.sizeMenu.text })

        val hard = view.levelMenu.items[Level.HARD.ordinal] as RadioMenuItem
        robot.interact {
            hard.isSelected = true
            hard.fire()
        }

        assertEquals("Change Level", asked.last()[0])
        assertEquals(Level.MEDIUM, fx { model.level })
        assertFalse(fx { hard.isSelected })

        answer = true
        robot.interact {
            hard.isSelected = true
            hard.fire()
        }

        assertEquals(Level.HARD, fx { model.level })
        assertEquals("Hard", fx { view.levelMenu.text })
    }

    @Test
    fun `the toolbar and pad buttons act on the selected cell`(robot: FxRobot) {
        click(robot, empty)

        robot.interact { view.notesButton.fire() }
        assertTrue(fx { model.notesMode })
        robot.interact { view.pad.digitButton(2).fire() }
        assertEquals(setOf(2), fx { view.board.noteDigits(empty) })
        robot.interact { view.pad.notes.fire() }
        assertFalse(fx { model.notesMode })
        robot.interact { view.pad.eraseButton().fire() }
        assertEquals(emptySet(), fx { view.board.noteDigits(empty) })
        robot.interact { view.fillNotesButton.fire() }
        assertTrue(fx { view.board.noteDigits(empty).isNotEmpty() })
        robot.interact { view.hintButton.fire() }
        assertEquals("3", digit(empty))
        robot.interact { view.pauseButton.fire() }
        assertTrue(fx { view.overlay.isVisible })
        robot.interact { view.pauseButton.fire() }
        assertFalse(fx { view.overlay.isVisible })
        press(robot, KeyCode.DIGIT1)
        robot.interact { view.newGameButton.fire() }
        assertEquals(1, asked.size)
    }

    @Test
    fun `while paused the step controls and the pad are disabled`(robot: FxRobot) {
        click(robot, empty)
        press(robot, KeyCode.DIGIT3)

        press(robot, KeyCode.P)

        listOf(view.restartButton, view.undoButton, view.fillNotesButton, view.hintButton, view.notesButton)
            .forEach { assertTrue(fx { it.isDisable }) }
        assertTrue(fx { view.pad.isDisable })
        assertFalse(fx { view.pauseButton.isDisable })
        press(robot, KeyCode.P)

        assertFalse(fx { view.restartButton.isDisable })
        assertFalse(fx { view.pad.isDisable })
    }

    @Test
    fun `a disposed view leaves both callbacks to the view that took over and pauses the game`(robot: FxRobot) {
        lateinit var second: SudokuView
        robot.interact {
            second = SudokuView(model)
            second.flashDuration = Duration.seconds(5.0)
            view.dispose()
            stage.scene.root = VBox(decoy, second.node)
            model.selected = empty
            model.input(2)
            model.hint()
        }
        // The hint removed the wrong 2: the second view shows the status and flashes, the first does neither.
        assertEquals("This 2 was wrong", fx { second.status.text })
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS) { fx { second.board.flashOpacity(empty) } > 0.0 }
        assertEquals(0.0, fx { view.board.flashOpacity(empty) })
        assertFalse(fx { view.status.text.startsWith("This") })

        robot.interact { second.dispose() }

        assertTrue(fx { model.hidden })
    }
}
