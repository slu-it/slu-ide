package dev.sluide.games.orbitguard

import dev.sluide.games.common.PauseReason
import dev.sluide.games.common.RealtimeSession
import dev.sluide.games.common.SessionState
import java.util.Locale
import kotlin.random.Random

/**
 * The Orbit Guard game of one window and what the view shows of it: the held keys and what the keys mean on top of the
 * [session] (ready, countdown, pause, timing), the score and the best. Outlives the tab (owned by the window bean);
 * the view that shows it owns [onChange]. FX thread, no JavaFX imports. The view draws every frame while
 * [needsAnimation], so steps do not call [onChange].
 */
internal class OrbitGuardModel(
    private val preferences: OrbitGuardPreferences,
    private val random: Random = Random.Default,
    private val gameFactory: (Difficulty, Random) -> OrbitGuardGame = ::OrbitGuardGame,
) {
    val session =
        RealtimeSession({ focused ->
            if (focused) {
                "Press P, ← → or Space to resume"
            } else {
                "Click the game, then press P, ← → or Space to resume"
            }
        })

    /** Called after every change of [state], [pauseReason], [pauseHint], [difficulty] and after a new game. */
    var onChange: () -> Unit = {}

    private var chosen: Difficulty? = null
    private var current: OrbitGuardGame? = null
    private var newBest = false
    private var pauseKeyDown = false
    private var left = false
    private var right = false
    private var fire = false

    init {
        session.onChange = { onChange() }
        session.onStep = ::step
        session.onFocusLost = ::releaseKeys
    }

    val state: SessionState get() = session.state

    val pauseReason: PauseReason? get() = session.pauseReason

    val difficulty: Difficulty get() = chosen ?: preferences.lastDifficulty.also { chosen = it }

    val game: OrbitGuardGame get() = current ?: create()

    val pauseHint: String get() = session.pauseHint

    /** 3, 2, 1 while counting down, else 0. */
    val countdown: Int get() = session.countdown

    val best: Int get() = preferences.best(difficulty)

    val isNewBest: Boolean get() = newBest

    val statusText: String
        get() {
            val keys = "← → move · Space fire · P pause · F2 new game"
            return if (best > 0) "$keys · Best: ${String.format(Locale.ROOT, "%,d", best)}" else keys
        }

    val needsAnimation: Boolean get() = session.needsAnimation

    val canPause: Boolean get() = session.canPause

    val pauseButtonText: String get() = session.pauseButtonText

    /** A running game with a score is worth a question before it is abandoned. */
    val needsConfirmToAbandon: Boolean get() = game.score > 0 && state != SessionState.OVER

    fun keyPressed(action: KeyAction) {
        if (action == KeyAction.PAUSE) {
            // Key auto-repeat: only the first press counts until the release.
            if (pauseKeyDown) return
            pauseKeyDown = true
        }
        when (state) {
            SessionState.READY -> {
                if (action.isGameKey()) session.startOrResume()
            }

            SessionState.PAUSED -> {
                if (action != KeyAction.NEW_GAME) session.startOrResume()
            }

            SessionState.COUNTDOWN, SessionState.PLAYING -> {
                when (action) {
                    KeyAction.LEFT -> left = true
                    KeyAction.RIGHT -> right = true
                    KeyAction.FIRE -> if (state == SessionState.PLAYING) fire = true
                    KeyAction.PAUSE -> session.togglePause()
                    KeyAction.NEW_GAME -> Unit
                }
            }

            SessionState.OVER -> {
                Unit
            }
        }
    }

    /** Releases always count, so that a key is never stuck. */
    fun keyReleased(action: KeyAction) {
        when (action) {
            KeyAction.LEFT -> left = false
            KeyAction.RIGHT -> right = false
            KeyAction.FIRE -> fire = false
            KeyAction.PAUSE -> pauseKeyDown = false
            KeyAction.NEW_GAME -> Unit
        }
    }

    /** The button. */
    fun togglePause() = session.togglePause()

    /** [nowNanos] is a monotonic time; see [RealtimeSession.tick]. */
    fun tick(nowNanos: Long) = session.tick(nowNanos)

    /** Always starts over; the view asks for confirmation first when [needsConfirmToAbandon]. */
    fun newGame() {
        create()
        newBest = false
        releaseKeys()
        session.reset()
    }

    fun chooseDifficulty(difficulty: Difficulty) {
        if (difficulty == this.difficulty) return
        preferences.saveDifficulty(difficulty)
        chosen = difficulty
        newGame()
    }

    private fun KeyAction.isGameKey() = this == KeyAction.LEFT || this == KeyAction.RIGHT || this == KeyAction.FIRE

    private fun create(): OrbitGuardGame = gameFactory(difficulty, random).also { current = it }

    private fun releaseKeys() {
        left = false
        right = false
        fire = false
        pauseKeyDown = false
    }

    private fun step() {
        val before = game.score
        val events = game.step(Input(left, right, fire))
        if (game.score != before && preferences.recordScore(difficulty, game.score)) {
            newBest = true
            // The status line shows the best.
            onChange()
        }
        if (game.isOver || GameEvent.GameOver in events) {
            releaseKeys()
            session.gameOver()
        }
    }
}
