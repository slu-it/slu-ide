package dev.sluide.gradle.view

import dev.sluide.fileviewers.ViewActivity
import dev.sluide.gradle.run.RunState
import kotlin.time.Duration

/** What a run of the view does: tasks, or loading the task list (which runs Gradle as well). */
internal sealed interface GradleJob {
    /** How the status and the background task name it. */
    val label: String

    data class Tasks(
        val arguments: List<String>,
    ) : GradleJob {
        override val label = arguments.joinToString(" ")
    }

    data object LoadTasks : GradleJob {
        override val label = "loading tasks"
    }
}

/** [duration] as `0:42`, `12:03` or `1:02:03`, whole seconds. */
internal fun elapsedText(duration: Duration): String {
    val seconds = duration.inWholeSeconds.coerceAtLeast(0)
    val (hours, minutes, rest) = Triple(seconds / 3600, seconds / 60 % 60, seconds % 60)
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/** The status line of [job] while it runs, [elapsed] so far. */
internal fun runningText(
    job: GradleJob,
    elapsed: Duration,
): String =
    when (job) {
        is GradleJob.Tasks -> "▶ ${job.label} — running ${elapsedText(elapsed)}"
        GradleJob.LoadTasks -> "▶ Loading tasks… ${elapsedText(elapsed)}"
    }

/** The status line of [job] once it ended as [state] says; `null` while it runs or for no run. */
internal fun endedText(
    job: GradleJob,
    state: RunState,
): String? =
    when (state) {
        is RunState.Finished -> {
            when (job) {
                is GradleJob.Tasks -> "✔ ${job.label} — finished in ${elapsedText(state.duration)}"
                GradleJob.LoadTasks -> "✔ Tasks loaded in ${elapsedText(state.duration)}"
            }
        }

        is RunState.Failed -> {
            val failure = "(exit ${state.exitCode}) after ${elapsedText(state.duration)}"
            when (job) {
                is GradleJob.Tasks -> "✖ ${job.label} — failed $failure"
                GradleJob.LoadTasks -> "✖ Loading tasks failed $failure"
            }
        }

        RunState.Stopped -> {
            when (job) {
                is GradleJob.Tasks -> "■ ${job.label} — stopped"
                GradleJob.LoadTasks -> "■ Loading tasks stopped"
            }
        }

        RunState.Idle, is RunState.Running -> {
            null
        }
    }

/** The style class of the status line in [state]. */
internal fun statusStyle(state: RunState): String? =
    when (state) {
        is RunState.Running -> "gradle-status-running"
        is RunState.Finished -> "gradle-status-finished"
        is RunState.Failed -> "gradle-status-failed"
        RunState.Stopped -> "gradle-status-stopped"
        RunState.Idle -> null
    }

/** The tab marker of a view whose last run is in [state]. */
internal fun activityOf(state: RunState): ViewActivity =
    when (state) {
        is RunState.Running -> ViewActivity.RUNNING
        is RunState.Failed -> ViewActivity.FAILED
        else -> ViewActivity.NONE
    }

/** Asked before [next] stops the [running] job: ":app:test is still running. Stop it and run :app:build?" */
internal fun stopQuestion(
    running: GradleJob,
    next: GradleJob,
): String {
    val subject =
        when (running) {
            is GradleJob.Tasks -> running.label
            GradleJob.LoadTasks -> "Loading the tasks"
        }
    val action =
        when (next) {
            is GradleJob.Tasks -> "run ${next.label}"
            GradleJob.LoadTasks -> "load the tasks again"
        }
    return "$subject is still running. Stop it and $action?"
}

/** The name of [job]'s row among the background tasks. */
internal fun backgroundName(job: GradleJob): String = "Gradle: ${job.label}"

/** Why closing the view of [fileName] while [job] runs should be confirmed. */
internal fun closeWarningText(
    fileName: String,
    job: GradleJob,
): String =
    when (job) {
        is GradleJob.Tasks -> "$fileName is still running ${job.label}."
        GradleJob.LoadTasks -> "$fileName is still loading tasks."
    }
