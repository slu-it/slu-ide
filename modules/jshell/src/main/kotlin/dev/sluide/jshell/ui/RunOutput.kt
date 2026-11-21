package dev.sluide.jshell.ui

import dev.sluide.jshell.EvalEvent

/** Where a [dev.sluide.jshell.view.JShellRuns] writes what happens: the transcript. Called on the thread of the FX hop. */
internal interface RunOutput {
    fun startEvaluation(text: String)

    fun accept(event: EvalEvent)

    fun lateOutput(
        text: String,
        error: Boolean,
    )

    fun note(text: String)
}
