package dev.sluide.games.solitaire

/** A delayed task that can be called off. */
internal fun interface Cancellable {
    fun cancel()
}

/**
 * Plays the moves that finish a decided game one at a time through [play], [delayMillis] apart, using [schedule] (an
 * FX-thread delay). While [paused] it waits and goes on after [resume]. FX thread.
 */
internal class AutoFinisher(
    private val schedule: (Long, Runnable) -> Cancellable,
    private val delayMillis: Long,
    private val paused: () -> Boolean,
    private val play: (Move) -> Unit,
) {
    private var pending = ArrayDeque<Move>()
    private var task: Cancellable? = null
    private var waiting = false
    private var run = 0

    /** From [start] until the last move is handed to [play] or [stop]. */
    var isRunning = false
        private set

    /** Starts with [moves]; nothing when they are empty. */
    fun start(moves: List<Move>) {
        stop()
        if (moves.isEmpty()) return
        pending = ArrayDeque(moves)
        isRunning = true
        next()
    }

    fun stop() {
        run++
        task?.cancel()
        task = null
        pending.clear()
        waiting = false
        isRunning = false
    }

    /** The pause ended: goes on if a move waited for it. */
    fun resume() {
        if (!waiting || paused()) return
        waiting = false
        next()
    }

    private fun next() {
        val mine = run
        task =
            schedule(
                delayMillis,
                Runnable {
                    if (mine != run) return@Runnable
                    task = null
                    fire()
                },
            )
    }

    private fun fire() {
        if (paused()) {
            waiting = true
            return
        }
        val move = pending.removeFirst()
        if (pending.isEmpty()) isRunning = false
        val mine = run
        play(move)
        // play may have stopped or restarted the run (a win, an illegal move); then the new state schedules itself.
        if (isRunning && mine == run) next()
    }
}
