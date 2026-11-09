package dev.sluide.games.orbitguard.ui

import dev.sluide.games.common.PauseReason
import dev.sluide.games.common.SessionState
import dev.sluide.games.common.ui.firePress
import dev.sluide.games.orbitguard.Difficulty
import dev.sluide.games.orbitguard.OrbitGuardGame
import dev.sluide.games.orbitguard.OrbitGuardModel
import dev.sluide.games.orbitguard.OrbitGuardPreferences
import dev.sluide.games.orbitguard.ScriptedRandom
import dev.sluide.projectdata.ProjectDataStore
import javafx.event.Event
import javafx.event.EventHandler
import javafx.scene.Scene
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.TextField
import javafx.scene.control.Tooltip
import javafx.scene.image.WritableImage
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The connections of the Orbit Guard view to its model and session that need a scene: key events and consumption,
 * the focus listener and the pause overlay, the toolbar, the abandon question, the animation timer and the canvas.
 * Needs a display. The rules are tested in the model and session tests.
 */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class OrbitGuardViewUiTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var model: OrbitGuardModel
    private lateinit var view: OrbitGuardView
    private lateinit var stage: Stage
    private lateinit var other: TextField
    private val asked = mutableListOf<Triple<String, String, Int>>()
    private var answer = false
    private var withScore = false

    @Start
    fun start(stage: Stage) {
        this.stage = stage
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        val preferences = OrbitGuardPreferences(store, java.time.Duration.ZERO) { it.run() }
        model =
            OrbitGuardModel(preferences) { difficulty, _ ->
                // Quiet enemies, so that the ship survives the test.
                OrbitGuardGame(difficulty, ScriptedRandom()).also { if (withScore) it.addScore(100) }
            }
        view = OrbitGuardView(model)
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

    /** Fires the key press at the board; true when the view consumed it (it never reached the tab's root). */
    private fun press(
        robot: FxRobot,
        code: KeyCode,
        control: Boolean = false,
    ): Boolean {
        var reachedRoot = false
        val handler = EventHandler<KeyEvent> { reachedRoot = true }
        robot.interact {
            view.node.addEventHandler(KeyEvent.KEY_PRESSED, handler)
            Event.fireEvent(view.board, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, control, false, false))
            view.node.removeEventHandler(KeyEvent.KEY_PRESSED, handler)
        }
        return !reachedRoot
    }

    private fun release(
        robot: FxRobot,
        code: KeyCode,
        control: Boolean = false,
    ) = robot.interact {
        Event.fireEvent(
            view.board,
            KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, control, false, false),
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

    @Test
    fun `Space on the focused board starts the countdown and is consumed`(robot: FxRobot) {
        focusBoard(robot)

        assertTrue(press(robot, KeyCode.SPACE))

        assertEquals(SessionState.COUNTDOWN, state())
        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `an arrow key is consumed and the focus stays on the board`(robot: FxRobot) {
        focusBoard(robot)

        assertTrue(press(robot, KeyCode.LEFT))

        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a mouse press on the status line returns the focus to the board`(robot: FxRobot) {
        robot.interact { view.node.lookup(".button").requestFocus() }
        assertFalse(fx { view.board.isFocused })

        robot.interact { firePress(view.status) }

        assertSame(view.board, fx { stage.scene.focusOwner })
    }

    @Test
    fun `a key with a modifier is not consumed`(robot: FxRobot) {
        focusBoard(robot)

        assertFalse(press(robot, KeyCode.SPACE, control = true))

        assertEquals(SessionState.READY, state())
    }

    @Test
    fun `moving the focus away while counting down pauses with the overlay and the focus hint`(robot: FxRobot) {
        startCountdown(robot)
        assertTrue(fx { view.loop.isRunning })

        robot.interact { other.requestFocus() }

        assertEquals(SessionState.PAUSED, state())
        assertFalse(fx { view.loop.isRunning })
        assertTrue(fx { view.overlay.isVisible })
        assertTrue(fx { model.pauseHint.startsWith("Click the game") })
        assertEquals("Resume", fx { view.pauseButton.text })
    }

    @Test
    fun `the timer stops when the game is over`(robot: FxRobot) {
        startCountdown(robot)

        robot.interact { model.session.gameOver() }

        assertFalse(fx { view.loop.isRunning })
    }

    @Test
    fun `removing the tab from its parent during the countdown pauses at once`(robot: FxRobot) {
        startCountdown(robot)

        // Inside one pulse: the scene clears the removed node's focus only on the next pulse, and closing a tab
        // disposes the view in that same pulse.
        val state =
            fx {
                (view.node.parent as VBox).children.remove(view.node)
                model.state
            }

        assertEquals(SessionState.PAUSED, state)
    }

    @Test
    fun `a tab removed and added again within one pulse reports the board's real focus`(robot: FxRobot) {
        startCountdown(robot)

        fx {
            val parent = view.node.parent as VBox
            parent.children.remove(view.node)
            parent.children.add(0, view.node)
            assertTrue(view.board.isFocused)
        }

        assertTrue(fx { model.session.isFocused })
    }

    @Test
    fun `opening the difficulty menu while counting down pauses like a lost focus`(robot: FxRobot) {
        startCountdown(robot)

        robot.interact { view.difficultyMenu.show() }

        assertEquals(SessionState.PAUSED, state())
        assertEquals(PauseReason.FOCUS, fx { model.pauseReason })
        robot.interact { view.difficultyMenu.hide() }
    }

    private fun showMenu(robot: FxRobot): ContextMenu {
        val menu = ContextMenu(MenuItem("Item"))
        robot.interact { menu.show(stage, 100.0, 100.0) }
        return menu
    }

    @Test
    fun `a context menu of the window pauses and releases held keys, and P resumes after it closed`(robot: FxRobot) {
        startCountdown(robot)
        press(robot, KeyCode.RIGHT)
        val start = fx { model.game.shipX }

        val menu = showMenu(robot)
        assertEquals(SessionState.PAUSED, state())
        robot.interact { menu.hide() }

        assertEquals(SessionState.PAUSED, state())
        assertTrue(fx { model.pauseHint.startsWith("Press P") })
        press(robot, KeyCode.P)
        release(robot, KeyCode.P)
        assertEquals(SessionState.COUNTDOWN, state())
        assertTrue(waitUntil { model.state == SessionState.PLAYING })
        // A held RIGHT would cancel LEFT; it was released when the menu opened.
        press(robot, KeyCode.LEFT)
        assertTrue(waitUntil { model.game.shipX < start })
        release(robot, KeyCode.LEFT)
    }

    @Test
    fun `after a menu closed and play resumed, moving the focus away pauses again`(robot: FxRobot) {
        startCountdown(robot)
        val menu = showMenu(robot)
        robot.interact { menu.hide() }
        press(robot, KeyCode.P)
        release(robot, KeyCode.P)
        assertEquals(SessionState.COUNTDOWN, state())

        robot.interact { other.requestFocus() }

        assertEquals(SessionState.PAUSED, state())
    }

    @Test
    fun `a tooltip showing does not pause`(robot: FxRobot) {
        startCountdown(robot)
        val tip = Tooltip("Hint")

        robot.interact { tip.show(stage, 100.0, 100.0) }

        assertEquals(SessionState.COUNTDOWN, state())
        robot.interact { tip.hide() }
    }

    @Test
    fun `a disposed view leaves the model to the view that took it over`(robot: FxRobot) {
        startCountdown(robot)
        val second = fx { OrbitGuardView(model) }

        robot.interact { view.dispose() }

        // The second view owns the model now: the first one's disposal does not pause the game.
        assertEquals(SessionState.COUNTDOWN, state())
        assertFalse(fx { view.loop.isRunning })
        robot.interact { model.chooseDifficulty(Difficulty.HARD) }
        assertEquals("Hard", fx { second.difficultyMenu.text })
        assertEquals("Normal", fx { view.difficultyMenu.text })
        robot.interact { second.dispose() }
    }

    @Test
    fun `a disposed view's board no longer reaches the session`(robot: FxRobot) {
        startCountdown(robot)
        val second = fx { OrbitGuardView(model) }
        robot.interact { view.dispose() }

        // The scene listener is gone: leaving the scene does not pause ...
        fx {
            val parent = view.node.parent as VBox
            parent.children.remove(view.node)
            parent.children.add(0, view.node)
        }
        assertEquals(SessionState.COUNTDOWN, state())
        // ... and neither does the focus listener ...
        robot.interact { other.requestFocus() }
        assertEquals(SessionState.COUNTDOWN, state())
        // ... nor a menu showing (the window list listener is gone). The second view's own loop would sync on
        // every window change with its board that is not in a scene, so it is detached for this check.
        robot.interact { second.loop.dispose() }
        val menu = showMenu(robot)
        assertEquals(SessionState.COUNTDOWN, state())
        robot.interact { menu.hide() }
        robot.interact { second.dispose() }
    }

    @Test
    fun `a context menu of another window does not pause`(robot: FxRobot) {
        val otherStage = fx { Stage().apply { scene = Scene(Pane(), 100.0, 100.0) } }
        val menu = ContextMenu(MenuItem("Item"))
        try {
            robot.interact { otherStage.show() }
            robot.interact { stage.requestFocus() }
            assertTrue(waitUntil { stage.isFocused })
            startCountdown(robot)

            robot.interact { menu.show(otherStage, 50.0, 50.0) }

            assertEquals(SessionState.COUNTDOWN, state())
        } finally {
            robot.interact {
                menu.hide()
                otherStage.close()
            }
        }
    }

    @Test
    fun `disposing the view that owns the model pauses the game`(robot: FxRobot) {
        startCountdown(robot)

        robot.interact { view.dispose() }

        assertEquals(SessionState.PAUSED, state())
    }

    @Test
    fun `clicking the overlay focuses the board and changes the hint but does not resume`(robot: FxRobot) {
        pauseByFocus(robot)
        val before = fx { model.pauseHint }

        robot.interact { Event.fireEvent(view.overlay, click()) }

        assertSame(view.board, fx { stage.scene.focusOwner })
        assertNotEquals(before, fx { model.pauseHint })
        assertEquals(SessionState.PAUSED, state())
        assertTrue(fx { view.overlay.isVisible })
    }

    @Test
    fun `P resumes a paused game with a countdown`(robot: FxRobot) {
        pauseByFocus(robot)
        focusBoard(robot)

        assertTrue(press(robot, KeyCode.P))
        release(robot, KeyCode.P)

        assertEquals(SessionState.COUNTDOWN, state())
        assertFalse(fx { view.overlay.isVisible })
    }

    @Test
    fun `the Pause button pauses and the Resume button focuses the board and counts down`(robot: FxRobot) {
        startCountdown(robot)

        robot.interact { view.pauseButton.fire() }
        assertEquals(SessionState.PAUSED, state())
        assertEquals("Resume", fx { view.pauseButton.text })

        robot.interact { other.requestFocus() }
        robot.interact { view.pauseButton.fire() }

        assertEquals(SessionState.COUNTDOWN, state())
        assertSame(view.board, fx { stage.scene.focusOwner })
        assertEquals("Pause", fx { view.pauseButton.text })
    }

    @Test
    fun `F2 with a score asks first and Cancel keeps the game`(robot: FxRobot) {
        withScore = true
        robot.interact { model.newGame() }
        val game = fx { model.game }
        focusBoard(robot)

        assertTrue(press(robot, KeyCode.F2))

        assertEquals(listOf(Triple("New Game", "New Game", 100)), asked)
        assertSame(game, fx { model.game })

        answer = true
        withScore = false
        press(robot, KeyCode.F2)

        assertEquals(2, asked.size)
        assertEquals(0, fx { model.game.score })
    }

    @Test
    fun `a difficulty menu item asks and then changes the difficulty`(robot: FxRobot) {
        withScore = true
        robot.interact { model.newGame() }
        val hard = view.difficultyMenu.items[Difficulty.HARD.ordinal] as RadioMenuItem

        robot.interact { hard.fire() }
        assertEquals(listOf(Triple("Change Difficulty", "Change Difficulty", 100)), asked)
        assertEquals(Difficulty.NORMAL, fx { model.difficulty })

        answer = true
        robot.interact { hard.fire() }

        assertEquals(Difficulty.HARD, fx { model.difficulty })
        assertEquals("Hard", fx { view.difficultyMenu.text })
    }

    @Test
    fun `after the countdown the timer steps the game while a key is held`(robot: FxRobot) {
        startCountdown(robot)
        // A direction key counts as held from the countdown on; it moves the ship once play begins.
        press(robot, KeyCode.RIGHT)
        val start = fx { model.game.shipX }

        assertTrue(waitUntil { model.state == SessionState.PLAYING && model.game.shipX > start })
        assertTrue(fx { view.loop.isRunning })

        release(robot, KeyCode.RIGHT)
    }

    @Test
    fun `a release with a modifier down still releases the key`(robot: FxRobot) {
        startCountdown(robot)
        press(robot, KeyCode.RIGHT)

        release(robot, KeyCode.RIGHT, control = true)

        val start = fx { model.game.shipX }
        // A stuck RIGHT would cancel LEFT (direction = right - left) and the ship would never move left.
        press(robot, KeyCode.LEFT)
        assertTrue(waitUntil { model.game.shipX < start })
        release(robot, KeyCode.LEFT)
    }

    @Test
    fun `the canvas rebuilds its sprite images when the window's render scale changes, until disposed`(robot: FxRobot) {
        assertTrue(waitUntil { view.canvas.deviceScale != null })
        val before = fx { view.canvas.deviceScale }!!
        try {
            robot.interact { stage.renderScaleX = 2.0 }
            assertEquals(before * 2, fx { view.canvas.deviceScale })

            robot.interact { view.dispose() }
            robot.interact { stage.renderScaleX = 3.0 }
            assertEquals(before * 2, fx { view.canvas.deviceScale })
        } finally {
            robot.interact { stage.renderScaleX = 1.0 }
        }
    }

    @Test
    fun `the canvas has a size and the area outside the field is dark space`(robot: FxRobot) {
        val image =
            fx { view.canvas.snapshot(null, WritableImage(view.canvas.width.toInt(), view.canvas.height.toInt())) }

        assertTrue(image.width > 100 && image.height > 100)
        // The 900 wide area is wider than the field, so its left margin is empty space.
        val argb = image.pixelReader.getArgb(5, (image.height / 2).toInt())
        assertEquals(0xff070a14.toInt(), argb)
    }

    private fun click() =
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
            false,
            true,
            null,
        )
}
