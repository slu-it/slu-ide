package dev.sluide.gradle.run

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Where the runs of one view stand. */
sealed interface RunState {
    data object Idle : RunState

    data class Running(
        val tasks: List<String>,
        val startedAt: TimeMark,
    ) : RunState

    data class Finished(
        val tasks: List<String>,
        val duration: Duration,
    ) : RunState

    data class Failed(
        val tasks: List<String>,
        val exitCode: Int,
        val duration: Duration,
    ) : RunState

    /** The user stopped the last run. */
    data object Stopped : RunState
}

/** What to do with a request to run tasks. */
sealed interface RunDecision {
    data object StartNow : RunDecision

    /** Ask whether to stop the [running] tasks first. */
    data class AskToStop(
        val running: List<String>,
    ) : RunDecision
}

/** The run state machine of one view, without JavaFX: one run at a time, every run a fresh process. */
class GradleRuns(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    var state: RunState = RunState.Idle
        private set

    private var stopWasRequested = false

    fun request(tasks: List<String>): RunDecision =
        (state as? RunState.Running)?.let { RunDecision.AskToStop(it.tasks) } ?: RunDecision.StartNow

    /** The process for [tasks] has started. */
    fun started(tasks: List<String>) {
        stopWasRequested = false
        state = RunState.Running(tasks, timeSource.markNow())
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
                exitCode == 0 -> RunState.Finished(running.tasks, duration)
                else -> RunState.Failed(running.tasks, exitCode, duration)
            }
        stopWasRequested = false
    }
}

/** Schedules [action] after [delay]; the returned function cancels it. */
fun interface StopScheduler {
    fun schedule(
        delay: Duration,
        action: () -> Unit,
    ): () -> Unit
}

/**
 * Stops a run in two steps: [interrupt] (Ctrl+C) at once, [kill] after [grace] if the run has not [ended] by then.
 * Thread-safe: the scheduler fires on its own thread.
 */
class StopEscalation(
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
