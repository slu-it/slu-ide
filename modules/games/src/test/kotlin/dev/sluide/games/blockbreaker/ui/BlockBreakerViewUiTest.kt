package dev.sluide.games.blockbreaker.ui

import dev.sluide.games.blockbreaker.BlockBreakerGame
import dev.sluide.games.blockbreaker.BlockBreakerModel
import dev.sluide.games.blockbreaker.BlockBreakerPreferences
import dev.sluide.games.blockbreaker.BrickKind
import dev.sluide.games.blockbreaker.Difficulty
import dev.sluide.games.blockbreaker.FixedRandom
import dev.sluide.games.blockbreaker.PowerUp
import dev.sluide.games.blockbreaker.Rules
import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.common.SessionState
import dev.sluide.games.common.ZoomRange
import dev.sluide.games.common.ui.FieldLayout
import dev.sluide.games.common.ui.firePress
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.image.WritableImage
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The connections of the Block Breaker view to its model and session that need a scene: key events, the mouse (click
 * order against the focus, pointer, hidden cursor), the focus listener and the pause overlay, the toolbar, the abandon
 * question, the handover of the model and the canvas. Needs a display. The rules are tested in the model tests.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class BlockBreakerViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: BlockBreakerModel
    private lateinit var zoomPreferences: GameZoomPreferences
    private lateinit var view: BlockBreakerView
    private lateinit var stage: Stage
    private lateinit var other: TextField
    private val asked = mutableListOf<Triple<String, String, Int>>()
    private var answer = false
    private var withScore = false

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = BlockBreakerPreferences(store, java.time.Duration.ZERO) { it.run() }
        model =
            BlockBreakerModel(preferences) { difficulty, _ ->
                BlockBreakerGame(difficulty, FixedRandom()).also { if (withScore) it.addScore(100) }
            }
        zoomPreferences = GameZoomPreferences(store, java.time.Duration.ZERO) { it.run() }
        view = BlockBreakerView(model, zoomPreferences)
        view.confirmAbandon = { title, verb, score ->
            asked += Triple(title, verb, score)
            answer
        }
        other = TextField()
        VBox.setVgrow(view.node, Priority.ALWAYS)
        stage.scene = Scene(VBox(view.node, other), 900.0, 600.0)
        // The primary stage is shared by the UI test classes; a size set by an earlier test would outlive the scene.
        stage.sizeToScene()
        stage.show()
    }

    // No test leaves a listener on the static window list.
    @AfterEach
    fun disposeView() {
        fx { view.dispose() }
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun press(
        robot: FxRobot,
        code: KeyCode,
    ) = robot.interact {
        Event.fireEvent(view.board, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false))
    }

    private fun release(
        robot: FxRobot,
        code: KeyCode,
    ) = robot.interact {
        Event.fireEvent(view.board, KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false))
    }

    private fun mouse(
        robot: FxRobot,
        type: javafx.event.EventType<MouseEvent>,
        x: Double,
        y: Double = 100.0,
        target: Node = view.board,
    ) = robot.interact {
        Event.fireEvent(
            target,
            MouseEvent(
                type,
                x,
                y,
                x,
                y,
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
            ),
        )
    }

    private fun focusBoard(robot: FxRobot) = robot.interact { view.board.requestFocus() }

    private fun state(): SessionState = fx { model.state }

    private fun startCountdown(robot: FxRobot) {
        focusBoard(robot)
        press(robot, KeyCode.SPACE)
        release(robot, KeyCode.SPACE)
        assertEquals(SessionState.COUNTDOWN, state())
    }

    private fun pauseByFocus(robot: FxRobot) {
        startCountdown(robot)
        robot.interact { other.requestFocus() }
        assertEquals(SessionState.PAUSED, state())
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            if (fx(condition)) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun playing(robot: FxRobot) {
        startCountdown(robot)
        assertTrue(waitUntil { model.state == SessionState.PLAYING })
    }

    @Test
    fun `a mouse press on the status line returns the focus to the board`(robot: FxRobot) {
        robot.interact { view.node.lookup(".button").requestFocus() }
        assertFalse(fx { view.board.isFocused })

        robot.interact { firePress(view.status) }

        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `Space on the focused board reaches the model and starts the countdown`(robot: FxRobot) {
        startCountdown(robot)
    }

    /** The board x of the field x [fieldX]: the board is wider than the field, so the raw x is far from it. */
    private fun boardX(fieldX: Double): Double =
        fx {
            val place =
                FieldLayout(BlockBreakerGame.WIDTH, BlockBreakerGame.HEIGHT)
                    .fit(view.board.width, view.board.height, 1.0, 1.0)
            place.offsetX + fieldX * place.scale
        }

    private fun paddleCentre(): Double = fx { model.game.paddleX + model.game.paddleWidth / 2 }

    @Test
    fun `moving the mouse over the board puts the paddle under the pointer's field position`(robot: FxRobot) {
        playing(robot)

        mouse(robot, MouseEvent.MOUSE_MOVED, boardX(60.0))

        // The raw x (some 200 view units) would drive the paddle to the right of its start at 160.
        assertTrue(waitUntil { abs(paddleCentre() - 60.0) < 5.0 })
    }

    @Test
    fun `dragging the mouse moves the paddle too`(robot: FxRobot) {
        playing(robot)

        mouse(robot, MouseEvent.MOUSE_DRAGGED, boardX(40.0))

        assertTrue(waitUntil { abs(paddleCentre() - 40.0) < 5.0 })
    }

    @Test
    fun `moving the focus away pauses and the overlay shows the unfocused hint`(robot: FxRobot) {
        pauseByFocus(robot)

        assertTrue(fx { view.overlay.isVisible })
        assertEquals("Click the game to focus it", fx { (view.overlay.lookup(".game-paused-hint") as Label).text })
    }

    @Test
    fun `on an unfocused board the first press only focuses it and the second resumes`(robot: FxRobot) {
        pauseByFocus(robot)

        // Pressed on the overlay: the press reaches the board through it.
        mouse(robot, MouseEvent.MOUSE_PRESSED, 100.0, target = view.overlay)

        assertSame(view.board, fx { stage.scene.focusOwner })
        assertEquals(SessionState.PAUSED, state())
        assertTrue(fx { view.overlay.isVisible })

        mouse(robot, MouseEvent.MOUSE_PRESSED, 100.0, target = view.overlay)

        assertEquals(SessionState.COUNTDOWN, state())
        assertFalse(fx { view.overlay.isVisible })
    }

    @Test
    fun `a click on the overlay itself does not resume`(robot: FxRobot) {
        pauseByFocus(robot)
        focusBoard(robot)

        robot.interact {
            Event.fireEvent(
                view.overlay,
                MouseEvent(
                    MouseEvent.MOUSE_CLICKED,
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
                    true,
                    true,
                    null,
                ),
            )
        }

        assertEquals(SessionState.PAUSED, state())
    }

    @Test
    fun `the Pause button pauses and the Resume button counts down`(robot: FxRobot) {
        startCountdown(robot)

        robot.interact { view.pauseButton.fire() }
        assertEquals(SessionState.PAUSED, state())
        assertEquals("Resume", fx { view.pauseButton.text })

        robot.interact { other.requestFocus() }
        robot.interact { view.pauseButton.fire() }

        assertEquals(SessionState.COUNTDOWN, state())
        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `the buttons are not focus traversable and New Game names F2`(robot: FxRobot) {
        assertFalse(fx { view.newGameButton.isFocusTraversable })
        assertFalse(fx { view.difficultyMenu.isFocusTraversable })
        assertFalse(fx { view.pauseButton.isFocusTraversable })
        assertEquals("New Game (F2)", fx { (view.newGameButton.tooltip as Tooltip).text })
    }

    @Test
    fun `a difficulty menu item asks while a game with a score runs, and Cancel keeps the game`(robot: FxRobot) {
        withScore = true
        robot.interact { model.newGame() }
        startCountdown(robot)

        robot.interact { view.difficultyMenu.items[2].fire() }

        assertEquals(listOf(Triple("Change Difficulty", "Change Difficulty", 100)), asked)
        assertEquals(Difficulty.NORMAL, fx { model.difficulty })

        answer = true
        robot.interact { view.difficultyMenu.items[2].fire() }

        assertEquals(Difficulty.HARD, fx { model.difficulty })
        assertEquals("Hard", fx { view.difficultyMenu.text })
    }

    @Test
    fun `a disposed view leaves the model to the view that took it over`(robot: FxRobot) {
        startCountdown(robot)
        val second = fx { BlockBreakerView(model, zoomPreferences) }

        try {
            robot.interact { view.dispose() }

            // The second view owns the model now: the first one's disposal neither pauses nor detaches it.
            assertEquals(SessionState.COUNTDOWN, state())
            assertFalse(fx { view.loop.isRunning })
            robot.interact { model.chooseDifficulty(Difficulty.HARD) }
            assertEquals("Hard", fx { second.difficultyMenu.text })
            assertEquals("Normal", fx { view.difficultyMenu.text })
        } finally {
            robot.interact { second.dispose() }
        }
    }

    @Test
    fun `the canvas draws bricks and capsules at a fractional scale`(robot: FxRobot) {
        fx {
            model.game.loadBricks(listOf("12345TH#.....", "5555555555555"))
            PowerUp.entries.forEachIndexed { i, kind -> model.game.addCapsule(40.0 + i * 20, 150.0, kind) }
        }
        try {
            robot.interact { stage.renderScaleX = 1.5 }
            val image =
                fx {
                    view.canvas.redraw()
                    view.canvas.snapshot(null, WritableImage(view.canvas.width.toInt(), view.canvas.height.toInt()))
                }
            // The middle of brick (0, 0), a tier-1 brick, in the colour of its tier (not the letterbox background).
            val place =
                FieldLayout(
                    BlockBreakerGame.WIDTH,
                    BlockBreakerGame.HEIGHT,
                ).fit(image.width, image.height, 1.0, 1.0)
            val x = place.x(Rules.PLAY_LEFT + Rules.BRICK_WIDTH / 2.0).toInt()
            val y = place.y(Rules.BRICK_TOP + Rules.BRICK_HEIGHT / 2.0).toInt()
            assertEquals(
                0xff000000.toInt() or BrickBitmaps.baseColor(BrickKind.NORMAL, 1),
                image.pixelReader.getArgb(x, y),
            )
            assertTrue(fx { view.canvas.deviceScale!! } >= 1.5)
        } finally {
            robot.interact { stage.renderScaleX = 1.0 }
        }
    }

    @Test
    fun `the pointer is hidden while playing and shown again when paused`(robot: FxRobot) {
        assertEquals(Cursor.DEFAULT, fx { view.board.cursor })

        playing(robot)
        assertEquals(Cursor.NONE, fx { view.board.cursor })

        robot.interact { other.requestFocus() }
        assertEquals(Cursor.DEFAULT, fx { view.board.cursor })
    }

    @Test
    fun `zooming out shrinks the field, is saved and zoom in is disabled at Fit`(robot: FxRobot) {
        fun width() = fx { view.canvas.placement.width }
        val fit = width()
        assertTrue(fx { view.zoomControls.zoomInButton.isDisable })

        robot.interact { view.zoomControls.zoomTo(0.5) }

        assertEquals(fit / 2, width(), 2.0)
        assertEquals(0.5, fx { view.canvas.zoom })
        // The pointer mapping of the press, drag and release paths follows the zoom.
        assertEquals(0.0, fx { view.canvas.fieldX(view.canvas.placement.x(0.0)) }, 1.0)
        assertEquals(320.0, fx { view.canvas.fieldX(view.canvas.placement.x(320.0)) }, 1.0)
        assertEquals(0.5, fx { zoomPreferences.zoom("blockBreaker", ZoomRange.UP_TO_FIT) })
        assertFalse(fx { view.zoomControls.zoomInButton.isDisable })
    }
}
