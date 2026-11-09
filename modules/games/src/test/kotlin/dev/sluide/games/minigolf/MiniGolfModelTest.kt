package dev.sluide.games.minigolf

import dev.sluide.games.common.PauseReason
import dev.sluide.games.common.SessionState
import dev.sluide.games.minigolf.geometry.Vec
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MiniGolfModelTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var preferences: MiniGolfPreferences
    private var now = 1_000_000L
    private var changes = 0
    private var created = 0
    private val burstAt = mutableListOf<Vec>()

    @BeforeEach
    fun setUp() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        preferences = MiniGolfPreferences(store, Duration.ZERO, Executor { it.run() })
    }

    /** Two holes (par 3 and 2) whose cups lie 30 units from the tee: a full-power shot is far too fast to drop. */
    private fun course(holes: Int) =
        List(holes) { index ->
            testHole(index + 1, if (index == 0) 3 else 2) {
                tee(30, 125)
                cup(60, 125)
            }
        }

    private fun model(
        focused: Boolean = true,
        holes: Int = 2,
    ): MiniGolfModel =
        MiniGolfModel(preferences) {
            created++
            MiniGolfGame(course(holes))
        }.also {
            if (focused) it.session.focusChanged(true)
            it.onChange = { changes++ }
            it.onHoleInOne = { cup -> burstAt += cup }
        }

    private fun MiniGolfModel.advance(nanos: Long) {
        now += nanos
        tick(now)
    }

    private fun MiniGolfModel.step(count: Int = 1) = repeat(count) { advance(STEP) }

    /** READY to PLAYING: the press that starts, then the countdown. */
    private fun MiniGolfModel.play() {
        pressed(0.0, 0.0)
        advance(0)
        advance(COUNTDOWN)
        assertEquals(SessionState.PLAYING, state)
    }

    /** A shot to the right with a drag of 20 units: power 0.25, which drops into the cup 30 units away. */
    private fun MiniGolfModel.holeInOne() {
        pressed(100.0, 100.0)
        dragged(80.0, 100.0)
        released(80.0, 100.0)
        // The card of the last hole ends the round at once, so the game is FINISHED there.
        repeat(600) { if (game.phase != Phase.CARD && game.phase != Phase.FINISHED) step() }
        assertTrue(game.phase == Phase.CARD || game.phase == Phase.FINISHED)
    }

    @ParameterizedTest
    @CsvSource("PRESS", "SPACE", "ENTER")
    fun `a press, Space or Enter starts the countdown and never aims`(how: String) {
        val model = model()

        when (how) {
            "PRESS" -> model.pressed(100.0, 100.0)
            else -> model.keyPressed(KeyAction.CONTINUE)
        }
        model.dragged(60.0, 100.0)

        assertEquals(SessionState.COUNTDOWN, model.state)
        assertNull(model.aim)
        model.advance(0)
        model.advance(COUNTDOWN)
        model.released(60.0, 100.0)
        assertEquals(0, model.game.strokes)
    }

    @Test
    fun `a press during the countdown does not aim either`() {
        val model = model()
        model.pressed(0.0, 0.0)

        model.pressed(100.0, 100.0)
        model.dragged(60.0, 100.0)

        assertNull(model.aim)
    }

    @Test
    fun `the aim goes opposite to the drag with the power of its length`() {
        val model = model()
        model.play()

        model.pressed(100.0, 100.0)
        model.dragged(100.0, 130.0)

        assertEquals(-PI / 2, model.aim?.angle ?: 0.0, 1e-9)
        assertEquals(30.0 / 80.0, model.aim?.power ?: 0.0, 1e-9)
    }

    @Test
    fun `the power is capped at a drag of 80 units`() {
        val model = model()
        model.play()
        model.pressed(100.0, 100.0)

        model.dragged(20.0, 100.0)
        assertEquals(1.0, model.aim?.power ?: 0.0, 1e-9)
        model.dragged(-200.0, 100.0)

        assertEquals(1.0, model.aim?.power ?: 0.0, 1e-9)
        assertEquals(0.0, model.aim?.angle ?: 1.0, 1e-9)
    }

    @Test
    fun `a release shoots with the angle and power of the drag`() {
        val model = model()
        model.play()
        model.pressed(100.0, 100.0)
        model.dragged(60.0, 100.0)
        changes = 0

        model.released(60.0, 100.0)

        assertEquals(1, model.game.strokes)
        assertEquals(Phase.ROLLING, model.game.phase)
        assertNull(model.aim)
        assertTrue(changes > 0)
        model.step()
        assertEquals(150.0, model.game.ballVelocity.length, 5.0)
    }

    @Test
    fun `a release after a drag under 4 units cancels without a shot`() {
        val model = model()
        model.play()
        model.pressed(100.0, 100.0)
        model.dragged(97.0, 100.0)
        assertTrue(model.aim != null)

        model.released(97.0, 100.0)

        assertEquals(0, model.game.strokes)
        assertEquals(Phase.AIMING, model.game.phase)
        assertNull(model.aim)
    }

    @Test
    fun `a drag of exactly 4 units shoots`() {
        val model = model()
        model.play()
        model.pressed(100.0, 100.0)

        model.released(96.0, 100.0)

        assertEquals(1, model.game.strokes)
    }

    @Test
    fun `Esc and a right click cancel the aim, and a later release does nothing`() {
        val model = model()
        model.play()

        model.pressed(100.0, 100.0)
        model.dragged(60.0, 100.0)
        model.keyPressed(KeyAction.CANCEL)
        assertNull(model.aim)
        model.released(60.0, 100.0)
        assertEquals(0, model.game.strokes)

        model.pressed(100.0, 100.0)
        model.dragged(60.0, 100.0)
        model.cancelAim()
        assertNull(model.aim)
        model.released(60.0, 100.0)
        assertEquals(0, model.game.strokes)
    }

    @Test
    fun `a pause cancels the aim`() {
        val model = model()
        model.play()
        model.pressed(100.0, 100.0)
        model.dragged(60.0, 100.0)

        model.keyPressed(KeyAction.PAUSE)
        model.released(60.0, 100.0)

        assertEquals(SessionState.PAUSED, model.state)
        assertNull(model.aim)
        assertEquals(0, model.game.strokes)
    }

    @Test
    fun `losing the focus pauses and cancels the aim`() {
        val model = model()
        model.play()
        model.pressed(100.0, 100.0)
        model.dragged(60.0, 100.0)

        model.session.focusChanged(false)

        assertEquals(PauseReason.FOCUS, model.pauseReason)
        assertNull(model.aim)
    }

    @Test
    fun `a ball that a mover pushes while aiming cancels the aim and a release does nothing`() {
        val model = model()
        model.play()
        model.pressed(100.0, 100.0)
        model.dragged(60.0, 100.0)
        assertTrue(model.aim != null)

        // The test seam stands for a push: the ball rolls without the model's shot.
        model.game.placeBall(Vec(30.0, 125.0), Vec(10.0, 0.0))
        model.step()

        assertNull(model.aim)
        model.released(60.0, 100.0)
        assertEquals(0, model.game.strokes)
    }

    @Test
    fun `a press while the ball rolls starts no aim`() {
        val model = model()
        model.play()
        model.game.placeBall(Vec(30.0, 125.0), Vec(10.0, 0.0))

        model.pressed(100.0, 100.0)
        model.dragged(60.0, 100.0)

        assertNull(model.aim)
    }

    @Test
    fun `a press resumes a paused game only while the board has the focus`() {
        val model = model()
        model.play()
        model.togglePause()

        model.session.focusChanged(false)
        model.pressed(0.0, 0.0)
        assertEquals(SessionState.PAUSED, model.state)

        model.session.focusChanged(true)
        model.pressed(0.0, 0.0)
        assertEquals(SessionState.COUNTDOWN, model.state)
    }

    @Test
    fun `the pause hint follows the focus and the window`() {
        val model = model()
        model.play()
        model.togglePause()
        assertEquals("Press P or click to resume", model.pauseHint)

        model.session.focusChanged(false)
        assertEquals("Click the game to focus it", model.pauseHint)

        model.windowFocusChanged(false)
        assertEquals("Click the game to resume", model.pauseHint)

        model.windowFocusChanged(true)
        assertEquals("Click the game to focus it", model.pauseHint)
    }

    @Test
    fun `a window change while paused reports a change for the hint`() {
        val model = model()
        model.play()
        model.togglePause()
        changes = 0

        model.windowFocusChanged(false)
        model.windowFocusChanged(false)

        assertEquals(1, changes)
    }

    @Test
    fun `P toggles the pause and a held P counts once`() {
        val model = model()
        model.play()

        model.keyPressed(KeyAction.PAUSE)
        model.keyPressed(KeyAction.PAUSE)
        assertEquals(SessionState.PAUSED, model.state)
        model.keyReleased(KeyAction.PAUSE)
        model.keyPressed(KeyAction.PAUSE)

        assertEquals(SessionState.COUNTDOWN, model.state)
    }

    @Test
    fun `the ready text and the pause button`() {
        val model = model()

        assertEquals("Click or press Space to start", model.readyText)
        assertFalse(model.canPause)
        model.play()
        assertTrue(model.canPause)
        assertEquals("Pause", model.pauseButtonText)
        model.togglePause()
        assertEquals("Resume", model.pauseButtonText)
    }

    @ParameterizedTest
    @CsvSource("PRESS", "SPACE", "ENTER")
    fun `the card goes on to the next hole at once`(how: String) {
        val model = model()
        model.play()
        model.holeInOne()
        assertEquals(SessionState.PLAYING, model.state)

        when (how) {
            "PRESS" -> model.pressed(0.0, 0.0)
            else -> model.keyPressed(KeyAction.CONTINUE)
        }

        assertEquals(1, model.game.holeIndex)
        assertEquals(Phase.AIMING, model.game.phase)
        assertEquals(SessionState.PLAYING, model.state)
        assertNull(model.aim)
    }

    @Test
    fun `a held Space goes through one card only`() {
        val model = model(holes = 3)
        model.play()
        model.holeInOne()
        model.keyPressed(KeyAction.CONTINUE)
        model.holeInOne()

        model.keyPressed(KeyAction.CONTINUE)

        assertEquals(1, model.game.holeIndex)
        assertEquals(Phase.CARD, model.game.phase)
        model.keyReleased(KeyAction.CONTINUE)
        model.keyPressed(KeyAction.CONTINUE)
        assertEquals(2, model.game.holeIndex)
    }

    @Test
    fun `a P held before the focus was lost acts at its next press`() {
        val model = model()
        model.play()
        model.keyPressed(KeyAction.PAUSE)
        assertEquals(SessionState.PAUSED, model.state)

        model.session.focusChanged(false)
        model.session.focusChanged(true)
        model.keyPressed(KeyAction.PAUSE)

        assertEquals(SessionState.COUNTDOWN, model.state)
    }

    @Test
    fun `a Space held before the focus was lost goes through the next card`() {
        val model = model(holes = 3)
        model.play()
        model.holeInOne()
        model.keyPressed(KeyAction.CONTINUE)
        model.togglePause()
        model.session.focusChanged(false)
        model.session.focusChanged(true)
        model.pressed(0.0, 0.0)
        model.advance(0)
        model.advance(COUNTDOWN)
        model.holeInOne()

        model.keyPressed(KeyAction.CONTINUE)

        assertEquals(2, model.game.holeIndex)
    }

    @Test
    fun `a new game releases the held keys`() {
        val model = model()
        model.play()
        model.keyPressed(KeyAction.PAUSE)
        model.keyPressed(KeyAction.CONTINUE)

        model.newGame()
        model.keyPressed(KeyAction.CONTINUE)
        assertEquals(SessionState.COUNTDOWN, model.state)
        model.advance(0)
        model.advance(COUNTDOWN)
        model.keyPressed(KeyAction.PAUSE)

        assertEquals(SessionState.PAUSED, model.state)
    }

    @Test
    fun `a release after pausing and resuming with P shoots nothing`() {
        val model = model()
        model.play()
        model.pressed(100.0, 100.0)
        model.dragged(60.0, 100.0)

        model.keyPressed(KeyAction.PAUSE)
        model.keyReleased(KeyAction.PAUSE)
        model.keyPressed(KeyAction.PAUSE)
        model.keyReleased(KeyAction.PAUSE)
        model.advance(0)
        model.advance(COUNTDOWN)
        assertEquals(SessionState.PLAYING, model.state)
        model.released(60.0, 100.0)

        assertEquals(0, model.game.strokes)
    }

    @Test
    fun `a drag and a cancel report a change`() {
        val model = model()
        model.play()
        model.pressed(100.0, 100.0)
        changes = 0

        model.dragged(60.0, 100.0)
        assertEquals(1, changes)
        model.cancelAim()
        assertEquals(2, changes)
        model.cancelAim()

        assertEquals(2, changes)
    }

    @Test
    fun `a hole in one calls back with the cup`() {
        val model = model()
        model.play()

        model.holeInOne()

        assertEquals(listOf(Vec(60.0, 125.0)), burstAt)
    }

    @Test
    fun `a hole that is no hole in one calls no burst`() {
        val model = model()
        model.play()
        // Two strokes: the first stops short of the cup, the second drops.
        model.pressed(100.0, 100.0)
        model.released(88.0, 100.0)
        repeat(600) { if (model.game.phase == Phase.ROLLING) model.step() }
        model.pressed(100.0, 100.0)
        model.released(88.0, 100.0)
        repeat(600) { if (model.game.phase != Phase.CARD) model.step() }

        assertEquals(Phase.CARD, model.game.phase)
        assertTrue(burstAt.isEmpty())
    }

    @Test
    fun `a better score of a hole replaces the stored one when the hole ends`() {
        preferences.recordHole(0, 3)
        preferences.recordHole(1, 1)
        val model = model()
        model.play()

        model.holeInOne()

        assertEquals(listOf(1, 1), model.bestHoles)
        assertEquals(listOf(1, 1), preferences.bestHoles(2))
    }

    @Test
    fun `a finished round records the total, flags a new best and ends the game`() {
        val model = model()
        model.play()
        model.holeInOne()
        model.keyPressed(KeyAction.CONTINUE)
        model.keyReleased(KeyAction.CONTINUE)
        assertEquals(SessionState.PLAYING, model.state)
        assertFalse(model.isNewBest)

        model.holeInOne()

        // Right after the drop into the last hole, without another click.
        assertEquals(SessionState.OVER, model.state)
        assertFalse(model.needsConfirmToAbandon)
        assertEquals(2, preferences.bestTotal)
        assertEquals(2, model.bestTotal)
        assertTrue(model.isNewBest)
        assertEquals(listOf(1, 1), model.bestHoles)
        assertFalse(model.needsAnimation)
    }

    @Test
    fun `a round that does not beat the best is no new best`() {
        preferences.recordTotal(1)
        val model = model()
        model.play()
        model.holeInOne()
        model.pressed(0.0, 0.0)

        model.holeInOne()

        assertEquals(SessionState.OVER, model.state)
        assertFalse(model.isNewBest)
        assertEquals(1, preferences.bestTotal)
    }

    @Test
    fun `an abandoned round records no total`() {
        val model = model()
        model.play()
        model.holeInOne()

        model.newGame()

        assertNull(preferences.bestTotal)
        assertEquals(listOf(1, null), preferences.bestHoles(2))
    }

    @Test
    fun `in OVER only a new game starts over`() {
        val model = model()
        model.play()
        model.holeInOne()
        model.pressed(0.0, 0.0)
        model.holeInOne()
        assertEquals(SessionState.OVER, model.state)

        model.pressed(0.0, 0.0)
        model.keyPressed(KeyAction.CONTINUE)
        model.keyPressed(KeyAction.PAUSE)
        model.keyPressed(KeyAction.NEW_GAME)

        assertEquals(SessionState.OVER, model.state)
        model.newGame()
        assertEquals(SessionState.READY, model.state)
    }

    @Test
    fun `a new game is a fresh game in READY and clears the new best`() {
        val model = model()
        model.play()
        model.holeInOne()
        model.pressed(0.0, 0.0)
        model.holeInOne()
        assertTrue(model.isNewBest)
        val before = created

        model.newGame()

        assertEquals(before + 1, created)
        assertEquals(SessionState.READY, model.state)
        assertEquals(0, model.game.holeIndex)
        assertNull(model.game.scores[0])
        assertFalse(model.isNewBest)
    }

    @Test
    fun `an abandon question is needed once a stroke is played and the round is not over`() {
        val model = model()
        assertFalse(model.needsConfirmToAbandon)
        model.play()
        assertFalse(model.needsConfirmToAbandon)

        model.pressed(100.0, 100.0)
        model.released(60.0, 100.0)
        assertTrue(model.needsConfirmToAbandon)

        model.newGame()
        assertFalse(model.needsConfirmToAbandon)
    }

    @Test
    fun `a stroke on an earlier hole still needs the question on the next one`() {
        val model = model()
        model.play()
        model.holeInOne()
        model.pressed(0.0, 0.0)

        assertEquals(0, model.game.strokes)
        assertTrue(model.needsConfirmToAbandon)
    }

    @Test
    fun `no abandon question once the round is over`() {
        val model = model()
        model.play()
        model.holeInOne()
        model.pressed(0.0, 0.0)
        model.holeInOne()

        assertFalse(model.needsConfirmToAbandon)
    }

    @ParameterizedTest
    @CsvSource("4,−1", "5,E", "7,+2")
    fun `the status line shows the best against par`(
        best: Int,
        against: String,
    ) {
        preferences.recordTotal(best)

        assertEquals(
            "Drag to aim · release to putt · Esc cancel · P pause · F2 new round · Best: $best ($against)",
            model().statusText,
        )
    }

    @Test
    fun `the status line has no best without one`() {
        assertEquals("Drag to aim · release to putt · Esc cancel · P pause · F2 new round", model().statusText)
    }

    private companion object {
        const val STEP = 16_666_667L
        const val COUNTDOWN = 1_600_000_000L
    }
}
