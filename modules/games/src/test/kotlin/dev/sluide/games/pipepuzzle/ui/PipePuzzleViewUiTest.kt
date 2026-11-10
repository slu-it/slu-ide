package dev.sluide.games.pipepuzzle.ui

import dev.sluide.games.common.GameClock
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.common.ZoomRange
import dev.sluide.games.pipepuzzle.BoardSize
import dev.sluide.games.pipepuzzle.Generator
import dev.sluide.games.pipepuzzle.Look
import dev.sluide.games.pipepuzzle.Mode
import dev.sluide.games.pipepuzzle.Openings
import dev.sluide.games.pipepuzzle.PipePuzzleModel
import dev.sluide.games.pipepuzzle.PipePuzzlePreferences
import dev.sluide.games.pipepuzzle.Pos
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.geometry.Point2D
import javafx.scene.Scene
import javafx.scene.control.ScrollPane
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Priority
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The connections of the Pipe Puzzle view to its model that need a scene: clicks and keys on the canvas, the toolbar,
 * the confirm hook, the overlay and the tab visibility. Needs a display.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class PipePuzzleViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: PipePuzzleModel
    private lateinit var view: PipePuzzleView
    private lateinit var stage: Stage
    private lateinit var zoomPreferences: GameZoomPreferences
    private val asked = mutableListOf<List<String>>()
    private var answer = false
    private var now = 1000L

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = PipePuzzlePreferences(store, Duration.ZERO) { it.run() }
        preferences.lastSize = BoardSize.S5
        zoomPreferences = GameZoomPreferences(store, Duration.ZERO) { it.run() }
        val generator = Generator()
        val sync = Executor { it.run() }
        model =
            PipePuzzleModel(
                preferences,
                { mode, size -> generator.generate(mode, size, Random(1)) },
                sync,
                sync,
                GameClock(),
            )
        view = PipePuzzleView(model, zoomPreferences) { now }
        view.confirm = { title, header, verb, content ->
            asked += listOf(title, header, verb, content)
            answer
        }
        VBox.setVgrow(view.node, Priority.ALWAYS)
        stage.scene = Scene(VBox(view.node), 600.0, 700.0)
        // The primary stage is shared by the UI test classes; a size set by an earlier test would outlive the scene.
        stage.sizeToScene()
        stage.show()
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun tiles(): List<Openings> =
        fx {
            val game = checkNotNull(model.game)
            (0 until game.side * game.side).map { game.tile(Pos(it / game.side, it % game.side)) }
        }

    /** A tile that a turn changes. */
    private fun turnable(): Pos {
        val game = checkNotNull(fx { model.game })
        return (0 until game.side * game.side)
            .map { Pos(it / game.side, it % game.side) }
            .first { !game.puzzle.isFixed(it) && game.tile(it).rotatedCw() != game.tile(it) }
    }

    private fun press(
        robot: FxRobot,
        code: KeyCode,
        shift: Boolean = false,
    ) = robot.interact {
        Event.fireEvent(view.board, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false))
    }

    private fun click(
        robot: FxRobot,
        pos: Pos,
        button: MouseButton,
    ) {
        val geometry = fx { view.board.geometry() }
        clickAt(
            robot,
            Point2D(geometry.cellX(pos) + geometry.cellSize / 2, geometry.cellY(pos) + geometry.cellSize / 2),
            button,
        )
    }

    /** A press at [local], in the board's coordinates. */
    private fun clickAt(
        robot: FxRobot,
        local: Point2D,
        button: MouseButton,
    ) = robot.interact {
        // The event's coordinates are the scene's: they are converted to the board's on delivery.
        val at = view.board.localToScene(local)
        val x = at.x
        val y = at.y
        val event =
            MouseEvent(
                MouseEvent.MOUSE_PRESSED,
                x,
                y,
                x,
                y,
                button,
                1,
                false,
                false,
                false,
                false,
                button == MouseButton.PRIMARY,
                false,
                button == MouseButton.SECONDARY,
                false,
                false,
                true,
                null,
            )
        Event.fireEvent(view.board, event)
    }

    @Test
    fun `a primary click turns the tile clockwise and a secondary click counter-clockwise`(robot: FxRobot) {
        val pos = turnable()
        val before = fx { checkNotNull(model.game).tile(pos) }

        click(robot, pos, MouseButton.PRIMARY)

        assertEquals(before.rotatedCw(), fx { checkNotNull(model.game).tile(pos) })

        click(robot, pos, MouseButton.SECONDARY)

        assertEquals(before, fx { checkNotNull(model.game).tile(pos) })
    }

    @Test
    fun `arrow keys move the cursor and Space turns the tile there`(robot: FxRobot) {
        press(robot, KeyCode.DOWN)
        press(robot, KeyCode.RIGHT)
        val at = checkNotNull(fx { model.cursor })
        val game = checkNotNull(fx { model.game })
        assertTrue(!game.puzzle.isFixed(at) && game.tile(at).rotatedCw() != game.tile(at), "cursor tile turnable")
        val before = fx { game.tile(at) }

        press(robot, KeyCode.SPACE)

        assertEquals(before.rotatedCw(), fx { game.tile(at) })
    }

    @Test
    fun `choosing a Look keeps the game and changes the model's look`(robot: FxRobot) {
        val before = tiles()

        robot.interact { view.lookItems.getValue(Look.POWER).fire() }

        assertEquals(Look.POWER, fx { model.look })
        assertEquals(before, tiles())
        assertEquals("Power", fx { view.lookMenu.text })
    }

    @Test
    fun `the Hint button turns a tile`(robot: FxRobot) {
        val before = tiles()

        robot.interact { view.hintButton.fire() }

        assertEquals(1, fx { checkNotNull(model.game).hints })
        assertNotEquals(before, tiles())
    }

    @Test
    fun `Restart asks once a turn was made and keeps the turn when cancelled`(robot: FxRobot) {
        val pos = turnable()
        val start = tiles()
        robot.interact { view.restartButton.fire() }
        assertTrue(asked.isEmpty(), "no question before a turn")
        click(robot, pos, MouseButton.PRIMARY)
        val turned = tiles()
        assertNotEquals(start, turned)

        answer = false
        robot.interact { view.restartButton.fire() }

        assertEquals("Restart this puzzle?", asked.single()[1])
        assertEquals(turned, tiles())

        answer = true
        robot.interact { view.restartButton.fire() }

        assertEquals(start, tiles())
    }

    @Test
    fun `Pause shows the overlay and the board ignores clicks`(robot: FxRobot) {
        val pos = turnable()
        val before = tiles()

        press(robot, KeyCode.P)

        assertTrue(fx { view.overlay.isVisible })
        click(robot, pos, MouseButton.PRIMARY)
        assertEquals(before, tiles())

        press(robot, KeyCode.P)

        assertFalse(fx { view.overlay.isVisible })
    }

    @Test
    fun `taking the tab out of the scene pauses the clock`(robot: FxRobot) {
        click(robot, turnable(), MouseButton.PRIMARY)
        assertFalse(fx { model.isPaused })

        robot.interact { stage.scene.root = VBox() }

        assertTrue(fx { model.hidden })
        assertTrue(fx { model.isPaused })

        robot.interact { stage.scene.root = VBox(view.node) }

        assertFalse(fx { model.isPaused })
    }

    @Test
    fun `R asks the restart question and keeps the tiles when cancelled`(robot: FxRobot) {
        click(robot, turnable(), MouseButton.PRIMARY)
        val turned = tiles()

        press(robot, KeyCode.R)

        assertEquals("Restart this puzzle?", asked.single()[1])
        assertEquals(turned, tiles())
    }

    @Test
    fun `R while paused neither asks nor restarts`(robot: FxRobot) {
        click(robot, turnable(), MouseButton.PRIMARY)
        val turned = tiles()
        press(robot, KeyCode.P)
        answer = true

        press(robot, KeyCode.R)

        assertTrue(asked.isEmpty())
        assertEquals(turned, tiles())
    }

    @Test
    fun `a mode change asks once a turn was made and a cancel puts the old mode back`(robot: FxRobot) {
        click(robot, turnable(), MouseButton.PRIMARY)

        robot.interact { view.modeItems.getValue(Mode.PATH).fire() }

        assertEquals("Abandon this puzzle?", asked.single()[1])
        assertEquals(Mode.NETWORK, fx { model.mode })
        assertEquals("Network", fx { view.modeMenu.text })
        assertTrue(fx { view.modeItems.getValue(Mode.NETWORK).isSelected })
        assertFalse(fx { view.modeItems.getValue(Mode.PATH).isSelected })

        answer = true
        robot.interact { view.modeItems.getValue(Mode.PATH).fire() }

        assertEquals(Mode.PATH, fx { model.mode })
    }

    @Test
    fun `a size change asks once a turn was made and a cancel puts the old size back`(robot: FxRobot) {
        click(robot, turnable(), MouseButton.PRIMARY)

        robot.interact { view.sizeItems.getValue(BoardSize.S7).fire() }

        assertEquals("Abandon this puzzle?", asked.single()[1])
        assertEquals(BoardSize.S5, fx { model.size })
        assertEquals("5×5", fx { view.sizeMenu.text })
        assertTrue(fx { view.sizeItems.getValue(BoardSize.S5).isSelected })
        assertFalse(fx { view.sizeItems.getValue(BoardSize.S7).isSelected })

        answer = true
        robot.interact { view.sizeItems.getValue(BoardSize.S7).fire() }

        assertEquals(BoardSize.S7, fx { model.size })
    }

    @Test
    fun `the New Game button and F2 ask once a turn was made`(robot: FxRobot) {
        val game = fx { model.game }
        robot.interact { view.newGameButton.fire() }
        assertTrue(asked.isEmpty(), "no question before a turn")
        click(robot, turnable(), MouseButton.PRIMARY)
        val current = fx { model.game }
        assertNotSame(game, current)

        robot.interact { view.newGameButton.fire() }
        press(robot, KeyCode.F2)

        assertEquals(listOf("New Game", "New Game"), asked.map { it[0] })
        assertSame(current, fx { model.game })

        answer = true
        press(robot, KeyCode.F2)

        assertNotSame(current, fx { model.game })
    }

    @Test
    fun `a turn and the win start the canvas animation, and a restart ends the flow`(robot: FxRobot) {
        assertFalse(fx { view.board.isAnimating })

        click(robot, turnable(), MouseButton.PRIMARY)

        assertTrue(fx { view.board.isAnimating })
        now += 200
        assertFalse(fx { view.board.isAnimating })

        robot.interact { while (model.game?.isSolved == false) model.hint() }

        assertTrue(fx { view.board.isAnimating }, "the flow runs")
        now += 10_000
        assertFalse(fx { view.board.isAnimating })
    }

    @Test
    fun `the zoom resizes the cells, is saved, scrolls the board and keeps clicks on their tiles`(robot: FxRobot) {
        fun cell() = fx { view.board.geometry().cellSize }
        val fit = cell()
        assertEquals(BoardGeometry.MAX_CELL, fit)

        robot.interact { view.zoomControls.zoomTo(2.0) }
        assertEquals(2 * fit, cell())
        assertEquals(2.0, fx { zoomPreferences.zoom(PipePuzzleView.GAME_ID, ZoomRange.ABOVE_FIT) })

        // 5 cells of 144 px are wider than the 600 px tab: the board is larger than the viewport and scrolls.
        val scroll = fx { view.node.lookup(".pp-scroll") as ScrollPane }
        assertTrue(fx { view.board.width > scroll.viewportBounds.width })

        // A click at the middle of a tile of the top left corner, with the origin and cell computed here.
        val game = checkNotNull(fx { model.game })
        val pos =
            (0..3)
                .flatMap { row -> (0..3).map { col -> Pos(row, col) } }
                .first { !game.puzzle.isFixed(it) && game.tile(it).rotatedCw() != game.tile(it) }
        val before = fx { game.tile(pos) }
        val originX = fx { Math.floor(maxOf(0.0, (view.board.width - 5 * 144.0) / 2)) }
        val originY = fx { Math.floor(maxOf(0.0, (view.board.height - 5 * 144.0) / 2)) }
        val local = Point2D(originX + pos.col * 144.0 + 72.0, originY + pos.row * 144.0 + 72.0)
        clickAt(robot, local, MouseButton.PRIMARY)

        assertEquals(before.rotatedCw(), fx { game.tile(pos) })
        assertTrue(fx { view.board.isFocused })
    }

    @Test
    fun `Restart stays available after a win and ends a running flow`(robot: FxRobot) {
        robot.interact { while (model.game?.isSolved == false) model.hint() }
        assertTrue(fx { view.board.isAnimating })
        assertFalse(fx { view.restartButton.isDisable })

        robot.interact { view.restartButton.fire() }

        assertFalse(fx { checkNotNull(model.game).isSolved })
        assertFalse(fx { view.board.isAnimating })
    }

    @Test
    fun `a disposed view leaves the callbacks to the view that took over`(robot: FxRobot) {
        lateinit var second: PipePuzzleView
        robot.interact {
            second = PipePuzzleView(model, zoomPreferences) { now }
            view.dispose()
            stage.scene.root = VBox(second.node)
        }
        assertFalse(fx { model.hidden })

        val pos = turnable()
        robot.interact { model.turn(pos, true) }

        assertTrue(fx { second.board.isAnimating })
        assertFalse(fx { view.board.isAnimating })
        now += 200

        robot.interact { while (model.game?.isSolved == false) model.hint() }

        assertTrue(fx { second.board.isAnimating })
        assertFalse(fx { view.board.isAnimating })

        robot.interact { second.dispose() }

        assertTrue(fx { model.hidden })
    }
}
