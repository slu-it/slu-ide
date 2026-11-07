package dev.sluide.games.blockbreaker

import dev.sluide.games.common.SessionState
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class BlockBreakerModelTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var preferences: BlockBreakerPreferences
    private var setup: (BlockBreakerGame) -> Unit = {}
    private val created = mutableListOf<Difficulty>()
    private var now = 1_000_000L
    private var changes = 0

    @BeforeEach
    fun setUp() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        preferences = BlockBreakerPreferences(store, Duration.ZERO) { it.run() }
    }

    /** A model over games with a fixed random; [setup] prepares each game the factory creates. */
    private fun model(focused: Boolean = true): BlockBreakerModel =
        BlockBreakerModel(preferences) { difficulty, _ ->
            created += difficulty
            BlockBreakerGame(difficulty, FixedRandom()).also(setup)
        }.also {
            if (focused) it.session.focusChanged(true)
            it.onChange = { changes++ }
        }

    private fun BlockBreakerModel.advance(nanos: Long) {
        now += nanos
        tick(now)
    }

    /** The first tick of an animation only stores the time base. */
    private fun BlockBreakerModel.startAnimation() = advance(0)

    private fun BlockBreakerModel.press(vararg actions: KeyAction) = actions.forEach(::keyPressed)

    /** The countdown to its end; the model is in READY or PAUSED-and-resumed state. */
    private fun BlockBreakerModel.finishCountdown() {
        startAnimation()
        advance(COUNTDOWN)
        assertEquals(SessionState.PLAYING, state)
    }

    /** Start by click, countdown to the end: nothing is held. */
    private fun BlockBreakerModel.toPlaying() {
        clicked()
        finishCountdown()
    }

    private fun BlockBreakerModel.steps(count: Int) = repeat(count) { advance(STEP) }

    private val gap = "............."

    /** The game is PLAYING with one ball under the brick at column 0, row 0 (an anchor brick keeps the level alive). */
    private fun hitsBrick(game: BlockBreakerGame) {
        game.loadBricks(listOf("1............", gap, "............1"))
        game.step(Input(launch = true))
        game.clearBalls()
        game.addBall(4.0 + 10, 32.0 + 8 + 1, 0.0, -1.0)
    }

    /** The game is PLAYING with its last life and a ball that is lost in the first step; [score] is already won. */
    private fun losesLastBall(
        game: BlockBreakerGame,
        score: Int = 0,
    ) {
        game.setLives(1)
        game.addScore(score)
        loseBall(game)
    }

    private fun loseBall(game: BlockBreakerGame) {
        game.step(Input(launch = true))
        game.clearBalls()
        game.addBall(100.0, 239.0, 0.0, 1.0)
    }

    // start

    @ParameterizedTest
    @EnumSource(value = KeyAction::class, names = ["LEFT", "RIGHT", "LAUNCH"])
    fun `a move or launch key starts the countdown from READY and is eaten`(action: KeyAction) {
        val model = model()

        model.keyPressed(action)

        assertEquals(SessionState.COUNTDOWN, model.state)
        assertEquals(3, model.countdown)
        assertEquals(1, changes)
        model.finishCountdown()
        model.steps(3)
        // The ball still rests on the paddle: the start key launched nothing.
        assertEquals(Phase.SERVE, model.game.phase)
    }

    @ParameterizedTest
    @EnumSource(value = KeyAction::class, names = ["PAUSE", "NEW_GAME"])
    fun `pause and new game keys do nothing in READY`(action: KeyAction) {
        val model = model()

        model.keyPressed(action)

        assertEquals(SessionState.READY, model.state)
        assertEquals(0, changes)
    }

    @Test
    fun `a click starts the countdown from READY and launches nothing`() {
        val model = model()

        model.clicked()

        assertEquals(SessionState.COUNTDOWN, model.state)
        model.finishCountdown()
        model.steps(3)
        assertEquals(Phase.SERVE, model.game.phase)
    }

    // click while paused

    @ParameterizedTest
    @CsvSource("true,COUNTDOWN", "false,PAUSED")
    fun `a click resumes a pause only while the board has the focus`(
        focused: Boolean,
        expected: SessionState,
    ) {
        val model = model()
        model.toPlaying()
        model.press(KeyAction.PAUSE)
        model.session.focusChanged(focused)

        model.clicked()

        assertEquals(expected, model.state)
    }

    @ParameterizedTest
    @EnumSource(value = KeyAction::class, names = ["LEFT", "RIGHT", "LAUNCH", "PAUSE"])
    fun `a resume key restarts the countdown and is eaten`(action: KeyAction) {
        val model = model()
        model.toPlaying()
        model.press(KeyAction.PAUSE)
        model.keyReleased(KeyAction.PAUSE)
        val start = model.game.paddleX

        model.keyPressed(action)

        assertEquals(SessionState.COUNTDOWN, model.state)
        model.finishCountdown()
        model.steps(3)
        assertEquals(start, model.game.paddleX)
        assertEquals(Phase.SERVE, model.game.phase)
    }

    @Test
    fun `the new game key does not resume`() {
        val model = model()
        model.toPlaying()
        model.press(KeyAction.PAUSE)

        model.press(KeyAction.NEW_GAME)

        assertEquals(SessionState.PAUSED, model.state)
    }

    // launch

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a launch key or a click launches while playing`(byKey: Boolean) {
        val model = model()
        model.toPlaying()

        if (byKey) model.press(KeyAction.LAUNCH) else model.clicked()
        model.steps(1)

        assertEquals(Phase.PLAYING, model.game.phase)
    }

    @Test
    fun `a click while counting down launches nothing`() {
        val model = model()
        model.clicked()
        model.startAnimation()

        model.clicked()
        model.advance(COUNTDOWN)
        model.steps(3)

        assertEquals(Phase.SERVE, model.game.phase)
    }

    @Test
    fun `launch is ignored while counting down but a held move key applies afterwards`() {
        val model = model()
        model.clicked()
        model.startAnimation()
        model.press(KeyAction.LEFT, KeyAction.LAUNCH)
        val start = model.game.paddleX
        model.advance(COUNTDOWN)

        model.steps(3)

        assertTrue(model.game.paddleX < start)
        assertEquals(Phase.SERVE, model.game.phase)
    }

    @Test
    fun `the launch is one-shot so that a held key does not serve the next ball`() {
        setup = ::loseBall
        val model = model()
        model.toPlaying()

        model.press(KeyAction.LAUNCH)
        model.steps(1 + Rules.BALL_LOST_STEPS)
        // Auto-repeat presses while the key stays down, during the serve.
        model.press(KeyAction.LAUNCH)
        model.steps(5)
        model.press(KeyAction.LAUNCH)
        model.steps(5)

        assertEquals(Phase.SERVE, model.game.phase)
        assertEquals(2, model.game.lives)

        model.keyReleased(KeyAction.LAUNCH)
        model.press(KeyAction.LAUNCH)
        model.steps(1)

        assertEquals(Phase.PLAYING, model.game.phase)
    }

    @Test
    fun `a launch request not played yet is dropped by a pause`() {
        val model = model()
        model.toPlaying()

        // Space and P within one frame: no step in between.
        model.press(KeyAction.LAUNCH, KeyAction.PAUSE)
        model.keyReleased(KeyAction.LAUNCH)
        model.keyReleased(KeyAction.PAUSE)
        model.clicked()
        model.finishCountdown()
        model.steps(3)

        assertEquals(Phase.SERVE, model.game.phase)
    }

    @Test
    fun `a held P does not flip between pause and resume`() {
        val model = model()
        model.toPlaying()

        model.press(KeyAction.PAUSE, KeyAction.PAUSE, KeyAction.PAUSE)
        assertEquals(SessionState.PAUSED, model.state)
        model.keyReleased(KeyAction.PAUSE)
        model.press(KeyAction.PAUSE, KeyAction.PAUSE)
        assertEquals(SessionState.COUNTDOWN, model.state)
    }

    // pointer

    @ParameterizedTest
    @CsvSource("LEFT,false", "RIGHT,false", "LAUNCH,true")
    fun `a start key hands control to the keys but a launch key keeps the pointer`(
        action: KeyAction,
        follows: Boolean,
    ) {
        val model = model()
        model.pointerMoved(300.0)
        val start = model.game.paddleX

        model.keyPressed(action)
        model.keyReleased(action)
        model.finishCountdown()
        model.steps(3)

        assertEquals(follows, model.game.paddleX > start)
    }

    @ParameterizedTest
    @CsvSource("LEFT,false", "RIGHT,false", "LAUNCH,true", "PAUSE,true")
    fun `a resume key hands control to the keys but a launch or pause key keeps the pointer`(
        action: KeyAction,
        follows: Boolean,
    ) {
        val model = model()
        model.toPlaying()
        model.press(KeyAction.PAUSE)
        model.keyReleased(KeyAction.PAUSE)
        model.pointerMoved(300.0)
        val start = model.game.paddleX

        model.keyPressed(action)
        model.keyReleased(action)
        model.finishCountdown()
        model.steps(3)

        assertEquals(follows, model.game.paddleX > start)
    }

    @Test
    fun `a pointer move moves the paddle in any state and a key press hands control back to the keys`() {
        val model = model()
        model.pointerMoved(300.0)
        model.toPlaying()
        val start = model.game.paddleX
        model.steps(2)
        assertTrue(model.game.paddleX > start)

        model.pointerMoved(300.0)
        model.press(KeyAction.LEFT)
        model.keyReleased(KeyAction.LEFT)
        val parked = model.game.paddleX
        model.steps(3)

        assertEquals(parked, model.game.paddleX)
    }

    // focus

    @ParameterizedTest
    @EnumSource(value = KeyAction::class, names = ["LEFT", "RIGHT"])
    fun `focus loss releases a held move key`(action: KeyAction) {
        val model = model()
        model.toPlaying()
        model.press(action)
        model.steps(1)
        model.session.focusChanged(false)
        model.session.focusChanged(true)
        val parked = model.game.paddleX

        model.clicked()
        model.finishCountdown()
        model.steps(3)

        assertEquals(parked, model.game.paddleX)
    }

    @Test
    fun `focus loss drops a launch request that was not played yet`() {
        val model = model()
        model.toPlaying()
        model.press(KeyAction.LAUNCH)
        model.session.focusChanged(false)
        model.session.focusChanged(true)

        model.clicked()
        model.finishCountdown()
        model.steps(3)

        assertEquals(Phase.SERVE, model.game.phase)
    }

    @Test
    fun `focus loss releases the P latch`() {
        val model = model()
        model.toPlaying()
        model.press(KeyAction.PAUSE)
        model.session.focusChanged(false)
        model.session.focusChanged(true)

        // The release went to another control; the next press must still count.
        model.press(KeyAction.PAUSE)

        assertEquals(SessionState.COUNTDOWN, model.state)
    }

    @Test
    fun `focus loss releases the launch latch`() {
        val model = model()
        model.toPlaying()
        model.press(KeyAction.LAUNCH)
        model.session.focusChanged(false)
        model.session.focusChanged(true)
        model.clicked()
        model.finishCountdown()

        // The release went to another control; a single press must still launch.
        model.press(KeyAction.LAUNCH)
        model.steps(1)

        assertEquals(Phase.PLAYING, model.game.phase)
    }

    @Test
    fun `a held start key never launches through its auto-repeat`() {
        val model = model()
        model.press(KeyAction.LAUNCH)
        model.finishCountdown()

        model.press(KeyAction.LAUNCH)
        model.steps(3)

        assertEquals(Phase.SERVE, model.game.phase)
    }

    @Test
    fun `focus loss keeps the pointer`() {
        val model = model()
        model.toPlaying()
        model.pointerMoved(300.0)
        model.session.focusChanged(false)
        model.session.focusChanged(true)
        val start = model.game.paddleX

        model.clicked()
        model.finishCountdown()
        model.steps(2)

        assertTrue(model.game.paddleX > start)
    }

    // best and game over

    @ParameterizedTest
    @CsvSource("0,true,1", "1000,false,0")
    fun `a score the preferences accept as a best is a new best and notifies the view`(
        stored: Int,
        newBest: Boolean,
        expectedChanges: Int,
    ) {
        if (stored > 0) preferences.recordScore(Difficulty.NORMAL, stored)
        setup = ::hitsBrick
        val model = model()
        model.toPlaying()
        changes = 0

        model.steps(1)

        assertTrue(model.game.score > 0)
        assertEquals(newBest, model.isNewBest)
        assertEquals(if (newBest) model.game.score else stored, model.best)
        assertEquals(expectedChanges, changes)
    }

    @Test
    fun `a new game starts without a new best and without held input or pointer`() {
        setup = ::hitsBrick
        val model = model()
        model.toPlaying()
        model.steps(1)
        assertTrue(model.isNewBest)
        model.press(KeyAction.LEFT)
        model.pointerMoved(300.0)

        model.newGame()

        assertFalse(model.isNewBest)
        assertEquals(SessionState.READY, model.state)
        model.toPlaying()
        val start = model.game.paddleX
        model.steps(3)
        assertEquals(start, model.game.paddleX)
    }

    @Test
    fun `losing the last ball ends the game and notifies once`() {
        setup = ::losesLastBall
        val model = model()
        model.toPlaying()
        changes = 0

        model.steps(Rules.BALL_LOST_STEPS + 1)

        assertEquals(SessionState.OVER, model.state)
        assertEquals(1, changes)
    }

    @Test
    fun `choosing another difficulty saves it and starts a new game, the same one does nothing`() {
        val model = model()
        model.game
        created.clear()

        model.chooseDifficulty(Difficulty.NORMAL)
        assertTrue(created.isEmpty())
        model.chooseDifficulty(Difficulty.HARD)

        assertEquals(listOf(Difficulty.HARD), created)
        assertEquals(Difficulty.HARD, preferences.lastDifficulty)
        assertEquals(Difficulty.HARD, model.game.difficulty)
    }

    // texts and flags

    @Test
    fun `the pointer is hidden only while playing`() {
        val model = model()
        assertFalse(model.hidesPointer)
        model.clicked()
        assertFalse(model.hidesPointer)
        model.finishCountdown()
        assertTrue(model.hidesPointer)
        model.press(KeyAction.PAUSE)
        assertFalse(model.hidesPointer)
    }

    @Test
    fun `the status line shows the best only when there is one`() {
        val keys = "← → or mouse move · Space or click launch · P pause · F2 new game"
        val model = model()
        assertEquals(keys, model.statusText)

        preferences.recordScore(Difficulty.NORMAL, 12340)

        assertEquals("$keys · Best: 12,340", model.statusText)
    }

    @Test
    fun `the hint and ready texts`() {
        val model = model(focused = false)
        assertEquals("Click the game to focus it", model.pauseHint)

        model.session.focusChanged(true)

        assertEquals("Press P, ← →, Space or click to resume", model.pauseHint)
        assertEquals("Press ← →, Space or click to start", model.readyText)
    }

    @Test
    fun `only a running game with a score needs a confirmation to abandon`() {
        setup = ::hitsBrick
        val model = model()
        assertFalse(model.needsConfirmToAbandon)
        model.toPlaying()
        assertFalse(model.needsConfirmToAbandon)

        model.steps(1)
        assertTrue(model.needsConfirmToAbandon)

        setup = { losesLastBall(it, score = 50) }
        val over = model()
        over.toPlaying()
        over.steps(Rules.BALL_LOST_STEPS + 1)
        assertEquals(SessionState.OVER, over.state)
        assertFalse(over.needsConfirmToAbandon)
    }

    private companion object {
        const val STEP = 16_666_667L
        const val DIGIT = 500_000_000L
        const val COUNTDOWN = 3 * DIGIT
    }
}
