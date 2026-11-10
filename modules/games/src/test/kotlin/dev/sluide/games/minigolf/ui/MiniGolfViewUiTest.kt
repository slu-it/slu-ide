package dev.sluide.games.minigolf.ui

import dev.sluide.games.common.GameZoomPreferences
import dev.sluide.games.common.SessionState
import dev.sluide.games.common.ZoomRange
import dev.sluide.games.common.ui.firePress
import dev.sluide.games.minigolf.MiniGolfGame
import dev.sluide.games.minigolf.MiniGolfModel
import dev.sluide.games.minigolf.MiniGolfPreferences
import dev.sluide.games.minigolf.Phase
import dev.sluide.games.minigolf.geometry.Vec
import dev.sluide.games.minigolf.testHole
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.TextField
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
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The connections of the Mini Golf view to its model and session that need a scene: the mouse on the board (aim,
 * shot, right click, click order against the focus), keys, the focus listener, the pause overlay, the toolbar and the
 * abandon question. Needs a display. The rules are tested in the model tests.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class MiniGolfViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: MiniGolfModel
    private lateinit var zoomPreferences: GameZoomPreferences
    private lateinit var view: MiniGolfView
    private lateinit var other: TextField
    private var asked = 0
    private var answer = false

    @Start
    fun start(stage: Stage) {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = MiniGolfPreferences(store, java.time.Duration.ZERO) { it.run() }
        // Cups 30 units from the tee: a drag of 20 units drops into the cup, a drag of 60 rolls past it.
        model =
            MiniGolfModel(preferences) {
                MiniGolfGame(
                    List(3) {
                        testHole(it + 1) {
                            tee(30, 125)
                            cup(60, 125)
                        }
                    },
                )
            }
        zoomPreferences = GameZoomPreferences(store, java.time.Duration.ZERO) { it.run() }
        view = MiniGolfView(model, zoomPreferences)
        view.confirmAbandon = {
            asked++
            answer
        }
        other = TextField()
        VBox.setVgrow(view.node, Priority.ALWAYS)
        stage.scene = Scene(VBox(view.node, other), 900.0, 600.0)
        stage.sizeToScene()
        stage.show()
    }

    // No test leaves a listener on the static window list.
    @AfterEach
    fun disposeView() {
        fx { view.dispose() }
    }

    private fun <T> fx(block: () -> T): T = WaitForAsyncUtils.asyncFx(block).get()

    private fun key(
        robot: FxRobot,
        type: EventType<KeyEvent>,
        code: KeyCode,
    ) = robot.interact { Event.fireEvent(view.board, KeyEvent(type, "", "", code, false, false, false, false)) }

    private fun mouse(
        robot: FxRobot,
        type: EventType<MouseEvent>,
        fieldX: Double,
        fieldY: Double,
        button: MouseButton = MouseButton.PRIMARY,
        target: Node = view.board,
    ) = robot.interact {
        // The field of 320 x 240 units, centred in the board at the largest scale that fits.
        val w = view.board.width
        val h = view.board.height
        val scale = max(1.0, min(w / 320.0, h / 240.0))
        val x = (w - 320 * scale) / 2 + fieldX * scale
        val y = (h - 240 * scale) / 2 + fieldY * scale
        Event.fireEvent(
            target,
            MouseEvent(
                type,
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
            ),
        )
    }

    private fun focusBoard(robot: FxRobot) = robot.interact { view.board.requestFocus() }

    private fun state(): SessionState = fx { model.state }

    private fun startCountdown(robot: FxRobot) {
        focusBoard(robot)
        key(robot, KeyEvent.KEY_PRESSED, KeyCode.SPACE)
        key(robot, KeyEvent.KEY_RELEASED, KeyCode.SPACE)
        assertEquals(SessionState.COUNTDOWN, state())
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

    /** A drag of [length] units to the left of the ball, which shoots to the right. */
    private fun putt(
        robot: FxRobot,
        length: Double,
    ) {
        mouse(robot, MouseEvent.MOUSE_PRESSED, 100.0, 125.0)
        mouse(robot, MouseEvent.MOUSE_DRAGGED, 100.0 - length, 125.0)
        mouse(robot, MouseEvent.MOUSE_RELEASED, 100.0 - length, 125.0)
    }

    private fun holeInOne(robot: FxRobot) {
        putt(robot, 20.0)
        assertTrue(waitUntil { model.game.phase == Phase.CARD })
    }

    @Test
    fun `pressing, dragging and releasing on the board shoots`(robot: FxRobot) {
        playing(robot)

        putt(robot, 60.0)

        assertEquals(1, fx { model.game.strokes })
        assertNull(fx { model.aim })
    }

    @Test
    fun `a drag aims and a right click cancels the aim`(robot: FxRobot) {
        playing(robot)
        mouse(robot, MouseEvent.MOUSE_PRESSED, 100.0, 125.0)
        mouse(robot, MouseEvent.MOUSE_DRAGGED, 70.0, 125.0)
        assertNotNull(fx { model.aim })

        mouse(robot, MouseEvent.MOUSE_PRESSED, 70.0, 125.0, MouseButton.SECONDARY)
        mouse(robot, MouseEvent.MOUSE_RELEASED, 70.0, 125.0, MouseButton.SECONDARY)

        assertNull(fx { model.aim })
        assertEquals(0, fx { model.game.strokes })
    }

    @Test
    fun `the Pause button pauses and the Resume button counts down`(robot: FxRobot) {
        playing(robot)

        robot.interact { view.pauseButton.fire() }
        assertEquals(SessionState.PAUSED, state())
        assertEquals("Resume", fx { view.pauseButton.text })

        robot.interact { other.requestFocus() }
        robot.interact { view.pauseButton.fire() }

        assertEquals(SessionState.COUNTDOWN, state())
        assertSame(view.board, fx { view.board.scene.focusOwner })
    }

    @Test
    fun `moving the focus away pauses and the overlay shows the unfocused hint`(robot: FxRobot) {
        startCountdown(robot)

        robot.interact { other.requestFocus() }

        assertEquals(SessionState.PAUSED, state())
        assertTrue(fx { view.overlay.isVisible })
        assertEquals("Click the game to focus it", fx { (view.overlay.lookup(".game-paused-hint") as Label).text })
    }

    @Test
    fun `on an unfocused board the first press only focuses it and the second resumes`(robot: FxRobot) {
        startCountdown(robot)
        robot.interact { other.requestFocus() }

        mouse(robot, MouseEvent.MOUSE_PRESSED, 100.0, 125.0, target = view.overlay)

        assertSame(view.board, fx { view.board.scene.focusOwner })
        assertEquals(SessionState.PAUSED, state())

        mouse(robot, MouseEvent.MOUSE_PRESSED, 100.0, 125.0, target = view.overlay)

        assertEquals(SessionState.COUNTDOWN, state())
    }

    @Test
    fun `a click on the scorecard continues to the next hole`(robot: FxRobot) {
        playing(robot)
        holeInOne(robot)

        mouse(robot, MouseEvent.MOUSE_PRESSED, 100.0, 125.0)
        mouse(robot, MouseEvent.MOUSE_RELEASED, 100.0, 125.0)

        assertEquals(1, fx { model.game.holeIndex })
        assertEquals(Phase.AIMING, fx { model.game.phase })
    }

    @Test
    fun `Space on the scorecard continues to the next hole`(robot: FxRobot) {
        playing(robot)
        holeInOne(robot)

        key(robot, KeyEvent.KEY_PRESSED, KeyCode.SPACE)

        assertEquals(1, fx { model.game.holeIndex })
    }

    @Test
    fun `New Game with strokes played asks, Cancel keeps the round and confirming starts over`(robot: FxRobot) {
        playing(robot)
        putt(robot, 60.0)
        val round = fx { model.game }

        robot.interact { view.newGameButton.fire() }

        assertEquals(1, asked)
        assertSame(round, fx { model.game })

        answer = true
        robot.interact { view.newGameButton.fire() }

        assertEquals(2, asked)
        assertEquals(0, fx { model.game.strokes })
        assertEquals(SessionState.READY, state())
    }

    @Test
    fun `F2 with strokes played asks the abandon question`(robot: FxRobot) {
        playing(robot)
        putt(robot, 60.0)
        val round = fx { model.game }

        key(robot, KeyEvent.KEY_PRESSED, KeyCode.F2)

        assertEquals(1, asked)
        assertSame(round, fx { model.game })
    }

    @Test
    fun `F2 without strokes starts a new round without asking`(robot: FxRobot) {
        playing(robot)
        val round = fx { model.game }

        key(robot, KeyEvent.KEY_PRESSED, KeyCode.F2)

        assertEquals(0, asked)
        assertNotSame(round, fx { model.game })
        assertEquals(SessionState.READY, state())
    }

    @Test
    fun `a hole in one starts the burst on the canvas`(robot: FxRobot) {
        playing(robot)
        assertFalse(fx { view.canvas.isBurstActive })

        putt(robot, 20.0)

        assertTrue(waitUntil { view.canvas.isBurstActive })
    }

    @Test
    fun `disposing the view while playing pauses the round`(robot: FxRobot) {
        playing(robot)

        robot.interact { view.dispose() }

        assertEquals(SessionState.PAUSED, state())
    }

    @Test
    fun `a disposed view leaves the model to the view that took it over`(robot: FxRobot) {
        startCountdown(robot)
        val second = fx { MiniGolfView(model, zoomPreferences) }

        try {
            robot.interact { view.dispose() }

            // The second view owns the model now: the first one's disposal neither pauses nor detaches it.
            assertEquals(SessionState.COUNTDOWN, state())
            assertFalse(fx { view.loop.isRunning })
            robot.interact { model.togglePause() }
            assertEquals("Resume", fx { second.pauseButton.text })
            assertEquals("Pause", fx { view.pauseButton.text })
            robot.interact { model.onHoleInOne(Vec(60.0, 125.0)) }
            assertTrue(fx { second.canvas.isBurstActive })
            assertFalse(fx { view.canvas.isBurstActive })
        } finally {
            robot.interact { second.dispose() }
        }
    }

    @Test
    fun `a press on the status line returns the focus to the board`(robot: FxRobot) {
        robot.interact { view.node.lookup(".button").requestFocus() }

        robot.interact { firePress(view.status) }

        assertSame(view.board, fx { view.board.scene.focusOwner })
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
        val corner = fx { view.canvas.placement.let { view.canvas.fieldPoint(it.x(0.0), it.y(0.0)) } }
        assertEquals(0.0, corner.x, 1.0)
        assertEquals(0.0, corner.y, 1.0)
        assertEquals(0.5, fx { zoomPreferences.zoom("miniGolf", ZoomRange.UP_TO_FIT) })
        assertFalse(fx { view.zoomControls.zoomInButton.isDisable })
    }
}
