package dev.sluide.gradle.view

import dev.sluide.commandruns.RunJob
import dev.sluide.commandruns.RunState
import dev.sluide.commandruns.elapsedText
import kotlin.time.Duration

/**
 * What a run of the view does: tasks, or loading the task list (which runs Gradle as well). Tasks keep the default
 * texts of a [RunJob]; the load has its own.
 */
internal sealed interface GradleJob : RunJob {
    override val backgroundName: String get() = "Gradle: $label"

    data class Tasks(
        val arguments: List<String>,
    ) : GradleJob {
        override val label = arguments.joinToString(" ")
    }

    data object LoadTasks : GradleJob {
        override val label = "loading tasks"

        override fun runningText(elapsed: Duration) = "▶ Loading tasks… ${elapsedText(elapsed)}"

        override fun endedText(state: RunState): String? =
            when (state) {
                is RunState.Finished -> {
                    "✔ Tasks loaded in ${elapsedText(state.duration)}"
                }

                is RunState.Failed -> {
                    "✖ Loading tasks failed (exit ${state.exitCode}) after ${elapsedText(state.duration)}"
                }

                RunState.Stopped -> {
                    "■ Loading tasks stopped"
                }

                RunState.Idle, is RunState.Running -> {
                    null
                }
            }

        override val stopSubject = "Loading the tasks"

        override val stopAction = "load the tasks again"

        override fun closeWarning(fileName: String) = "$fileName is still loading tasks."
    }
}
