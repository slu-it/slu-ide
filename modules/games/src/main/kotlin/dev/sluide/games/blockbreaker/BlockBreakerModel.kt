package dev.sluide.games.blockbreaker

import dev.sluide.games.common.PauseReason
import dev.sluide.games.common.RealtimeSession
import dev.sluide.games.common.SessionState
import java.util.Locale
import kotlin.random.Random

/**
 * The Block Breaker game of one window and what the view shows of it: the held keys, the mouse pointer and what keys
 * and clicks mean on top of the [session] (ready, countdown, pause, timing), the score and the best. Outlives the tab
 * (owned by the window bean); the view that shows it owns [onChange]. FX thread, no JavaFX imports. The view draws
 * every frame while [needsAnimation], so steps do not call [onChange].
 *
 * The launch is a one-shot request, not a held key: it is passed to the game once, and a launch key counts only on its
 * first press until its release (auto-repeat sends a press each time), so that a held Space does not serve the next
 * ball by itself. A request not played yet is dropped when the session leaves PLAYING. Keys win over the pointer
 * until the mouse moves again.
 */
internal class BlockBreakerModel(
    private val preferences: BlockBreakerPreferences,
    private val random: Random = Random.Default,
    private val gameFactory: (Difficulty, Random) -> BlockBreakerGame = ::BlockBreakerGame,
) {
    val session =
        RealtimeSession({ focused ->
            if (focused) "Press P, ← →, Space or click to resume" else "Click the game to focus it"
        })

    /** Called after every change of [state], [pauseReason], [pauseHint], [difficulty] and after a new game. */
    var onChange: () -> Unit = {}

    private var chosen: Difficulty? = null
    private var current: BlockBreakerGame? = null
    private var newBest = false
    private var pauseKeyDown = false
    private var launchKeyDown = false
    private var left = false
    private var right = false
    private var launchRequested = false
    private var pointerX: Double? = null
    private var windowActive = true

    init {
        session.onChange = {
            if (state != SessionState.PLAYING) launchRequested = false
            onChange()
        }
        session.onStep = ::step
        session.onFocusLost = ::releaseInput
    }

    val state: SessionState get() = session.state

    val pauseReason: PauseReason? get() = session.pauseReason

    val difficulty: Difficulty get() = chosen ?: preferences.lastDifficulty.also { chosen = it }

    val game: BlockBreakerGame get() = current ?: create()

    /** The overlay text of PAUSED: a click resumes at once in an inactive window whose focus owner is the board. */
    val pauseHint: String get() = if (windowActive) session.pauseHint else "Click the game to resume"

    /** 3, 2, 1 while counting down, else 0. */
    val countdown: Int get() = session.countdown

    val best: Int get() = preferences.best(difficulty)

    val isNewBest: Boolean get() = newBest

    val statusText: String
        get() {
            val keys = "← → or mouse move · Space or click launch · P pause · F2 new game"
            return if (best > 0) "$keys · Best: ${String.format(Locale.ROOT, "%,d", best)}" else keys
        }

    /** Shown by the view in READY. */
    val readyText: String get() = "Press ← →, Space or click to start"

    val needsAnimation: Boolean get() = session.needsAnimation

    val canPause: Boolean get() = session.canPause

    val pauseButtonText: String get() = session.pauseButtonText

    /** A running game with a score is worth a question before it is abandoned. */
    val needsConfirmToAbandon: Boolean get() = game.score > 0 && state != SessionState.OVER

    /** The view hides the mouse pointer over the field while playing. */
    val hidesPointer: Boolean get() = state == SessionState.PLAYING

    fun keyPressed(action: KeyAction) {
        if (action == KeyAction.PAUSE) {
            // Key auto-repeat: only the first press counts until the release.
            if (pauseKeyDown) return
            pauseKeyDown = true
        }
        if (action == KeyAction.LAUNCH) {
            // Auto-repeat again: a held key launches once, not at every serve.
            if (launchKeyDown) return
            launchKeyDown = true
        }
        // The keys win over the pointer until the mouse moves again.
        if (action == KeyAction.LEFT || action == KeyAction.RIGHT) pointerX = null
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
                    KeyAction.LAUNCH -> if (state == SessionState.PLAYING) launchRequested = true
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
            KeyAction.PAUSE -> pauseKeyDown = false
            KeyAction.LAUNCH -> launchKeyDown = false
            KeyAction.NEW_GAME -> Unit
        }
    }

    /**
     * The window of the board became [active] or inactive; the view forwards it, counting an inactive window whose
     * focus owner is not the board as active (activating it does not focus the board). Updates the hint while PAUSED.
     */
    fun windowFocusChanged(active: Boolean) {
        if (active == windowActive) return
        windowActive = active
        if (state == SessionState.PAUSED) onChange()
    }

    /** The mouse is at [x] in logical field pixels (the view converts), in every state. */
    fun pointerMoved(x: Double) {
        pointerX = x
    }

    /**
     * A primary click on the field or the pause overlay: starts in READY, launches in PLAYING, resumes in PAUSED only
     * while the board has the focus. The view calls this BEFORE it requests the focus, so the first click on an
     * unfocused board only focuses it and the second one resumes.
     */
    fun clicked() {
        when (state) {
            SessionState.READY -> session.startOrResume()
            SessionState.PAUSED -> if (session.isFocused) session.startOrResume()
            SessionState.PLAYING -> launchRequested = true
            SessionState.COUNTDOWN, SessionState.OVER -> Unit
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
        releaseInput()
        pointerX = null
        session.reset()
    }

    fun chooseDifficulty(difficulty: Difficulty) {
        if (difficulty == this.difficulty) return
        preferences.saveDifficulty(difficulty)
        chosen = difficulty
        newGame()
    }

    private fun KeyAction.isGameKey() = this == KeyAction.LEFT || this == KeyAction.RIGHT || this == KeyAction.LAUNCH

    private fun create(): BlockBreakerGame = gameFactory(difficulty, random).also { current = it }

    private fun releaseInput() {
        left = false
        right = false
        // Belt and braces: the onChange hook of the session drops the request too.
        launchRequested = false
        pauseKeyDown = false
        launchKeyDown = false
    }

    private fun step() {
        val before = game.score
        val launch = launchRequested
        launchRequested = false
        val events = game.step(Input(left, right, launch, pointerX))
        if (game.score != before && preferences.recordScore(difficulty, game.score)) {
            newBest = true
            // The status line shows the best.
            onChange()
        }
        if (game.isOver || GameEvent.GameOver in events) {
            releaseInput()
            session.gameOver()
        }
    }
}
