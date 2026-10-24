package dev.sluide.commandruns

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.fileviewers.ViewActivity
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

/**
 * Runs the jobs of one view (ADR 0086, ADR 0088), one at a time, each a fresh process in a fresh embedded terminal in
 * the [pane] that replaces the previous one: asks before stopping a running job for the next one ([confirmStop]), shows
 * the status line ticking every [tick], the background task row (✕ stops) and the [activity] for the tab. Stop sends
 * Ctrl+C and ends the process after [grace]. FX thread.
 */
class CommandRunner<J : RunJob>(
    private val session: CommandRunSession,
    private val pane: RunPane,
    /** The folder the runs start in. */
    private val folder: () -> Path,
    /** Whether to stop the running job for the next one. */
    private val confirmStop: (running: J, next: J) -> Boolean,
    /** Whether Rerun runs a job again ([last]). */
    private val rerunnable: (J) -> Boolean = { true },
    private val grace: Duration = 5.seconds,
    tick: javafx.util.Duration = javafx.util.Duration.seconds(1.0),
) {
    private val runs = Runs()
    private val activityWrapper = ReadOnlyObjectWrapper(ViewActivity.NONE)

    /** Running, failed (until [acknowledge]d) or none. */
    val activity: ReadOnlyObjectProperty<ViewActivity> = activityWrapper.readOnlyProperty

    private var terminal: EmbeddedTerminal? = null
    private var job: J? = null
    private var escalation: StopEscalation? = null
    private var row: BackgroundTask? = null
    private var onEnded: ((RunState) -> Unit)? = null
    private var disposed = false

    /** Starts the terminal of a run in a folder; tests put a fake in its place. */
    internal var embed: (folder: Path, command: String) -> EmbeddedTerminal = { folder, command ->
        session.terminals.embed(folder, command)
    }

    /** The last started job that is [rerunnable], for Rerun. */
    var last: J? = null
        private set

    private val timer =
        Timeline(KeyFrame(tick, { showRunning() })).apply { cycleCount = Animation.INDEFINITE }

    init {
        pane.stop.setOnAction { stop() }
    }

    /** The job running now, if any. */
    val running: J? get() = job.takeIf { runs.state is RunState.Running }

    /**
     * Makes way for [next]: while a job runs, asks [confirmStop]; if yes, stops it and closes its terminal at once (the
     * run counts as stopped) and returns `true`, else `false`. Returns `true` without asking while nothing runs. The
     * stopped job's `ended` callback runs inside, before this returns.
     */
    fun replaceRunning(next: J): Boolean {
        if (runs.request() !is RunDecision.AskToStop) return true
        val current = checkNotNull(job)
        if (!confirmStop(current, next)) return false
        // The hangup ends the command; the run counts as stopped.
        runs.stopRequested()
        terminal?.let { old ->
            old.close()
            // A terminal may report its end only later; the run is over now, so a start right after does not ask again.
            ended(old)
        }
        return true
    }

    /**
     * Starts [job] with the command line [command] (whose [IllegalArgumentException] the status line shows) and calls
     * [ended] with its outcome. While another job runs, asks first ([replaceRunning]). The new terminal leaves the
     * focus where it is.
     */
    fun start(
        job: J,
        command: () -> String,
        ended: (RunState) -> Unit = {},
    ): StartResult {
        if (disposed) return StartResult.CANCELLED
        if (!replaceRunning(job)) return StartResult.CANCELLED
        val line =
            try {
                command()
            } catch (e: IllegalArgumentException) {
                return failedToStart(e.message ?: "Invalid arguments")
            }
        val next =
            try {
                embed(folder(), line)
            } catch (e: TerminalStartException) {
                return failedToStart(e.message ?: "The command could not be started")
            }
        terminal?.close()
        terminal = next
        this.job = job
        if (rerunnable(job)) last = job
        onEnded = ended
        pane.showTerminal(next.node)
        runs.started()
        escalation =
            StopEscalation(
                interrupt = next::interrupt,
                // The scheduler fires on its own thread.
                kill = { Platform.runLater { next.close() } },
                scheduler = session.stopScheduler,
                grace = grace,
            )
        row = session.backgroundTasks.start(job.backgroundName) { stop() }
        next.exited.subscribe { exited -> if (exited) ended(next) }
        activityWrapper.set(ViewActivity.RUNNING)
        pane.stop.isDisable = false
        pane.rerun.isDisable = last == null
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

    /** Shows [text] as a failure in the status line, e.g. an output that could not be read. */
    fun showProblem(text: String) = pane.showStatus(text, RunStatus.FAILED)

    /** Why closing the view of [fileName] should be confirmed now; `null` while nothing runs. */
    fun closeWarning(fileName: String): String? = running?.closeWarning(fileName)

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
        job.endedText(state)?.let { pane.showStatus(it, statusOf(state)) }
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
        pane.showStatus(job.runningText(running.startedAt.elapsedNow()), statusOf(running))
    }

    private fun failedToStart(message: String): StartResult {
        pane.showStatus("✖ $message", RunStatus.FAILED)
        return StartResult.FAILED
    }

    private companion object {
        /** Stands for the exit code of a process closed before it ended; such a run counts as stopped. */
        const val STOPPED_EXIT = 130
    }
}
