package dev.sluide.commandruns

import kotlin.time.Duration

/**
 * What a [CommandRunner] runs, and how the status line, the stop question, the background task row and the close
 * warning name it. The texts default to those of a run of named tasks, e.g. `▶ :app:test — running 0:42`.
 */
interface RunJob {
    /** How the default texts name the job. */
    val label: String

    /** The status line while the job runs, [elapsed] so far. */
    fun runningText(elapsed: Duration): String = "▶ $label — running ${elapsedText(elapsed)}"

    /** The status line once the job ended as [state] says; `null` while it runs or for no run. */
    fun endedText(state: RunState): String? =
        when (state) {
            is RunState.Finished -> "✔ $label — finished in ${elapsedText(state.duration)}"
            is RunState.Failed -> "✖ $label — failed (exit ${state.exitCode}) after ${elapsedText(state.duration)}"
            RunState.Stopped -> "■ $label — stopped"
            RunState.Idle, is RunState.Running -> null
        }

    /** Subject of the stop question while this job runs ("test" → "test is still running."). */
    val stopSubject: String get() = label

    /** What starting this job does, in the stop question ("run build"). */
    val stopAction: String get() = "run $label"

    /** The name of the job's row among the background tasks. */
    val backgroundName: String

    /** Why closing the view of [fileName] while this job runs should be confirmed. */
    fun closeWarning(fileName: String): String = "$fileName is still running $label."
}

/** Asked before [next] stops the [running] job: ":app:test is still running. Stop it and run :app:build?" */
fun stopQuestion(
    running: RunJob,
    next: RunJob,
): String = "${running.stopSubject} is still running. Stop it and ${next.stopAction}?"
