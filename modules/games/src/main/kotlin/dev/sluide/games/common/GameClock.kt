package dev.sluide.games.common

/**
 * Elapsed play time; ticks only while started, not stopped, not [hidden], not [manuallyPaused] and not [held]. FX
 * thread. [nowMillis] is a monotonic clock. [start] after [stop] has no effect until [reset].
 */
class GameClock(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var accumulated = 0L
    private var since: Long? = null

    var isStarted = false
        private set

    var isStopped = false
        private set

    /** The tab is not shown (automatic pause). */
    var hidden = false
        set(value) {
            field = value
            sync()
        }

    var manuallyPaused = false
        set(value) {
            field = value
            sync()
        }

    /** Held by the game while it waits for something else (a new deal being searched), not a pause the user sees. */
    var held = false
        set(value) {
            field = value
            sync()
        }

    val isTicking: Boolean get() = isStarted && !isStopped && !hidden && !manuallyPaused && !held

    val elapsedMillis: Long get() = accumulated + (since?.let { nowMillis() - it } ?: 0L)

    fun start() {
        if (isStopped) return
        isStarted = true
        sync()
    }

    fun stop() {
        isStopped = true
        sync()
    }

    /** Back to 0, not started, manual pause and hold cleared. */
    fun reset() {
        accumulated = 0
        since = null
        isStarted = false
        isStopped = false
        manuallyPaused = false
        held = false
    }

    private fun sync() {
        val begun = since
        if (isTicking && begun == null) {
            since = nowMillis()
        } else if (!isTicking && begun != null) {
            accumulated += nowMillis() - begun
            since = null
        }
    }
}
