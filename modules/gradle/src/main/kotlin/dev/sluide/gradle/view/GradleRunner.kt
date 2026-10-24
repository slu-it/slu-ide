package dev.sluide.gradle.view

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.gradle.GradleSession
import dev.sluide.gradle.run.GradleRuns
import dev.sluide.gradle.run.RunDecision
import dev.sluide.gradle.run.RunState
import dev.sluide.gradle.run.StopEscalation
import dev.sluide.terminal.EmbeddedTerminal
import dev.sluide.terminal.TerminalStartException
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How a request to start a job ended. */
internal enum class StartResult { STARTED, CANCELLED, FAILED }

/**
 * Runs the jobs of one Gradle view (ADR 0085, ADR 0086), one at a time, each a fresh process in a fresh terminal in the
 * [pane] that replaces the previous one: asks before stopping a running job for the next one ([confirmStop]), shows
 * the status line ticking every [tick], the background task row (✕ stops) and the [activity] for the tab. Stop sends
 * Ctrl+C and ends the process after [grace]. FX thread.
 */
internal class GradleRunner(
    private val session: GradleSession,
    private val pane: RunPane,
    /** The folder the runs start in: the build file's. */
    private val folder: () -> Path,
    /** Whether to stop the running job for the next one. */
    private val confirmStop: (running: GradleJob, next: GradleJob) -> Boolean,
    private val grace: Duration = 5.seconds,
    tick: javafx.util.Duration = javafx.util.Duration.seconds(1.0),
) {
    private val runs = GradleRuns()
    private val activityWrapper = ReadOnlyObjectWrapper(ViewActivity.NONE)

    /** Running, failed (until [acknowledge]d) or none. */
    val activity: ReadOnlyObjectProperty<ViewActivity> = activityWrapper.readOnlyProperty

    private var terminal: EmbeddedTerminal? = null
    private var job: GradleJob? = null
    private var escalation: StopEscalation? = null
    private var row: BackgroundTask? = null
    private var onEnded: ((RunState) -> Unit)? = null
    private var disposed = false

    /** The tasks of the last run, for Rerun. */
    var lastTasks: GradleJob.Tasks? = null
        private set

    private val timer =
        Timeline(KeyFrame(tick, { showRunning() })).apply { cycleCount = Animation.INDEFINITE }

    init {
        pane.stop.setOnAction { stop() }
    }

    /** The job running now, if any. */
    val running: GradleJob? get() = job.takeIf { runs.state is RunState.Running }

    /**
     * Starts [job] with the command line [command] (whose [IllegalArgumentException] the status line shows) and calls
     * [ended] with its outcome. While another job runs, asks first; stopping it closes its terminal at once. The new
     * terminal leaves the focus where it is.
     */
    fun start(
        job: GradleJob,
        command: () -> String,
        ended: (RunState) -> Unit = {},
    ): StartResult {
        if (disposed) return StartResult.CANCELLED
        val decision = runs.request(tasksOf(job))
        if (decision is RunDecision.AskToStop) {
            val current = checkNotNull(this.job)
            if (!confirmStop(current, job)) return StartResult.CANCELLED
            // The hangup ends the Gradle client; the run counts as stopped.
            runs.stopRequested()
            terminal?.close()
        }
        val line =
            try {
                command()
            } catch (e: IllegalArgumentException) {
                return failedToStart(e.message ?: "Invalid arguments")
            }
        val next =
            try {
                session.terminals.embed(folder(), line)
            } catch (e: TerminalStartException) {
                return failedToStart(e.message ?: "Gradle could not be started")
            }
        terminal?.close()
        terminal = next
        this.job = job
        if (job is GradleJob.Tasks) lastTasks = job
        onEnded = ended
        pane.showTerminal(next.node)
        runs.started(tasksOf(job))
        escalation =
            StopEscalation(
                interrupt = next::interrupt,
                // The scheduler fires on its own thread.
                kill = { Platform.runLater { next.close() } },
                scheduler = session.stopScheduler,
                grace = grace,
            )
        row = session.backgroundTasks.start(backgroundName(job)) { stop() }
        next.exited.subscribe { exited -> if (exited) ended(next) }
        activityWrapper.set(ViewActivity.RUNNING)
        pane.stop.isDisable = false
        pane.rerun.isDisable = lastTasks == null
        timer.playFromStart()
        showRunning()
        return StartResult.STARTED
    }

    /** Stops the running job: Ctrl+C now, the process ended after the grace. */
    fun stop() {
        if (runs.state !is RunState.Running) return
        runs.stopRequested()
        escalation?.stop()
    }

    /** The view was shown: a failure no longer marks its tab. */
    fun acknowledge() {
        if (activityWrapper.get() == ViewActivity.FAILED) activityWrapper.set(ViewActivity.NONE)
    }

    /** Shows [text] as a failure in the status line, e.g. a task list that could not be read. */
    fun showProblem(text: String) = pane.showStatus(text, "gradle-status-failed")

    /** Why closing the view of [fileName] should be confirmed now; `null` while nothing runs. */
    fun closeWarning(fileName: String): String? = running?.let { closeWarningText(fileName, it) }

    /** Ends a running job and closes the terminal; nothing is reported to the view anymore. */
    fun dispose() {
        disposed = true
        onEnded = null
        timer.stop()
        if (runs.state is RunState.Running) runs.stopRequested()
        terminal?.close()
        terminal = null
        row?.close()
        row = null
    }

    private fun ended(ended: EmbeddedTerminal) {
        if (ended !== terminal || runs.state !is RunState.Running) return
        escalation?.ended()
        escalation = null
        timer.stop()
        val exitCode = ended.exitCode
        // Closed before it ended: stopped, as only stopping closes a running terminal.
        if (exitCode == null) runs.stopRequested()
        runs.ended(exitCode ?: STOPPED_EXIT)
        val state = runs.state
        val job = checkNotNull(this.job)
        endedText(job, state)?.let { pane.showStatus(it, statusStyle(state)) }
        activityWrapper.set(activityOf(state))
        pane.stop.isDisable = true
        val row = row
        this.row = null
        if (state is RunState.Failed) row?.failed("exit ${state.exitCode}") else row?.close()
        val callback = onEnded
        onEnded = null
        callback?.invoke(state)
    }

    private fun showRunning() {
        val running = runs.state as? RunState.Running ?: return
        val job = this.job ?: return
        pane.showStatus(runningText(job, running.startedAt.elapsedNow()), statusStyle(running))
    }

    private fun failedToStart(message: String): StartResult {
        pane.showStatus("✖ $message", "gradle-status-failed")
        return StartResult.FAILED
    }

    private fun tasksOf(job: GradleJob) = (job as? GradleJob.Tasks)?.paths ?: emptyList()

    private companion object {
        /** Stands for the exit code of a process closed before it ended; such a run counts as stopped. */
        const val STOPPED_EXIT = 130
    }
}
