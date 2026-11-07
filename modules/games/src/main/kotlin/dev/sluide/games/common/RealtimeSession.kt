package dev.sluide.games.common

internal enum class SessionState { READY, COUNTDOWN, PLAYING, PAUSED, OVER }

internal enum class PauseReason { MANUAL, FOCUS }

/**
 * The game-independent part of a real-time game: ready, 3-2-1 countdown, playing in fixed steps, the pause (manual or
 * by losing the keyboard focus) and over. The game starts and resumes through [startOrResume], drives [tick] with a
 * monotonic clock and plays one step per [onStep]. FX thread, no JavaFX imports. The view draws every frame while
 * [needsAnimation], so steps do not call [onChange].
 *
 * Timing: the first tick after the animation (re)started only stores the time base; a pause or anything leaving
 * [needsAnimation] forgets it, so paused time never becomes steps. While playing, one [onStep] per full [stepNanos],
 * at most [maxStepsPerTick] per tick; beyond that the leftover time is dropped. The countdown shows 3, 2, 1 for
 * [countdownDigitNanos] each; the tick that ends it starts PLAYING with an empty accumulator.
 */
internal class RealtimeSession(
    private val hintFor: (focused: Boolean) -> String,
    private val stepNanos: Long = 16_666_667,
    private val maxStepsPerTick: Int = 5,
    private val countdownDigitNanos: Long = 500_000_000,
) {
    /** Called after a change of [state], [pauseReason] or, while PAUSED, of the pause hint. */
    var onChange: () -> Unit = {}

    /** Called once per fixed step while PLAYING. */
    var onStep: () -> Unit = {}

    /**
     * Called on every change to unfocused, in every state, before the session decides whether to pause: the game
     * releases its held input here, since the releases go to whatever has the focus now.
     */
    var onFocusLost: () -> Unit = {}

    var state = SessionState.READY
        private set

    var pauseReason: PauseReason? = null
        private set

    var isFocused = false
        private set

    private var countdownNanos = 0L
    private var accumulated = 0L
    private var base: Long? = null

    val pauseHint: String get() = hintFor(isFocused)

    /** 3, 2, 1 while counting down, else 0. */
    val countdown: Int
        get() {
            if (state != SessionState.COUNTDOWN) return 0
            return (DIGITS - (countdownNanos / countdownDigitNanos).toInt()).coerceAtLeast(1)
        }

    val needsAnimation: Boolean get() = state == SessionState.COUNTDOWN || state == SessionState.PLAYING

    val canPause: Boolean get() = needsAnimation || state == SessionState.PAUSED

    val pauseButtonText: String get() = if (state == SessionState.PAUSED) "Resume" else "Pause"

    /**
     * READY or PAUSED to COUNTDOWN, restarting at 3; true when it acted. Keyboard games may branch on [state] before
     * calling it; mouse-driven games can use the Boolean to eat the click that started or resumed.
     */
    fun startOrResume(): Boolean {
        if (state != SessionState.READY && state != SessionState.PAUSED) return false
        state = SessionState.COUNTDOWN
        pauseReason = null
        resetTiming()
        onChange()
        return true
    }

    /** The pause key or button: pauses while counting down or playing, resumes while paused. */
    fun togglePause() {
        when (state) {
            SessionState.COUNTDOWN, SessionState.PLAYING -> pause(PauseReason.MANUAL)
            SessionState.PAUSED -> startOrResume()
            SessionState.READY, SessionState.OVER -> Unit
        }
    }

    /** Losing the focus pauses while counting down or playing; regaining it never resumes. */
    fun focusChanged(focused: Boolean) {
        if (focused == isFocused) return
        isFocused = focused
        if (!focused) onFocusLost()
        if (!focused && needsAnimation) {
            pause(PauseReason.FOCUS)
        } else if (state == SessionState.PAUSED) {
            onChange()
        }
    }

    fun tick(nowNanos: Long) {
        if (!needsAnimation) return
        val last = base
        base = nowNanos
        if (last == null) return
        val elapsed = (nowNanos - last).coerceAtLeast(0)
        if (state == SessionState.COUNTDOWN) {
            countdownNanos += elapsed
            if (countdownNanos >= DIGITS * countdownDigitNanos) {
                accumulated = 0
                state = SessionState.PLAYING
                onChange()
            }
            return
        }
        accumulated += elapsed
        val due = accumulated / stepNanos
        val steps =
            if (due > maxStepsPerTick) {
                accumulated = 0
                maxStepsPerTick
            } else {
                accumulated -= due * stepNanos
                due.toInt()
            }
        repeat(steps) { if (state == SessionState.PLAYING) onStep() }
    }

    /** A new game: READY, the pause and the time base cleared. */
    fun reset() {
        state = SessionState.READY
        pauseReason = null
        resetTiming()
        onChange()
    }

    fun gameOver() {
        state = SessionState.OVER
        pauseReason = null
        resetTiming()
        onChange()
    }

    private fun pause(reason: PauseReason) {
        state = SessionState.PAUSED
        pauseReason = reason
        resetTiming()
        onChange()
    }

    private fun resetTiming() {
        base = null
        accumulated = 0
        countdownNanos = 0
    }

    private companion object {
        const val DIGITS = 3
    }
}
