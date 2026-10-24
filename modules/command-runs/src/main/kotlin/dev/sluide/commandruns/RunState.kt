package dev.sluide.commandruns

import dev.sluide.fileviewers.ViewActivity
import kotlin.time.Duration
import kotlin.time.TimeMark

/** Where the runs of one view stand. */
sealed interface RunState {
    data object Idle : RunState

    data class Running(
        val startedAt: TimeMark,
    ) : RunState

    data class Finished(
        val duration: Duration,
    ) : RunState

    data class Failed(
        val exitCode: Int,
        val duration: Duration,
    ) : RunState

    /** The user stopped the last run. */
    data object Stopped : RunState
}

/** How a request to start a job ended. */
enum class StartResult { STARTED, CANCELLED, FAILED }

/** The colour of the status line. */
enum class RunStatus { RUNNING, FINISHED, FAILED, STOPPED }

/** [duration] as `0:42`, `12:03` or `1:02:03`, whole seconds. */
fun elapsedText(duration: Duration): String {
    val seconds = duration.inWholeSeconds.coerceAtLeast(0)
    val (hours, minutes, rest) = Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/** The colour of the status line in [state]; `null` (plain) before the first run. */
fun statusOf(state: RunState): RunStatus? =
    when (state) {
        is RunState.Running -> RunStatus.RUNNING
        is RunState.Finished -> RunStatus.FINISHED
        is RunState.Failed -> RunStatus.FAILED
        RunState.Stopped -> RunStatus.STOPPED
        RunState.Idle -> null
    }

/** The tab marker of a view whose last run is in [state]. */
fun activityOf(state: RunState): ViewActivity =
    when (state) {
        is RunState.Running -> ViewActivity.RUNNING
        is RunState.Failed -> ViewActivity.FAILED
        else -> ViewActivity.NONE
    }
