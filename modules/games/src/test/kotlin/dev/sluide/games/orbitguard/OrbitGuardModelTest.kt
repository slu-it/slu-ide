package dev.sluide.games.orbitguard

import dev.sluide.games.common.PauseReason
import dev.sluide.games.common.SessionState
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal class OrbitGuardModelTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var preferences: OrbitGuardPreferences
    private val games = mutableListOf<OrbitGuardGame>()
    private var setup: (OrbitGuardGame) -> Unit = {}
    private var now = 1_000_000L
    private var changes = 0

    @BeforeEach
    fun setUp() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        preferences = OrbitGuardPreferences(store, Duration.ZERO) { it.run() }
    }

    /** A model over quiet games (the enemies never fire); [setup] prepares each game the factory creates. */
    private fun model(focused: Boolean = true): OrbitGuardModel =
        OrbitGuardModel(preferences) { difficulty, _ ->
            OrbitGuardGame(difficulty, ScriptedRandom()).also {
                setup(it)
                games += it
            }
        }.also {
            if (focused) it.session.focusChanged(true)
            it.onChange = { changes++ }
        }

    private fun OrbitGuardModel.advance(nanos: Long) {
        now += nanos
        tick(now)
    }

    /** The first tick of an animation only stores the time base. */
    private fun OrbitGuardModel.startAnimation() = advance(0)

    private fun OrbitGuardModel.press(vararg actions: KeyAction) = actions.forEach(::keyPressed)

    /** Start key, countdown to the end; the start key is not held afterwards. */
    private fun OrbitGuardModel.toPlaying() {
        press(KeyAction.FIRE)
        keyReleased(KeyAction.FIRE)
        startAnimation()
        advance(COUNTDOWN)
        assertEquals(SessionState.PLAYING, state)
    }

    private fun OrbitGuardModel.steps(count: Int) = repeat(count) { advance(STEP) }

    /** Sets up a shot that kills a scout (30 points) in the next step, with an anchor enemy left alive. */
    private fun killScout(game: OrbitGuardGame) {
        val target = FleetEnemy(EnemyKind.SCOUT, 5, 0, 100.0, 100.0)
        game.fleet = Fleet(listOf(FleetEnemy(EnemyKind.SCOUT, 0, 0, 10.0, 40.0), target), 1.0)
        game.addPlayerShot(target.x + 2, target.y + 10)
    }

    private fun overGame(game: OrbitGuardGame) {
        game.fleet = Fleet(listOf(FleetEnemy(EnemyKind.SCOUT, 0, 0, 100.0, 208.0)), 1.0)
    }

    @ParameterizedTest
    @EnumSource(value = KeyAction::class, names = ["LEFT", "RIGHT", "FIRE"])
    fun `a move or fire key starts the countdown from READY`(action: KeyAction) {
        val model = model()
        assertEquals(SessionState.READY, model.state)

        model.keyPressed(action)

        assertEquals(SessionState.COUNTDOWN, model.state)
        assertEquals(3, model.countdown)
        assertEquals(1, changes)
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
    fun `the start key is eaten and fires no shot`() {
        val model = model()
        model.keyPressed(KeyAction.FIRE)
        model.startAnimation()
        model.advance(COUNTDOWN)

        model.advance(STEP)

        assertEquals(SessionState.PLAYING, model.state)
        assertTrue(model.game.playerShots.isEmpty())
    }

    @Test
    fun `P pauses while counting down and while playing`() {
        val counting = model()
        counting.press(KeyAction.FIRE)
        counting.press(KeyAction.PAUSE)
        assertEquals(SessionState.PAUSED, counting.state)
        assertEquals(PauseReason.MANUAL, counting.pauseReason)

        val playing = model()
        playing.toPlaying()
        playing.press(KeyAction.PAUSE)
        assertEquals(SessionState.PAUSED, playing.state)
        assertEquals(PauseReason.MANUAL, playing.pauseReason)
    }

    @Test
    fun `P does nothing in READY and OVER`() {
        val model = model()
        model.press(KeyAction.PAUSE)
        assertEquals(SessionState.READY, model.state)
        model.togglePause()
        assertEquals(SessionState.READY, model.state)

        setup = { overGame(it) }
        val over = model()
        over.toPlaying()
        over.steps(1)
        assertEquals(SessionState.OVER, over.state)
        over.press(KeyAction.PAUSE)
        over.togglePause()
        assertEquals(SessionState.OVER, over.state)
    }

    @Test
    fun `a held key stops moving the ship when the focus is lost`() {
        val model = model()
        model.toPlaying()
        model.startAnimation()
        model.press(KeyAction.LEFT)
        model.steps(2)
        assertTrue(model.game.shipX < 153.0)
        model.session.focusChanged(false)
        model.session.focusChanged(true)
        val parked = model.game.shipX

        // The resume key is eaten; the physical LEFT is still down but was released by the focus loss.
        model.press(KeyAction.FIRE)
        model.startAnimation()
        model.advance(COUNTDOWN)
        model.steps(3)

        assertEquals(parked, model.game.shipX)
    }

    @Test
    fun `a key released while the focus was elsewhere after a manual pause does not stay held`() {
        val model = model()
        model.toPlaying()
        model.startAnimation()
        model.press(KeyAction.LEFT)
        model.steps(1)
        model.press(KeyAction.PAUSE)
        model.keyReleased(KeyAction.PAUSE)
        // The user clicks another tab and lets go of LEFT there: the model never sees the release.
        model.session.focusChanged(false)
        model.session.focusChanged(true)
        val parked = model.game.shipX

        model.press(KeyAction.FIRE)
        model.startAnimation()
        model.advance(COUNTDOWN)
        model.steps(3)

        assertEquals(parked, model.game.shipX)
    }

    @Test
    fun `the hint texts differ by focus`() {
        val model = model(focused = false)
        assertEquals("Click the game, then press P, ← → or Space to resume", model.pauseHint)

        model.session.focusChanged(true)

        assertEquals("Press P, ← → or Space to resume", model.pauseHint)
    }

    @Test
    fun `the button pauses and resumes through the session`() {
        val model = model()
        model.toPlaying()

        model.togglePause()
        assertEquals(SessionState.PAUSED, model.state)
        assertEquals("Resume", model.pauseButtonText)
        model.togglePause()

        assertEquals(SessionState.COUNTDOWN, model.state)
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

    @Test
    fun `a manual pause keeps the held keys`() {
        val model = model()
        model.toPlaying()
        model.startAnimation()
        model.press(KeyAction.LEFT)
        model.press(KeyAction.PAUSE)
        model.keyReleased(KeyAction.PAUSE)
        model.press(KeyAction.PAUSE)
        model.startAnimation()
        model.advance(COUNTDOWN)
        val before = model.game.shipX

        model.steps(2)

        assertTrue(model.game.shipX < before)
    }

    @ParameterizedTest
    @EnumSource(value = KeyAction::class, names = ["LEFT", "RIGHT", "FIRE", "PAUSE"])
    fun `a resume key restarts the countdown and is eaten`(action: KeyAction) {
        val model = model()
        model.press(KeyAction.FIRE)
        model.startAnimation()
        model.advance(DIGIT + 1)
        assertEquals(2, model.countdown)
        model.press(KeyAction.PAUSE)
        model.keyReleased(KeyAction.PAUSE)
        assertEquals(SessionState.PAUSED, model.state)
        val start = model.game.shipX

        model.keyPressed(action)

        assertEquals(SessionState.COUNTDOWN, model.state)
        assertNull(model.pauseReason)
        assertEquals(3, model.countdown)
        model.startAnimation()
        model.advance(COUNTDOWN)
        model.steps(2)
        assertEquals(start, model.game.shipX)
        assertTrue(model.game.playerShots.isEmpty())
    }

    @Test
    fun `the new game key does not resume`() {
        val model = model()
        model.toPlaying()
        model.press(KeyAction.PAUSE)

        model.press(KeyAction.NEW_GAME)

        assertEquals(SessionState.PAUSED, model.state)
    }

    @Test
    fun `fire is ignored while counting down but a held move key applies afterwards`() {
        val model = model()
        model.press(KeyAction.FIRE)
        model.startAnimation()
        model.press(KeyAction.LEFT, KeyAction.FIRE)
        val start = model.game.shipX
        model.advance(COUNTDOWN)

        model.steps(3)

        assertTrue(model.game.shipX < start)
        assertTrue(model.game.playerShots.isEmpty())
    }

    @Test
    fun `a key released during the countdown is not held afterwards`() {
        val model = model()
        model.press(KeyAction.FIRE)
        model.startAnimation()
        model.press(KeyAction.LEFT)
        model.keyReleased(KeyAction.LEFT)
        val start = model.game.shipX
        model.advance(COUNTDOWN)

        model.steps(3)

        assertEquals(start, model.game.shipX)
    }

    @Test
    fun `held fire shoots while playing and a release stops it`() {
        val model = model()
        model.toPlaying()
        model.press(KeyAction.FIRE)
        model.steps(1)
        assertEquals(1, model.game.playerShots.size)

        model.keyReleased(KeyAction.FIRE)
        // Long enough for the shot to end; held fire would have kept shooting.
        model.steps(120)

        assertTrue(model.game.playerShots.isEmpty())
    }

    @Test
    fun `a score the preferences accept as a best is a new best`() {
        preferences.recordScore(Difficulty.NORMAL, 50)
        val model = model()
        model.toPlaying()

        killScout(model.game)
        model.steps(1)
        assertEquals(30, model.game.score)
        assertEquals(50, model.best)
        assertFalse(model.isNewBest)

        killScout(model.game)
        model.steps(1)
        assertEquals(60, model.best)
        assertTrue(model.isNewBest)
    }

    @Test
    fun `a best raised elsewhere meanwhile makes the score no new best`() {
        val model = model()
        model.toPlaying()
        // Another window records a higher best in the shared preferences after this game started.
        preferences.recordScore(Difficulty.NORMAL, 100)

        killScout(model.game)
        model.steps(1)

        assertEquals(30, model.game.score)
        assertEquals(100, model.best)
        assertFalse(model.isNewBest)
    }

    @Test
    fun `a new game starts without a new best`() {
        val model = model()
        model.toPlaying()
        assertFalse(model.isNewBest)
        killScout(model.game)
        model.steps(1)
        assertTrue(model.isNewBest)

        model.newGame()
        model.toPlaying()
        killScout(model.game)
        model.steps(1)

        assertEquals(30, model.best)
        assertEquals(30, model.game.score)
        assertFalse(model.isNewBest)
    }

    @Test
    fun `game over ends the game, notifies once and ignores every key`() {
        setup = { overGame(it) }
        val model = model()
        model.toPlaying()
        model.startAnimation()
        changes = 0

        model.steps(1)

        assertEquals(SessionState.OVER, model.state)
        assertFalse(model.needsAnimation)
        assertEquals(1, changes)
        model.press(KeyAction.LEFT, KeyAction.FIRE, KeyAction.PAUSE, KeyAction.NEW_GAME)
        model.togglePause()
        model.session.focusChanged(false)
        assertEquals(SessionState.OVER, model.state)
        assertEquals(1, changes)
    }

    @Test
    fun `only a running game with a score needs a confirmation`() {
        val model = model()
        assertFalse(model.needsConfirmToAbandon)
        model.toPlaying()
        assertFalse(model.needsConfirmToAbandon)
        model.game.addScore(10)
        assertTrue(model.needsConfirmToAbandon)

        setup = {
            overGame(it)
            it.addScore(10)
        }
        model.newGame()
        model.toPlaying()
        model.startAnimation()
        model.steps(1)
        assertEquals(SessionState.OVER, model.state)
        assertFalse(model.needsConfirmToAbandon)
    }

    @Test
    fun `the difficulty is the saved one`() {
        preferences.saveDifficulty(Difficulty.HARD)

        val model = model()

        assertEquals(Difficulty.HARD, model.difficulty)
        assertEquals(Difficulty.HARD, model.game.difficulty)
    }

    @Test
    fun `choosing the same difficulty changes nothing`() {
        val model = model()
        model.toPlaying()
        val game = model.game
        changes = 0

        model.chooseDifficulty(Difficulty.NORMAL)

        assertSame(game, model.game)
        assertEquals(SessionState.PLAYING, model.state)
        assertEquals(0, changes)
    }

    @Test
    fun `choosing another difficulty saves it and starts a new game`() {
        val model = model()
        model.toPlaying()
        val game = model.game
        changes = 0

        model.chooseDifficulty(Difficulty.EASY)

        assertEquals(Difficulty.EASY, preferences.lastDifficulty)
        assertEquals(Difficulty.EASY, model.difficulty)
        assertEquals(Difficulty.EASY, model.game.difficulty)
        assertTrue(model.game !== game)
        assertEquals(SessionState.READY, model.state)
        assertEquals(1, changes)
    }

    @Test
    fun `a new game is READY, forgets held keys and notifies`() {
        val model = model()
        model.toPlaying()
        model.startAnimation()
        model.press(KeyAction.LEFT)
        model.steps(1)
        changes = 0

        model.newGame()

        assertEquals(SessionState.READY, model.state)
        assertEquals(1, changes)
        model.toPlaying()
        val start = model.game.shipX
        model.steps(3)
        assertEquals(start, model.game.shipX)
    }

    @Test
    fun `the status text shows the best only when there is one`() {
        val model = model()
        assertEquals("← → move · Space fire · P pause · F2 new game", model.statusText)

        preferences.recordScore(Difficulty.NORMAL, 12340)

        assertEquals("← → move · Space fire · P pause · F2 new game · Best: 12,340", model.statusText)
    }

    private companion object {
        const val STEP = 16_666_667L
        const val DIGIT = 500_000_000L
        const val COUNTDOWN = 3 * DIGIT
        const val SHIP_SPEED = 1.5
    }
}
