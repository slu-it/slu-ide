package dev.sluide.games.minigolf

import dev.sluide.games.common.PauseReason
import dev.sluide.games.common.RealtimeSession
import dev.sluide.games.common.SessionState
import dev.sluide.games.minigolf.geometry.Vec
import kotlin.math.min

/**
 * The Mini Golf round of one window and what the view shows of it: the aim of a drag and what keys and clicks mean on
 * top of the [session] (ready, countdown, pause, timing, the scorecard), the best scores. Outlives the tab (owned by
 * the window bean); the view that shows it owns [onChange]. FX thread, no JavaFX imports. The view draws every frame
 * while [needsAnimation], so steps do not call [onChange] (a changed aim, a finished hole and the like do).
 *
 * The pointer is in logical field units. A press in PLAYING while the ball rests stores the press point, a drag aims
 * opposite to it (power: the drag length up to [MAX_DRAG] units), a release of at least [MIN_DRAG] units shoots. Any
 * change away from PLAYING and a ball that starts moving by itself (a pushing obstacle) cancel the aim.
 */
internal class MiniGolfModel(
    private val preferences: MiniGolfPreferences,
    private val gameFactory: () -> MiniGolfGame = { MiniGolfGame() },
) {
    val session =
        RealtimeSession({ focused ->
            if (focused) "Press P or click to resume" else "Click the game to focus it"
        })

    /** Called after every change of [state], [pauseReason], [pauseHint], [aim], the game phase and after a new game. */
    var onChange: () -> Unit = {}

    /** A hole in one: the view starts the burst at [cup]. */
    var onHoleInOne: (cup: Vec) -> Unit = {}

    private var current: MiniGolfGame? = null
    private var newBest = false
    private var pauseKeyDown = false
    private var continueKeyDown = false
    private var windowActive = true
    private var pressPoint: Vec? = null

    /** The shot a drag aims at; null unless a drag is aiming. */
    var aim: Aim? = null
        private set

    init {
        session.onChange = {
            if (state != SessionState.PLAYING) clearAim()
            onChange()
        }
        session.onStep = ::step
        session.onFocusLost = ::releaseInput
    }

    val state: SessionState get() = session.state

    val pauseReason: PauseReason? get() = session.pauseReason

    val game: MiniGolfGame get() = current ?: create()

    /** The overlay text of PAUSED: a click resumes at once in an inactive window whose focus owner is the board. */
    val pauseHint: String get() = if (windowActive) session.pauseHint else "Click the game to resume"

    /** 3, 2, 1 while counting down, else 0. */
    val countdown: Int get() = session.countdown

    /** Shown by the view in READY. */
    val readyText: String get() = "Click or press Space to start"

    val bestTotal: Int? get() = preferences.bestTotal

    val bestHoles: List<Int?> get() = preferences.bestHoles(game.course.size)

    val isNewBest: Boolean get() = newBest

    val statusText: String
        get() {
            val keys = "Drag to aim · release to putt · Esc cancel · P pause · F2 new round"
            val best = bestTotal ?: return keys
            val diff = best - game.course.sumOf { it.par }
            return "$keys · Best: $best (${ScoreText.toPar(diff)})"
        }

    val needsAnimation: Boolean get() = session.needsAnimation

    val canPause: Boolean get() = session.canPause

    val pauseButtonText: String get() = session.pauseButtonText

    /** A round with a stroke played is worth a question before it is abandoned. */
    val needsConfirmToAbandon: Boolean
        get() = state != SessionState.OVER && (game.strokes > 0 || game.scores.any { it != null })

    fun keyPressed(action: KeyAction) {
        if (action == KeyAction.PAUSE) {
            // Key auto-repeat: only the first press counts until the release.
            if (pauseKeyDown) return
            pauseKeyDown = true
        }
        if (action == KeyAction.CONTINUE) {
            // Auto-repeat again: a held key goes on once, not through every card.
            if (continueKeyDown) return
            continueKeyDown = true
        }
        when (action) {
            KeyAction.PAUSE -> session.togglePause()
            KeyAction.CANCEL -> cancelAim()
            KeyAction.CONTINUE -> continueOrStart()
            KeyAction.NEW_GAME -> Unit
        }
    }

    /** Releases always count, so that a key is never stuck. */
    fun keyReleased(action: KeyAction) {
        when (action) {
            KeyAction.PAUSE -> pauseKeyDown = false
            KeyAction.CONTINUE -> continueKeyDown = false
            else -> Unit
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

    /**
     * A primary press at ([x], [y]) on the field or the pause overlay: starts in READY (that press never aims), goes on
     * past the card, starts an aim while the ball rests and resumes in PAUSED only while the board has the focus. The
     * view calls this BEFORE it requests the focus, so the first click on an unfocused board only focuses it.
     */
    fun pressed(
        x: Double,
        y: Double,
    ) {
        when (state) {
            SessionState.READY -> {
                session.startOrResume()
            }

            SessionState.PAUSED -> {
                if (session.isFocused) session.startOrResume()
            }

            SessionState.PLAYING -> {
                when (game.phase) {
                    Phase.CARD -> advance { game.continueToNext() }
                    Phase.AIMING -> pressPoint = Vec(x, y)
                    else -> Unit
                }
            }

            SessionState.COUNTDOWN, SessionState.OVER -> {
                Unit
            }
        }
    }

    fun dragged(
        x: Double,
        y: Double,
    ) {
        val start = pressPoint ?: return
        if (!canAim()) return cancelAim()
        val next = aimOf(start, Vec(x, y))
        if (next == aim) return
        aim = next
        onChange()
    }

    fun released(
        x: Double,
        y: Double,
    ) {
        val start = pressPoint ?: return
        val shoot = canAim() && (Vec(x, y) - start).length >= MIN_DRAG
        val shot = aimOf(start, Vec(x, y))
        clearAim()
        if (shoot) game.shoot(shot.angle, shot.power)
        onChange()
    }

    /** A right click or Esc: drops the aim without a shot. */
    fun cancelAim() {
        if (pressPoint == null && aim == null) return
        clearAim()
        onChange()
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
        clearAim()
        session.reset()
    }

    private fun canAim() = state == SessionState.PLAYING && game.phase == Phase.AIMING

    private fun aimOf(
        from: Vec,
        to: Vec,
    ): Aim {
        val drag = to - from
        return Aim((-drag).angle(), min(drag.length, MAX_DRAG) / MAX_DRAG)
    }

    private fun clearAim() {
        pressPoint = null
        aim = null
    }

    private fun create(): MiniGolfGame = gameFactory().also { current = it }

    private fun releaseInput() {
        pauseKeyDown = false
        continueKeyDown = false
    }

    private fun continueOrStart() {
        when (state) {
            SessionState.READY -> session.startOrResume()
            SessionState.PLAYING -> if (game.phase == Phase.CARD) advance { game.continueToNext() }
            else -> Unit
        }
    }

    private fun step() {
        advance { game.step() }
        if ((pressPoint != null || aim != null) && game.phase != Phase.AIMING) cancelAim()
    }

    /**
     * Runs [move] on the game and handles its events; a change of the phase or a finished hole is a change. The card
     * of the last hole ends the round at once: the final card shows in OVER.
     */
    private fun advance(move: () -> List<GameEvent>) {
        val before = game.phase
        var changed = handle(move())
        if (game.phase == Phase.CARD && game.holeIndex == game.course.lastIndex) {
            changed = handle(game.continueToNext()) || changed
        }
        if (changed || game.phase != before) onChange()
    }

    private fun handle(events: List<GameEvent>): Boolean {
        var changed = false
        for (event in events) {
            when (event) {
                is GameEvent.Holed -> {
                    if (event.holeInOne) onHoleInOne(game.hole.cup)
                }

                is GameEvent.HoleFinished -> {
                    preferences.recordHole(event.holeIndex, event.score)
                    changed = true
                }

                is GameEvent.RoundFinished -> {
                    if (preferences.recordTotal(event.total)) newBest = true
                    session.gameOver()
                    changed = true
                }

                else -> {
                    Unit
                }
            }
        }
        return changed
    }

    companion object {
        /** A drag of this many units or more is a full-power shot. */
        const val MAX_DRAG = 80.0

        /** A release after a shorter drag only cancels. */
        const val MIN_DRAG = 4.0
    }
}
