package dev.sluide.commandruns

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** What to do with a request to start a job. */
internal sealed interface RunDecision {
    data object StartNow : RunDecision

    /** Ask whether to stop the running job first. */
    data object AskToStop : RunDecision
}

/** The run state machine of one view, without JavaFX: one run at a time, every run a fresh process. */
internal class Runs(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    var state: RunState = RunState.Idle
        private set

    private var stopWasRequested = false

    fun request(): RunDecision = if (state is RunState.Running) RunDecision.AskToStop else RunDecision.StartNow

    /** The process has started. */
    fun started() {
        stopWasRequested = false
        state = RunState.Running(timeSource.markNow())
    }

    /** The user asked to stop the running process; its end then counts as stopped, not failed. */
    fun stopRequested() {
        if (state is RunState.Running) stopWasRequested = true
    }

    /** The process ended with [exitCode]. */
    fun ended(exitCode: Int) {
        val running = state as? RunState.Running ?: return
        val duration = running.startedAt.elapsedNow()
        state =
            when {
                stopWasRequested -> RunState.Stopped
                exitCode == 0 -> RunState.Finished(duration)
                else -> RunState.Failed(exitCode, duration)
            }
        stopWasRequested = false
    }
}

/** Schedules [action] after [delay]; the returned function cancels it. */
internal fun interface StopScheduler {
    fun schedule(
        delay: Duration,
        action: () -> Unit,
    ): () -> Unit
}

/**
 * Stops a run in two steps: [interrupt] (Ctrl+C) at once, [kill] after [grace] if the run has not [ended] by then.
 * Thread-safe: the scheduler fires on its own thread.
 */
internal class StopEscalation(
    private val interrupt: () -> Unit,
    private val kill: () -> Unit,
    private val scheduler: StopScheduler,
    private val grace: Duration = 5.seconds,
) {
    private var cancel: (() -> Unit)? = null

    /** Interrupts now and schedules the kill; a second request while one is pending does nothing. */
    @Synchronized
    fun stop() {
        if (cancel != null) return
        cancel = scheduler.schedule(grace) { fire() }
        interrupt()
    }

    @Synchronized
    private fun fire() {
        if (cancel == null) return
        cancel = null
        kill()
    }

    /** The run ended: the pending kill is cancelled. */
    @Synchronized
    fun ended() {
        cancel?.invoke()
        cancel = null
    }
}
